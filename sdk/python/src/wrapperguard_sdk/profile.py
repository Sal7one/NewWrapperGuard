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
from .rule_packs import RULE_PACKS

MAX_PROFILE_BYTES = 65_536
MAX_REPORT_BYTES = 16 * 1024 * 1024
MAX_OUTPUT_BYTES = 4 * 1024 * 1024
OPERATIONS = frozenset({"SCAN", "BUILD", "SYNC"})
FORMATS = frozenset({"sarif", "bandit", "psscriptanalyzer", "trivy", "osv", "zap", "script-semantics", "ai-review", "cyclonedx", "sonar", "http-audit"})


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
    executable: Path | None = None
    timeout_ms: int = 30_000
    max_output_bytes: int = MAX_REPORT_BYTES
    module_path: Path | None = None
    endpoint: str | None = None
    model: str | None = None
    input_report_path: Path | None = None
    cache_path: Path | None = None
    rules_path: Path | None = None
    response_format: str = "json_schema"
    rule_packs: tuple[str, ...] | None = None
    ownership_confirmed: bool = False
    target_urls: tuple[str, ...] | None = None
    max_requests: int = 16
    request_timeout_ms: int = 5000
    max_response_bytes: int = 262144
    auth_headers_env: tuple[tuple[str, str], ...] = ()


@dataclass(frozen=True)
class ReportProvenance:
    manifest_path: Path
    max_age_seconds: int = 86400


@dataclass(frozen=True)
class DependencyCoverage:
    require_resolved_versions: bool = False


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
    project_root: Path | None = None
    report_provenance: ReportProvenance | None = None
    dependency_coverage: DependencyCoverage | None = None


def load_profile(path: Path, project_root: Path) -> IntegrationProfile:
    if not path.is_absolute():
        raise ValueError("Profile path must be absolute")
    root = project_root.resolve(strict=True)
    actual = path.resolve(strict=True)
    if not root.is_dir() or actual.is_relative_to(root) or not actual.is_file():
        raise ValueError("User profile must be outside the project")
    raw = load_json_document(bounded_read(actual, MAX_PROFILE_BYTES))
    value = _keys(raw, {"id", "displayName", "version", "analysisKind", "format", "reportPath", "runner",
                        "allowedOperations", "requestedActions", "targetScope", "projectRoot", "reportProvenance", "dependencyCoverage"},
                  {"id", "displayName", "version", "analysisKind", "format"})
    if not isinstance(value["id"], str) or not re.fullmatch(r"[a-z][a-z0-9._-]{0,79}", value["id"]) or value["id"] == "wrapperguard":
        raise ValueError("Invalid profile identity")
    binding = None
    if "projectRoot" in value:
        if not isinstance(value["projectRoot"], str) or not Path(value["projectRoot"]).is_absolute():
            raise ValueError("Profile project binding requires an absolute project root")
        binding = Path(value["projectRoot"]).resolve(strict=True)
        if binding != root:
            raise ValueError("This integration profile belongs to another project")
    display_name, version = _text(value["displayName"], 160), _text(value["version"], 80)
    kind, format_name = value["analysisKind"], value["format"]
    if not isinstance(kind, str) or kind not in {"SAST", "SCA", "DAST", "AI"} or not isinstance(format_name, str) or format_name not in FORMATS:
        raise ValueError("Invalid profile classification")
    kinds = {"sarif": {"SAST", "SCA"}, "bandit": {"SAST"}, "psscriptanalyzer": {"SAST"},
             "trivy": {"SCA"}, "osv": {"SCA"}, "zap": {"DAST"},
             "script-semantics": {"SAST"}, "ai-review": {"AI"}, "cyclonedx": {"SCA"}, "sonar": {"SAST"}, "http-audit": {"DAST"}}
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
        if format_name in {"script-semantics", "ai-review", "http-audit"}:
            raise ValueError("This method requires its controlled runner")
        if not isinstance(value["reportPath"], str) or not value["reportPath"] or "\x00" in value["reportPath"]:
            raise ValueError("Invalid report path")
        report = Path(value["reportPath"])
        if not report.is_absolute():
            raise ValueError("Report path must be absolute")
    else:
        config = _keys(value["runner"], {"tool", "executable", "timeoutMs", "maxOutputBytes", "modulePath",
                                         "endpoint", "model", "inputReportPath", "cachePath", "rulesPath", "responseFormat", "rulePacks",
                                         "ownershipConfirmed", "targetUrls", "maxRequests", "requestTimeoutMs", "maxResponseBytes", "authHeadersEnv"}, {"tool"})
        tool = config["tool"]
        formats = {"bandit": "bandit", "psscriptanalyzer": "psscriptanalyzer", "trivy": "trivy",
                   "osv": "osv", "script-semantics": "script-semantics", "local-ai": "ai-review", "semgrep": "sarif", "http-audit": "http-audit"}
        if not isinstance(tool, str) or formats.get(tool) != format_name:
            raise ValueError("Unsupported runner or format mismatch")
        allowed = {"tool", "timeoutMs", "maxOutputBytes"}
        executable, module, endpoint, model, input_report, cache, rules = None, None, None, None, None, None, None
        response_format = "json_schema"
        rule_packs = None
        ownership, urls, max_requests, request_timeout, max_response, auth_headers = False, None, 16, 5000, 262144, ()
        if tool in {"bandit", "psscriptanalyzer", "trivy", "osv", "semgrep"}:
            allowed.add("executable")
            if not isinstance(config.get("executable"), str) or not Path(config["executable"]).is_absolute():
                raise ValueError("An absolute tool executable is required")
            executable = Path(config["executable"]).resolve(strict=True)
            if executable.is_relative_to(root) or not executable.is_file() or not os.access(executable, os.X_OK):
                raise ValueError("Tool executable must be installed outside the project")
        if tool == "psscriptanalyzer":
            allowed.add("modulePath")
            if not isinstance(config.get("modulePath"), str) or not Path(config["modulePath"]).is_absolute():
                raise ValueError("An absolute trusted PSScriptAnalyzer module is required")
            module = Path(config["modulePath"]).resolve(strict=True)
            if module.is_relative_to(root) or not module.exists():
                raise ValueError("Analyzer module must be installed outside the project")
        if tool == "local-ai":
            allowed.update({"endpoint", "model", "inputReportPath", "responseFormat"})
            response_format = config.get("responseFormat", "json_schema")
            if response_format not in ("json_schema", "json_object"):
                raise ValueError("Unsupported local model response format")
            endpoint, model = config.get("endpoint"), config.get("model")
            validate_local_endpoint(endpoint)
            if not isinstance(model, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:/@+-]{0,159}", model):
                raise ValueError("A local model name is required")
            if not isinstance(config.get("inputReportPath"), str) or not Path(config["inputReportPath"]).is_absolute():
                raise ValueError("An absolute reviewed finding report is required")
            input_report = Path(config["inputReportPath"]).resolve()
            if input_report.is_relative_to(root):
                raise ValueError("AI input report must be outside the project")
        if tool == "trivy":
            allowed.add("cachePath")
            if "cachePath" in config:
                if not isinstance(config["cachePath"], str) or not Path(config["cachePath"]).is_absolute():
                    raise ValueError("Trivy cache must use an absolute user path")
                cache = Path(config["cachePath"]).resolve()
                if cache.is_relative_to(root):
                    raise ValueError("Trivy cache must be outside the project")
        if tool == "semgrep":
            allowed.update({"rulesPath", "rulePacks"})
            if "rulePacks" in config:
                selected = config["rulePacks"]
                if (not isinstance(selected, list) or not 1 <= len(selected) <= len(RULE_PACKS)
                        or any(not isinstance(pack, str) or pack not in RULE_PACKS for pack in selected)
                        or len(set(selected)) != len(selected) or "rulesPath" in config):
                    raise ValueError("Choose known bundled rule packs or one external rule file")
                rule_packs = tuple(selected)
            if "rulesPath" in config:
                if not isinstance(config["rulesPath"], str) or not Path(config["rulesPath"]).is_absolute():
                    raise ValueError("Semgrep rules require an absolute trusted path")
                rules = Path(config["rulesPath"]).resolve(strict=True)
                if rules.is_relative_to(root) or not rules.is_file():
                    raise ValueError("Semgrep rules must be a file outside the project")
        if tool == "http-audit":
            allowed.update({"ownershipConfirmed", "targetUrls", "maxRequests", "requestTimeoutMs", "maxResponseBytes", "authHeadersEnv"})
            ownership = config.get("ownershipConfirmed")
            urls = config.get("targetUrls")
            max_requests = config.get("maxRequests", 16)
            request_timeout = config.get("requestTimeoutMs", 5000)
            max_response = config.get("maxResponseBytes", 262144)
            if (ownership is not True or not isinstance(urls, list) or not 1 <= len(urls) <= 32 or
                    len(set(url for url in urls if isinstance(url, str))) != len(urls) or
                    type(max_requests) is not int or not 1 <= max_requests <= 32 or len(urls) > max_requests or
                    type(request_timeout) is not int or not 100 <= request_timeout <= 10000 or
                    type(max_response) is not int or not 1024 <= max_response <= 1048576):
                raise ValueError("HTTP audit requires explicit ownership, unique targets and bounded budgets")
            for url in urls:
                if not isinstance(url, str) or not scope.allows(url) or not url.isascii():
                    raise ValueError("HTTP audit targets must stay inside the exact configured scope")
                try: ipaddress.ip_address(urlsplit(url).hostname or '')
                except ValueError: raise ValueError("HTTP audit requires numeric target addresses; DNS targets use external scanner adapters") from None
            selected_headers = config.get("authHeadersEnv", {})
            if not isinstance(selected_headers, dict) or len(selected_headers) > 8:
                raise ValueError("Invalid HTTP audit secret references")
            for name, reference in selected_headers.items():
                if (not isinstance(name, str) or not re.fullmatch(r"[A-Za-z][A-Za-z0-9-]{0,63}", name) or
                        name.lower() in {"host", "content-length", "transfer-encoding", "connection", "accept-encoding", "proxy-authorization"} or
                        not isinstance(reference, str) or not re.fullmatch(r"[A-Z_][A-Z0-9_]{0,127}", reference)):
                    raise ValueError("Invalid HTTP audit secret references")
            if len({name.lower() for name in selected_headers}) != len(selected_headers):
                raise ValueError("Duplicate HTTP audit authentication header")
            urls, auth_headers = tuple(urls), tuple(selected_headers.items())
        if set(config) - allowed:
            raise ValueError("Runner does not accept these fields")
        timeout, maximum = config.get("timeoutMs", 120_000 if tool in {"trivy", "osv"} else 30_000), config.get("maxOutputBytes", MAX_REPORT_BYTES)
        if type(timeout) is not int or not 100 <= timeout <= 300_000:
            raise ValueError("Invalid tool timeout")
        if type(maximum) is not int or not 1 <= maximum <= MAX_REPORT_BYTES:
            raise ValueError("Invalid tool output limit")
        runner = RunnerConfig(tool, executable, timeout, maximum, module, endpoint, model, input_report, cache, rules, response_format, rule_packs,
                              ownership, urls, max_requests, request_timeout, max_response, auth_headers)
    provenance = coverage = None
    if "reportProvenance" in value:
        selected = _keys(value["reportProvenance"], {"manifestPath", "maxAgeSeconds"}, {"manifestPath"})
        if report is None or not isinstance(selected["manifestPath"], str) or not Path(selected["manifestPath"]).is_absolute():
            raise ValueError("Report provenance requires an absolute user manifest and report import")
        manifest = Path(selected["manifestPath"]).resolve(strict=True)
        age = selected.get("maxAgeSeconds", 86400)
        if manifest.is_relative_to(root) or not manifest.is_file() or type(age) is not int or not 1 <= age <= 604800:
            raise ValueError("Invalid report provenance configuration")
        provenance = ReportProvenance(manifest, age)
    if "dependencyCoverage" in value:
        selected = _keys(value["dependencyCoverage"], {"requireResolvedVersions"})
        required = selected.get("requireResolvedVersions", False)
        if kind != "SCA" or type(required) is not bool:
            raise ValueError("Dependency coverage requires SCA and an explicit boolean")
        coverage = DependencyCoverage(required)
    return IntegrationProfile(value["id"], display_name, version, kind, format_name, report, runner,
                              tuple(operations), tuple(actions), scope, binding, provenance, coverage)


def validate_local_endpoint(endpoint: object) -> str:
    """Only literal loopback addresses; credentials, proxies and remote redirects are unsupported."""
    if not isinstance(endpoint, str) or len(endpoint) > 512:
        raise ValueError("A local model endpoint is required")
    parsed = urlsplit(endpoint)
    if "%" in (parsed.hostname or ""):
        raise ValueError("IPv6 zone identifiers are not supported")
    try:
        address = ipaddress.ip_address(parsed.hostname or "")
        port = parsed.port
    except ValueError:
        raise ValueError("The model endpoint must use a numeric loopback address") from None
    if (not address.is_loopback or parsed.scheme != "http" or parsed.username is not None
            or parsed.password is not None or parsed.query or parsed.fragment
            or parsed.path.rstrip("/") != "/v1" or port is None or not 1 <= port <= 65535
            or any(c.isspace() or ord(c) < 32 for c in endpoint)):
        raise ValueError("Only an explicit local HTTP /v1 model endpoint is supported")
    return endpoint.rstrip("/")
