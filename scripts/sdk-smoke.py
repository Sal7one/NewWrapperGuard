#!/usr/bin/env python3
"""Exercise the installed Python SDK through the installed JVM command-provider host.

Reports are synthetic protocol fixtures. This does not run Bandit or any other scanner,
execute project sources, or contact a DAST target. Install the SDK into a trusted Python
environment first; --sdk-python selects that environment without importing checkout code.
"""
import argparse
import copy
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile


REPOSITORY = Path(__file__).resolve().parents[1]
DEFAULT_CLI = REPOSITORY / "wrapperguard-cli/build/install/wrapperguard-cli/bin" / (
    "wrapperguard-cli.bat" if os.name == "nt" else "wrapperguard-cli"
)


def write_json(path, value):
    path.write_text(json.dumps(value), encoding="utf-8")
    return path


def run(arguments, *, timeout=45):
    return subprocess.run(arguments, text=True, capture_output=True, timeout=timeout)


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk-python", type=Path, default=Path(sys.executable),
                        help="Absolute interpreter from an environment with wrapperguard-sdk installed")
    parser.add_argument("--cli", type=Path, default=DEFAULT_CLI,
                        help="Installed WrapperGuard JVM launcher; build installDist first")
    options = parser.parse_args()
    check(options.sdk_python.is_absolute() and options.sdk_python.is_file(), "Use an absolute --sdk-python executable")
    check(options.cli.is_absolute() and options.cli.is_file(), "Build :wrapperguard-cli:installDist first")
    sdk_python = str(options.sdk_python)
    installed = run([sdk_python, "-I", "-c",
                     "import sys, importlib.metadata; assert sys.version_info >= (3, 10); "
                     "print(importlib.metadata.version('wrapperguard-sdk'))"])
    check(installed.returncode == 0 and installed.stdout.strip() == "0.1.0",
          "Install wrapperguard-sdk 0.1.0 into the selected Python 3.10+ environment first")
    console = options.sdk_python.parent / ("wrapperguard-sdk.exe" if os.name == "nt" else "wrapperguard-sdk")
    check(console.is_file(), "Installed wrapperguard-sdk console entry point is missing")
    check(run([str(console), "--help"]).returncode == 0, "Installed SDK console entry point failed")

    with tempfile.TemporaryDirectory(prefix="wg-sdk-host-smoke-") as temporary:
        user = Path(temporary).resolve()
        project = user / "project"
        source = project / "src/app.py"
        source.parent.mkdir(parents=True)
        source.write_text("# Synthetic scanner-input fixture; never executed.\nprint('fixture')\n", encoding="utf-8")
        (project / "settings.gradle").write_text("rootProject.name = 'sdk-protocol-fixture'\n", encoding="utf-8")
        # The host intentionally uses isolated installed-module invocation, even in an
        # untrusted cwd containing a same-named Python module.
        (project / "wrapperguard_sdk.py").write_text(
            "raise RuntimeError('Project-controlled SDK module must not be imported')\n", encoding="utf-8")

        sarif = {
            "version": "2.1.0", "runs": [{
                "tool": {"driver": {"name": "Synthetic SARIF fixture"}},
                "results": [{"ruleId": "FIXTURE-SAST-001", "level": "error",
                             "message": {"text": "Synthetic SARIF issue; no scanner was run."},
                             "locations": [{"physicalLocation": {
                                 "artifactLocation": {"uri": "src/app.py"},
                                 "region": {"startLine": 2}}}]}]}]
        }
        bandit = {
            "errors": [], "metrics": {"src/app.py": {"loc": 2}},
            "results": [{"test_id": "B999", "test_name": "synthetic_fixture",
                         "issue_text": "Synthetic Bandit issue; Bandit was not run.",
                         "issue_severity": "HIGH", "issue_confidence": "HIGH",
                         "filename": "src/app.py", "line_number": 2}]
        }
        sarif_path = write_json(user / "synthetic.sarif", sarif)
        bandit_path = write_json(user / "synthetic-bandit.json", bandit)

        def profile(identifier, format_name, report, **extra):
            return write_json(user / (identifier + ".json"), {
                "id": identifier, "displayName": "Synthetic " + identifier,
                "version": "0.1.0-fixture", "analysisKind": "SAST",
                "format": format_name, "reportPath": str(report),
                "allowedOperations": ["SCAN"],
                "requestedActions": ["WARNING", "BLOCK_BUILD", "BLOCK_SYNC"], **extra,
            })

        bandit_profile = profile("fixture.bandit", "bandit", bandit_path)
        sarif_profile = profile("fixture.sarif", "sarif", sarif_path)

        def registration(identifier, sdk_profile):
            return {
                "id": identifier, "displayName": "Synthetic " + identifier,
                "version": "0.1.0-fixture",
                "command": [sdk_python, "-I", "-m", "wrapperguard_sdk", "--profile", str(sdk_profile)],
                "permissions": {"enabled": True, "actions": ["WARNING"],
                                "required": False, "timeoutMs": 10000},
            }

        def invoke(registrations, *, operation="SCAN", expected=0, configuration=None):
            config = configuration or user / "host-providers.json"
            write_json(config, {"apiVersion": 1, "builtIn": {"enabled": False}, "providers": registrations})
            result = run([str(options.cli), str(project), "--providers", str(config),
                          "--operation", operation.lower(), "--enforce", "--json"])
            check(result.returncode == expected,
                  "Host exit %s, expected %s for %s: %s" % (result.returncode, expected, operation, result.stderr[:1000]))
            return json.loads(result.stdout) if result.stdout.strip() else None

        registrations = [registration("fixture.bandit", bandit_profile), registration("fixture.sarif", sarif_profile)]
        plan = run([sdk_python, "-I", "-m", "wrapperguard_sdk", "--profile", str(bandit_profile),
                    "--plan", "--project", str(project)])
        check(plan.returncode == 0 and isinstance(json.loads(plan.stdout), dict), "SDK review plan failed")
        report = invoke(registrations)
        check(report["complete"] and len(report["findings"]) == 2, "Both report providers must contribute findings")
        check({f["providerId"] for f in report["findings"]} == {"fixture.bandit", "fixture.sarif"}, "Provider identity was lost")
        check(all(f["policyAction"] == "REVIEW" and f["actions"] == ["WARNING"] for f in report["findings"]),
              "Host WARNING grants must cap profiles that request blocking")
        check([r["status"] for r in report["providerRuns"]] == ["DISABLED", "COMPLETED", "COMPLETED"],
              "Both providers must finish within one host evaluation")
        check(len({f["fingerprint"] for f in report["findings"]}) == 2, "Host must preserve provider-scoped fingerprints")

        for operation in ("BUILD", "SYNC"):
            denied = invoke(registrations, operation=operation, expected=1)
            check(not denied["complete"] and denied["verdict"] == "INCOMPLETE", "Disallowed operations must not look clean")
            check(all(r["status"] == "FAILED" for r in denied["providerRuns"][1:]), "SDK must reject disallowed host operations")
            check(not denied["findings"], "Warning-only optional failures must not invent blockers")

        malformed_path = user / "malformed.json"
        malformed_path.write_text("{not valid JSON", encoding="utf-8")
        bad_profile = profile("fixture.malformed", "bandit", malformed_path)
        malformed = invoke([registration("fixture.malformed", bad_profile)], expected=1)
        check(not malformed["complete"] and malformed["providerRuns"][1]["status"] == "FAILED", "Malformed reports must fail visibly")

        inside_profile = project / "untrusted-sdk-profile.json"
        inside_profile.write_text(bandit_profile.read_text(encoding="utf-8"), encoding="utf-8")
        inside = invoke([registration("fixture.inside", inside_profile)], expected=1)
        check(not inside["complete"] and inside["providerRuns"][1]["status"] == "FAILED", "SDK profiles inside the project must be rejected")
        check(invoke(registrations, expected=1, configuration=project / "untrusted-host-providers.json") is None,
              "Host configuration inside the project must be rejected before provider execution")

        traversal = copy.deepcopy(sarif)
        outside_issue = copy.deepcopy(traversal["runs"][0]["results"][0])
        outside_issue["locations"][0]["physicalLocation"]["artifactLocation"]["uri"] = "../../OUTSIDE_FINDING_SENTINEL.py"
        traversal["runs"][0]["results"].append(outside_issue)
        traversal_path = write_json(user / "traversal.sarif", traversal)
        traversal_profile = profile("fixture.traversal", "sarif", traversal_path)
        rejected = invoke([registration("fixture.traversal", traversal_profile)], expected=1)
        check(not rejected["complete"] and len(rejected["findings"]) == 1 and rejected["notices"],
              "Outside findings must be dropped with visible incomplete coverage")
        check(rejected["findings"][0]["location"]["path"] == "src/app.py", "Retain the valid in-project finding")
        check("OUTSIDE_FINDING_SENTINEL" not in json.dumps(rejected), "Outside source paths must not leak in notices")

        def zap_report(uri):
            return {"@version": "2.15.0", "site": [{"@name": "https://owned.example.invalid",
                    "@host": "owned.example.invalid", "@port": "443", "@ssl": "true",
                    "alerts": [{"pluginid": "10020", "alert": "Synthetic ZAP fixture",
                                "riskcode": "2", "confidence": "2", "desc": "Report import fixture; no target was contacted.",
                                "solution": "Review the synthetic fixture.", "instances": [{"uri": uri, "method": "GET"}]}]}]}

        zap_path = write_json(user / "synthetic-zap.json", zap_report("https://owned.example.invalid/app/profile"))
        zap_profile = profile("fixture.zap", "zap", zap_path, analysisKind="DAST",
                              targetScope={"origins": ["https://owned.example.invalid"], "pathPrefixes": ["/app"]})
        allowed_zap = invoke([registration("fixture.zap", zap_profile)])
        check(allowed_zap["complete"] and len(allowed_zap["findings"]) == 1, "In-scope DAST report import failed")
        check(allowed_zap["findings"][0]["location"]["path"] == ".", "DAST findings use target-level project locations")
        for uri in ("https://outside.example.invalid/app/profile", "https://owned.example.invalid/application/profile"):
            write_json(zap_path, zap_report(uri))
            denied_zap = invoke([registration("fixture.zap", zap_profile)], expected=1)
            check(not denied_zap["complete"] and not denied_zap["findings"], "Out-of-scope DAST findings must be rejected")

    print("SDK host smoke passed: installed console/module, review plan, two fixture report providers, warning grants, operation denial, malformed reports, external configuration, source traversal, and DAST scope. No scanners or targets were run.")


if __name__ == "__main__":
    main()
