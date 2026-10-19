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
                      IntegrationProfile, bounded_read, load_json_document, load_profile)
from .runner import (EXCLUDED_DIRECTORIES, MAX_SOURCE_BYTES, MAX_SOURCE_DEPTH,
                     MAX_SOURCE_FILE_BYTES, MAX_SOURCE_FILES, run_bandit)


class _SafeParser(argparse.ArgumentParser):
    def error(self, message: str) -> None:
        raise ValueError("Invalid CLI arguments")


def build_plan(profile: IntegrationProfile, project_root: Path, operation: str = "SCAN") -> dict:
    if operation not in profile.allowed_operations:
        raise ValueError("Operation is not allowed by the user profile")
    active = profile.runner is not None
    plan = {"sdkVersion": __version__, "providerApiVersion": 1,
            "profile": {"id": profile.id, "displayName": profile.display_name, "version": profile.version},
            "analysisKind": profile.analysis_kind, "format": profile.format,
            "mode": "bandit-static-runner" if active else "report-import",
            "projectPath": str(project_root), "operation": operation,
            "allowedOperations": list(profile.allowed_operations),
            "requestedActions": list(profile.requested_actions),
            "authority": "Requested actions are intersected with host user grants; profiles do not enable providers.",
            "inputAccess": "An existing JSON report only; report contents are untrusted data.",
            "executesProjectCode": False, "startsToolProcess": active,
            "network": "The SDK performs no network requests and starts no active DAST scanner.",
            "isolation": "Host permissions govern findings and actions, not OS filesystem or network access.",
            "limits": {"profileBytes": MAX_PROFILE_BYTES, "reportBytes": MAX_REPORT_BYTES,
                       "providerOutputBytes": MAX_OUTPUT_BYTES}}
    if profile.report_path is not None:
        plan["reportPath"] = redact_text(str(profile.report_path))
    if profile.target_scope is not None:
        plan["targetScope"] = profile.target_scope.to_dict()
        plan["inputAccess"] = "Existing passive ZAP JSON report; only findings matching explicit target scope are imported."
    if profile.runner is not None:
        plan["executable"] = redact_text(str(profile.runner.executable))
        plan["fixedArguments"] = ["--ini", "<owned-empty-ini>", "--configfile", "<owned-empty-yaml>",
                                  "--ignore-nosec", "--exit-zero", "-q", "-f", "json", "-r", "."]
        plan["inputAccess"] = "Only regular .py source data copied into a temporary snapshot; symlinks are skipped."
        plan["isolation"] = ("Snapshot input and sanitized environment limit accidental repository influence; "
                             "the installed Bandit executable and its installed plugins remain trusted code with OS user privileges. "
                             "The runner inherits the host process group for cancellation and terminates its known child on local failures.")
        plan["network"] = "No network access is requested; this is not an OS network sandbox for installed tool code."
        plan["runnerSupported"] = os.name == "posix" and hasattr(os, "O_NOFOLLOW")
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
    if profile.report_path is not None:
        report = profile.report_path.resolve(strict=True)
        if not report.is_file():
            raise ValueError("A regular report file is required")
        document = load_json_document(bounded_read(report, MAX_REPORT_BYTES))
    else:
        document, notices = run_bandit(profile, project_root)
    from .adapters import parse_report
    result = parse_report(profile.format, document, project_root, list(profile.requested_actions), profile.target_scope)
    if not isinstance(result, dict) or not isinstance(result.get("findings"), list) or len(result["findings"]) > MAX_FINDINGS:
        raise ValueError("Invalid adapter output")
    if notices:
        result["notices"] = list(result.get("notices", [])) + notices
        result["complete"] = False
    return result


def main(argv: list[str] | None = None) -> int:
    try:
        parser = _SafeParser(description="Explicit WrapperGuard report adapters; provider API 1 on stdin/stdout.")
        parser.add_argument("--version", action="version", version="%(prog)s " + __version__)
        parser.add_argument("--profile", required=True, help="Absolute user-owned profile outside the project")
        parser.add_argument("--plan", "--dry-run", action="store_true", help="Print review JSON without reading reports or executing tools")
        parser.add_argument("--project", type=Path, help="Absolute project directory for --plan")
        parser.add_argument("--operation", choices=sorted(OPERATIONS), default="SCAN", help="Operation reviewed by --plan")
        args = parser.parse_args(argv)
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
