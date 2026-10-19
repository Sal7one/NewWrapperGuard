"""Strict, explicitly selected user profiles and passive DAST target boundaries."""
from __future__ import annotations

from dataclasses import dataclass
import ipaddress
import json
import math
import os
from pathlib import Path
import re
import stat
from urllib.parse import unquote, urlsplit

from .models import _text, validate_actions

MAX_PROFILE_BYTES = 65_536
MAX_REPORT_BYTES = 16 * 1024 * 1024
MAX_OUTPUT_BYTES = 4 * 1024 * 1024
OPERATIONS = frozenset({"SCAN", "BUILD", "SYNC"})
FORMATS = frozenset({"sarif", "bandit", "psscriptanalyzer", "trivy", "osv", "zap"})


def _keys(value: object, allowed: set[str], required: set[str] = frozenset()) -> dict:
    if not isinstance(value, dict) or set(value) - allowed or required - set(value):
        raise ValueError("Invalid profile fields")
    return value


def _pairs(pairs: list[tuple[str, object]]) -> dict:
    result: dict = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON field")
        result[key] = value
    return result


def load_json_document(raw: bytes | str) -> dict | list:
    if isinstance(raw, bytes):
        raw = raw.decode("utf-8", "strict")
    def finite_float(value: str) -> float:
        number = float(value)
        if not math.isfinite(number):
            raise ValueError("Invalid JSON number")
        return number

    value = json.loads(raw, object_pairs_hook=_pairs, parse_float=finite_float,
                       parse_constant=lambda _: (_ for _ in ()).throw(ValueError("Invalid JSON number")))
    if not isinstance(value, (dict, list)):
        raise ValueError("Expected JSON object or array")
    return value


def bounded_read(path: Path, limit: int) -> bytes:
    fd = os.open(path, os.O_RDONLY | getattr(os, "O_NONBLOCK", 0))
    with os.fdopen(fd, "rb") as stream:
        if not stat.S_ISREG(os.fstat(stream.fileno()).st_mode):
            raise ValueError("A regular input file is required")
        data = stream.read(limit + 1)
    if len(data) > limit:
        raise ValueError("Input exceeds limit")
    return data


def _origin_and_path(url: str) -> tuple[str, str]:
    if not isinstance(url, str) or not url or len(url) > 8192 or any(c.isspace() or ord(c) < 32 for c in url):
        raise ValueError("Invalid target URL")
    parsed = urlsplit(url)
    if parsed.scheme.lower() not in ("http", "https") or not parsed.netloc or "@" in parsed.netloc or parsed.fragment or "#" in url:
        raise ValueError("Invalid target URL")
    host = parsed.hostname
    if not host or "%" in host or "\\" in parsed.netloc or parsed.netloc.endswith(":"):
        raise ValueError("Invalid target URL")
    try:
        address = ipaddress.ip_address(host)
        host = "[" + str(address) + "]" if address.version == 6 else str(address)
    except ValueError:
        host = host.encode("idna").decode("ascii").lower()
        if host.endswith(".") or not re.fullmatch(r"[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?", host) or ".." in host:
            raise ValueError("Invalid target URL")
    port = parsed.port
    if port is not None and not 1 <= port <= 65535:
        raise ValueError("Invalid target URL")
    scheme = parsed.scheme.lower()
    origin = scheme + "://" + host
    if port is not None and port != (443 if scheme == "https" else 80):
        origin += ":" + str(port)
    return origin, _scope_path(parsed.path or "/")


def _scope_path(path: str) -> str:
    if not isinstance(path, str) or not path.startswith("/") or "?" in path or "#" in path:
        raise ValueError("Invalid target path scope")
    decoded = unquote(path, encoding="utf-8", errors="strict")
    if ("\\" in decoded or "//" in decoded or any(ord(c) < 32 for c in decoded)
            or re.search(r"%[0-9a-fA-F]{2}", decoded)
            or any(part in (".", "..") for part in decoded.split("/"))):
        raise ValueError("Invalid target path scope")
    return decoded.rstrip("/") or "/"


@dataclass(frozen=True)
class TargetScope:
    origins: tuple[str, ...]
    path_prefixes: tuple[str, ...] = ("/",)

    def __post_init__(self) -> None:
        if not isinstance(self.origins, (list, tuple)) or not 1 <= len(self.origins) <= 32:
            raise ValueError("At least one exact target origin is required")
        origins: list[str] = []
        for value in self.origins:
            origin, path = _origin_and_path(value)
            parsed = urlsplit(value)
            if path != "/" or parsed.query or "?" in value:
                raise ValueError("Target origins must not contain paths or queries")
            origins.append(origin)
        if not isinstance(self.path_prefixes, (list, tuple)) or not 1 <= len(self.path_prefixes) <= 32:
            raise ValueError("Invalid target path scopes")
        prefixes = [_scope_path(value) for value in self.path_prefixes]
        object.__setattr__(self, "origins", tuple(dict.fromkeys(origins)))
        object.__setattr__(self, "path_prefixes", tuple(dict.fromkeys(prefixes)))

    @classmethod
    def from_dict(cls, value: dict) -> TargetScope:
        value = _keys(value, {"origins", "pathPrefixes"}, {"origins"})
        return cls(value["origins"], value.get("pathPrefixes", ("/",)))

    def allows(self, url: str) -> bool:
        try:
            origin, path = _origin_and_path(url)
            return origin in self.origins and any(prefix == "/" or path == prefix or path.startswith(prefix + "/")
                                                  for prefix in self.path_prefixes)
        except (ValueError, UnicodeError):
            return False

    def to_dict(self) -> dict:
        return {"origins": list(self.origins), "pathPrefixes": list(self.path_prefixes)}


@dataclass(frozen=True)
class RunnerConfig:
    tool: str
    executable: Path
    timeout_ms: int = 30_000
    max_output_bytes: int = MAX_REPORT_BYTES


@dataclass(frozen=True)
class IntegrationProfile:
    id: str
    display_name: str
    version: str
    analysis_kind: str
    format: str
    report_path: Path | None
    runner: RunnerConfig | None
    allowed_operations: tuple[str, ...]
    requested_actions: tuple[str, ...]
    target_scope: TargetScope | None


def load_profile(path: Path, project_root: Path) -> IntegrationProfile:
    if not path.is_absolute():
        raise ValueError("Profile path must be absolute")
    root = project_root.resolve(strict=True)
    actual = path.resolve(strict=True)
    if not root.is_dir() or actual.is_relative_to(root) or not actual.is_file():
        raise ValueError("User profile must be outside the project")
    raw = load_json_document(bounded_read(actual, MAX_PROFILE_BYTES))
    value = _keys(raw, {"id", "displayName", "version", "analysisKind", "format", "reportPath", "runner",
                        "allowedOperations", "requestedActions", "targetScope"},
                  {"id", "displayName", "version", "analysisKind", "format"})
    if not isinstance(value["id"], str) or not re.fullmatch(r"[a-z][a-z0-9._-]{0,79}", value["id"]) or value["id"] == "wrapperguard":
        raise ValueError("Invalid profile identity")
    display_name, version = _text(value["displayName"], 160), _text(value["version"], 80)
    kind, format_name = value["analysisKind"], value["format"]
    if not isinstance(kind, str) or kind not in {"SAST", "SCA", "DAST"} or not isinstance(format_name, str) or format_name not in FORMATS:
        raise ValueError("Invalid profile classification")
    kinds = {"sarif": {"SAST", "SCA"}, "bandit": {"SAST"}, "psscriptanalyzer": {"SAST"},
             "trivy": {"SCA"}, "osv": {"SCA"}, "zap": {"DAST"}}
    if kind not in kinds[format_name]:
        raise ValueError("Tool format does not match analysis kind")
    if ("reportPath" in value) == ("runner" in value):
        raise ValueError("Exactly one report import or runner is required")
    operations = value.get("allowedOperations", ["SCAN"])
    if (not isinstance(operations, list) or not operations or len(operations) > 3
            or any(not isinstance(operation, str) or operation not in OPERATIONS for operation in operations)
            or len(set(operations)) != len(operations)):
        raise ValueError("Invalid allowed operations")
    actions = validate_actions(value.get("requestedActions", ["WARNING"]))
    scope = TargetScope.from_dict(value["targetScope"]) if "targetScope" in value else None
    if (kind == "DAST") != (scope is not None):
        raise ValueError("DAST requires an explicit target scope")
    report, runner = None, None
    if "reportPath" in value:
        if not isinstance(value["reportPath"], str) or not value["reportPath"] or "\x00" in value["reportPath"]:
            raise ValueError("Invalid report path")
        report = Path(value["reportPath"])
        if not report.is_absolute():
            raise ValueError("Report path must be absolute")
    else:
        config = _keys(value["runner"], {"tool", "executable", "timeoutMs", "maxOutputBytes"}, {"tool", "executable"})
        if config["tool"] != "bandit" or format_name != "bandit" or kind != "SAST":
            raise ValueError("Only the fixed Bandit runner is supported")
        if not isinstance(config["executable"], str) or not Path(config["executable"]).is_absolute():
            raise ValueError("An absolute tool executable is required")
        executable = Path(config["executable"]).resolve(strict=True)
        if executable.is_relative_to(root) or not executable.is_file() or not os.access(executable, os.X_OK):
            raise ValueError("Tool executable must be installed outside the project")
        timeout, maximum = config.get("timeoutMs", 30_000), config.get("maxOutputBytes", MAX_REPORT_BYTES)
        if type(timeout) is not int or not 100 <= timeout <= 300_000:
            raise ValueError("Invalid tool timeout")
        if type(maximum) is not int or not 1 <= maximum <= MAX_REPORT_BYTES:
            raise ValueError("Invalid tool output limit")
        runner = RunnerConfig("bandit", executable, timeout, maximum)
    return IntegrationProfile(value["id"], display_name, version, kind, format_name, report, runner,
                              tuple(operations), tuple(actions), scope)
