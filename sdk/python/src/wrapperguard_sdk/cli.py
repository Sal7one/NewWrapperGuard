"""Review-only plans and the newline-delimited WrapperGuard provider API 1 bridge."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import subprocess
import sys

from . import __version__
from .models import MAX_FINDINGS, redact_text
from .profile import (MAX_OUTPUT_BYTES, MAX_PROFILE_BYTES, MAX_REPORT_BYTES, OPERATIONS,
                      FORMATS, IntegrationProfile, bounded_read, load_json_document, load_profile)
from .rule_packs import RULE_PACKS
from .runner import (EXCLUDED_DIRECTORIES, MAX_SOURCE_BYTES, MAX_SOURCE_DEPTH,
                     MAX_SOURCE_FILE_BYTES, MAX_SOURCE_FILES, run_tool, runner_plan, runner_supported)


class _SafeParser(argparse.ArgumentParser):
    def error(self, message: str) -> None:
        raise ValueError("Invalid CLI arguments")


def build_plan(profile: IntegrationProfile, project_root: Path, operation: str = "SCAN") -> dict:
    if profile.project_root is not None and profile.project_root.resolve(strict=True) != project_root.resolve(strict=True):
        raise ValueError("This integration profile belongs to another project")
    if operation not in profile.allowed_operations:
        raise ValueError("Operation is not allowed by the user profile")
    active = profile.runner is not None
    tool = profile.runner.tool if profile.runner else None
    process = active and tool not in {"script-semantics", "local-ai", "http-audit"}
    plan = {"sdkVersion": __version__, "providerApiVersion": 1,
            "profile": {"id": profile.id, "displayName": profile.display_name, "version": profile.version},
            "analysisKind": profile.analysis_kind, "format": profile.format,
            "mode": tool + "-runner" if active else "report-import",
            "projectPath": str(project_root), "operation": operation,
            "allowedOperations": list(profile.allowed_operations),
            "requestedActions": list(profile.requested_actions),
            "authority": "Requested actions are intersected with host user grants; profiles do not enable providers.",
            "inputAccess": "An existing JSON report only; report contents are untrusted data.",
            "executesProjectCode": False, "startsToolProcess": process,
            "network": "The SDK performs no network requests and starts no active DAST scanner.",
            "isolation": "Host permissions govern findings and actions, not OS filesystem or network access.",
            "limits": {"profileBytes": MAX_PROFILE_BYTES, "reportBytes": MAX_REPORT_BYTES,
                       "providerOutputBytes": MAX_OUTPUT_BYTES}}
    if profile.report_path is not None:
        plan["reportPath"] = redact_text(str(profile.report_path))
        plan["reportFreshness"] = "Report import does not verify scanner generation time or source revision."
        if profile.report_provenance is not None:
            plan["reportProvenance"] = {"manifestPath": redact_text(str(profile.report_provenance.manifest_path)),
                "maxAgeSeconds": profile.report_provenance.max_age_seconds,
                "guarantee": "Report digest, age and explicitly listed input digests only; no producer authentication or whole-project coverage."}
    if profile.dependency_coverage is not None:
        plan["dependencyCoverage"] = {"requireResolvedVersions": profile.dependency_coverage.require_resolved_versions,
                                     "executesResolver": False}
    plan["projectBinding"] = "explicit" if profile.project_root is not None else "unbound"
    if profile.target_scope is not None:
        plan["targetScope"] = profile.target_scope.to_dict()
        plan["inputAccess"] = "Existing passive ZAP JSON report; only findings matching explicit target scope are imported."
    if tool == "http-audit":
        plan.update({"targetUrls": [redact_text(url) for url in profile.runner.target_urls],
            "ownershipConfirmed": profile.runner.ownership_confirmed, "followsRedirects": False,
            "startsTarget": False, "authHeaderReferences": {name: variable for name, variable in profile.runner.auth_headers_env}})
        plan["limits"].update({"requests": profile.runner.max_requests, "requestTimeoutMs": profile.runner.request_timeout_ms,
                               "responseBytes": profile.runner.max_response_bytes, "responseHeaderBytes": 32768})
    if profile.runner is not None:
        if profile.runner.executable:
            plan["executable"] = redact_text(str(profile.runner.executable))
        if profile.runner.module_path:
            plan["modulePath"] = redact_text(str(profile.runner.module_path))
        if tool == "local-ai":
            plan.update({"endpoint": profile.runner.endpoint, "model": profile.runner.model,
                         "inputReportPath": redact_text(str(profile.runner.input_report_path)), "advisoryOnly": True,
                         "responseFormat": profile.runner.response_format})
        if tool == "script-semantics":
            plan["advisoryOnly"] = True
        if tool == "semgrep" and profile.runner.rules_path is None:
            plan["rulePacks"] = list(profile.runner.rule_packs or ("legacy-android-ktor",))
        plan.update(runner_plan(tool))
        plan["isolation"] = ("Snapshot input and sanitized environment limit accidental repository influence; "
                             "installed scanners and their plugins remain trusted code with OS user privileges. "
                             "Windows fixed tools use an atomic kill-on-close Job Object; POSIX tools inherit the host process group for cancellation.")
        plan["runnerSupported"] = runner_supported(tool)
        if tool == "http-audit":
            plan["isolation"] = "Owned target GET requests are scope checked, use no proxies or redirects and are budgeted; no target or project process is started."
        plan["limits"].update({"timeoutMs": profile.runner.timeout_ms,
                               "toolOutputBytes": profile.runner.max_output_bytes,
                               "sourceFiles": MAX_SOURCE_FILES, "sourceFileBytes": MAX_SOURCE_FILE_BYTES,
                               "sourceBytes": MAX_SOURCE_BYTES, "sourceDepth": MAX_SOURCE_DEPTH})
        plan["excludedDirectories"] = sorted(EXCLUDED_DIRECTORIES)
    return plan


def _request() -> tuple[Path, str]:
    raw = sys.stdin.buffer.readline(MAX_PROFILE_BYTES + 1)
    if len(raw) > MAX_PROFILE_BYTES:
        raise ValueError("Request is too large")
    request = load_json_document(raw)
    if (not isinstance(request, dict) or set(request) != {"apiVersion", "projectPath", "operation"}
            or type(request["apiVersion"]) is not int or request["apiVersion"] != 1
            or not isinstance(request["projectPath"], str)
            or not isinstance(request["operation"], str) or request["operation"] not in OPERATIONS):
        raise ValueError("Invalid provider request")
    root = Path(request["projectPath"])
    if not root.is_absolute() or not root.is_dir():
        raise ValueError("An absolute project directory is required")
    return root.resolve(strict=True), request["operation"]


def evaluate(profile: IntegrationProfile, project_root: Path, operation: str) -> dict:
    # Review and operation validation happen before report access or process creation.
    build_plan(profile, project_root, operation)
    notices: list[dict] = []
    provenance_notices: list[dict] = []
    if profile.report_path is not None:
        report = profile.report_path.resolve(strict=True)
        if not report.is_file():
            raise ValueError("A regular report file is required")
        raw = bounded_read(report, MAX_REPORT_BYTES)
        if profile.report_provenance is not None:
            from .provenance import verify_manifest
            provenance_notices = verify_manifest(profile.report_provenance, raw, project_root)
        else:
            provenance_notices = [{"code": "WG-REPORT-PROVENANCE-UNKNOWN", "message":
                "Imported report provenance is unverified: producer, generation time, database freshness and source revision are unknown. Report import does not prove current or whole-project coverage."}]
        document = load_json_document(raw)
    else:
        if profile.runner.tool == "local-ai":
            from .ai_review import run_ai_review
            return run_ai_review(profile, project_root)
        if profile.runner.tool == "http-audit":
            from .http_audit import run_http_audit
            return run_http_audit(profile, project_root)
        document, notices = run_tool(profile, project_root)
    if profile.format == "script-semantics":
        result = document
    else:
        from .adapters import parse_report
        result = parse_report(profile.format, document, project_root, list(profile.requested_actions), profile.target_scope)
    if not isinstance(result, dict) or not isinstance(result.get("findings"), list) or len(result["findings"]) > MAX_FINDINGS:
        raise ValueError("Invalid adapter output")
    if notices:
        result["notices"] = list(result.get("notices", [])) + notices
        result["complete"] = False
    if provenance_notices:
        result["notices"] = list(result.get("notices", [])) + provenance_notices
    if profile.dependency_coverage is not None:
        from .dependency_coverage import summarize
        coverage_notices, incomplete = summarize(profile.format, document,
            require_resolved=profile.dependency_coverage.require_resolved_versions)
        result["notices"] = list(result.get("notices", [])) + coverage_notices
        if incomplete: result["complete"] = False
    return result


def main(argv: list[str] | None = None) -> int:
    try:
        parser = _SafeParser(description="Explicit WrapperGuard report adapters; provider API 1 on stdin/stdout.")
        parser.add_argument("--version", action="version", version="%(prog)s " + __version__)
        parser.add_argument("--profile", help="Absolute user-owned profile outside the project")
        parser.add_argument("--capabilities", action="store_true", help="Describe formats and bundled rule packs without scanning or connecting a tool")
        parser.add_argument("--plan", "--dry-run", action="store_true", help="Print review JSON without reading reports or executing tools")
        parser.add_argument("--project", type=Path, help="Absolute project directory for --plan")
        parser.add_argument("--operation", choices=sorted(OPERATIONS), default="SCAN", help="Operation reviewed by --plan")
        args = parser.parse_args(argv)
        if args.capabilities:
            if args.profile or args.plan or args.project or args.operation != "SCAN":
                raise ValueError("Capability discovery does not use a project or profile")
            print(json.dumps({"sdkVersion": __version__, "providerApiVersion": 1,
                              "formats": sorted(FORMATS), "rulePacks": [
                                  {"id": key, "title": value['title'], "extensions": value['extensions'], "fileNames": value['fileNames']}
                                  for key, value in RULE_PACKS.items()],
                              "runners": [{"tool": tool, "supported": runner_supported(tool)} for tool in
                                  ("bandit", "semgrep", "psscriptanalyzer", "trivy", "osv", "script-semantics", "local-ai", "http-audit")],
                              "executesProjectCode": False, "installsTools": False}, separators=(',', ':')))
            return 0
        if not args.profile:
            raise ValueError("An explicit user profile is required")
        if args.plan:
            if args.project is None or not args.project.is_absolute() or not args.project.is_dir():
                raise ValueError("Plan requires an absolute project directory")
            root, operation = args.project.resolve(strict=True), args.operation
        else:
            if args.project is not None or args.operation != "SCAN":
                raise ValueError("Protocol requests supply their own project and operation")
            root, operation = _request()
        profile = load_profile(Path(args.profile), root)
        output = build_plan(profile, root, operation) if args.plan else evaluate(profile, root, operation)
        encoded = json.dumps(output, ensure_ascii=True, separators=(",", ":"), allow_nan=False).encode("utf-8")
        if len(encoded) + 1 > MAX_OUTPUT_BYTES:
            raise ValueError("Provider result is too large")
        sys.stdout.buffer.write(encoded + b"\n")
        sys.stdout.buffer.flush()
        return 0
    except (ValueError, TypeError, KeyError, OSError, RuntimeError, UnicodeError, RecursionError,
            TimeoutError, subprocess.SubprocessError):
        print("WrapperGuard integration failed; review the user profile, operation and report.", file=sys.stderr)
        return 1
