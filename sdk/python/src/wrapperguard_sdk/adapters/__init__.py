"""Offline importers for explicitly selected reports, never scanner launchers.

Source contracts: OASIS SARIF 2.1.0; Bandit's JSON formatter; Microsoft
PSScriptAnalyzer DiagnosticRecord; Trivy SchemaVersion 2; OSV-Scanner's
pkg/models/results.go; ZAP's traditional JSON report. Unknown data is not clean.
"""
from __future__ import annotations

from html.parser import HTMLParser
from pathlib import Path
import re
from urllib.parse import urlsplit, urlunsplit

from ..models import make_finding, redact_text, MAX_FINDINGS, validate_actions
from ..paths import normalize_location
from ..profile import TargetScope

_URL = re.compile(r"https?://[^\s<>\"']+", re.IGNORECASE)


def safe_url(value: str) -> str:
    """Display origin and path only: never userinfo, query values or fragments."""
    try:
        u = urlsplit(value)
        if u.scheme.lower() not in {"http", "https"} or not u.hostname:
            return "[invalid target URL]"
        host = u.hostname
        if ":" in host:
            host = "[" + host + "]"
        port = u.port
        netloc = host + (":" + str(port) if port else "")
        return redact_text(urlunsplit((u.scheme.lower(), netloc, u.path or "/", "", "")))
    except ValueError:
        return "[invalid target URL]"


def text(value: object, default: str = "") -> str:
    if not isinstance(value, str):
        return default
    value = value[:16384]
    value = _URL.sub(lambda m: safe_url(m.group(0)), value)
    return redact_text(value)


class _PlainText(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.parts: list[str] = []
        self.hidden = 0

    def handle_starttag(self, tag: str, attrs: list) -> None:
        if tag in {"script", "style"}:
            self.hidden += 1
        elif tag in {"p", "br", "div", "li"}:
            self.parts.append(" ")

    def handle_endtag(self, tag: str) -> None:
        if tag in {"script", "style"} and self.hidden:
            self.hidden -= 1
        elif tag in {"p", "div", "li"}:
            self.parts.append(" ")

    def handle_data(self, data: str) -> None:
        if not self.hidden:
            self.parts.append(data)


def plain_html(value: object) -> str:
    if not isinstance(value, str):
        return ""
    parser = _PlainText()
    parser.feed(value[:16384])
    parser.close()
    return text(" ".join("".join(parser.parts).split()))


def required_object(value: object, label: str) -> dict:
    if not isinstance(value, dict):
        raise ValueError(f"Invalid {label} report: expected an object.")
    return value


def required_list(value: object, label: str) -> list:
    if not isinstance(value, list):
        raise ValueError(f"Invalid {label} report: expected an array.")
    return value


class ImportContext:
    def __init__(self, project_root: Path, actions: list[str]) -> None:
        self.root = Path(project_root)
        self.actions = list(actions)
        self.findings: list[dict] = []
        self.paths: set[str] = set()
        self.notices: list[dict] = []
        self.complete = True

    def problem(self, code: str = "WG-REPORT-PARTIAL", message: str = "Some report records could not be imported.") -> None:
        self.complete = False
        notice = {"code": code, "message": message}
        if notice not in self.notices:
            self.notices.append(notice)

    def location(self, raw_path: object) -> str | None:
        if not isinstance(raw_path, str) or not raw_path.strip():
            self.problem("WG-REPORT-LOCATION", "A report location is missing, invalid or outside the project.")
            return None
        try:
            path = normalize_location(self.root, raw_path)
        except (ValueError, OSError):
            path = None
        if path is None:
            self.problem("WG-REPORT-LOCATION", "A report location is missing, invalid or outside the project.")
        else:
            if path != ".":
                self.paths.add(path)
        return path

    def position(self, value: object) -> int | None:
        if value is None:
            return None
        if isinstance(value, bool) or not isinstance(value, int) or not 1 <= value <= 2_147_483_647:
            self.problem("WG-REPORT-REGION", "An invalid source position was omitted.")
            return None
        return value

    def severity(self, value: object, mapping: dict, default: str = "INFO") -> str:
        # bool is an integer in Python; never interpret it as an external enum.
        if isinstance(value, bool) or not isinstance(value, (str, int)) or value not in mapping:
            self.problem("WG-REPORT-SEVERITY", "An unrecognized severity was imported without blocking authority.")
            return default
        return mapping[value]

    def add(self, *, rule_id: str, title: str, message: str, severity: str,
            path: str, confidence: str = "MEDIUM", category: str = "CONFIGURATION",
            line: object = None, column: object = None, remediation: str | None = None) -> None:
        if not rule_id or not title or not message:
            self.problem()
            return
        if len(self.findings) >= MAX_FINDINGS:
            self.problem("WG-REPORT-LIMIT", "Report finding limit reached; remaining records were omitted.")
            return
        try:
            finding = make_finding(
                rule_id=text(rule_id), title=text(title), message=text(message),
                severity=severity, confidence=confidence, category=category, path=path,
                line=self.position(line), column=self.position(column),
                # Import metadata only. No source snippets, matched secrets or HTTP bodies.
                actions=[] if severity == "INFO" else self.actions,
                remediation=text(remediation) if remediation else None,
            )
        except ValueError:
            self.problem()
            return
        self.findings.append(finding)

    def result(self) -> dict:
        return {"findings": self.findings, "scannedFiles": len(self.paths),
                "complete": self.complete, "notices": self.notices}


def parse_report(format: str, document: dict | list, project_root: Path,
                 requested_actions: list[str], target_scope: TargetScope | None = None) -> dict:
    """Translate a known report into an API 1 ProviderResult.

    Invalid envelopes raise ValueError. Skipped/unsupported records produce an
    incomplete result with generic notices; they cannot masquerade as clean scans.
    Actions are requests only; final authority always belongs to the IDE/CLI host.
    """
    requested_actions = validate_actions(requested_actions)
    from . import bandit, osv, psscriptanalyzer, sarif, trivy, zap
    parsers = {"sarif": sarif.parse, "bandit": bandit.parse, "psscriptanalyzer": psscriptanalyzer.parse,
               "trivy": trivy.parse, "osv": osv.parse, "zap": zap.parse}
    if not isinstance(format, str) or format not in parsers:
        raise ValueError("Unsupported report format.")
    ctx = ImportContext(project_root, requested_actions)
    parsers[format](document, ctx, target_scope)
    return ctx.result()


__all__ = ["parse_report"]
