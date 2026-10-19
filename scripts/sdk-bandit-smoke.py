#!/usr/bin/env python3
"""Run an installed Bandit on inert, generated Python sources through the SDK.

Unlike sdk-smoke.py's report protocol fixtures, this invokes the actual scanner.
It neither executes the generated project nor sends network traffic.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk-python", required=True, type=Path)
    parser.add_argument("--bandit", required=True, type=Path)
    options = parser.parse_args()
    if os.name != "posix":
        parser.error("The initial SDK tool runner requires POSIX; report imports are cross-platform.")
    if not options.sdk_python.is_absolute() or not options.bandit.is_absolute():
        parser.error("Select absolute installed Python and Bandit executables.")
    with tempfile.TemporaryDirectory(prefix="wg-real-bandit-") as temporary:
        user = Path(temporary).resolve()
        project = user / "project"
        project.mkdir()
        marker = user / "PROJECT_CODE_MUST_NOT_EXECUTE"
        poison = "from pathlib import Path\nPath(" + repr(str(marker)) + ").write_text('executed')\n"
        (project / "sitecustomize.py").write_text(poison, encoding="utf-8")
        (project / "app.py").write_text(
            poison + "import subprocess\nsubprocess.run('echo inert-scanner-fixture', shell=True)  # nosec\n",
            encoding="utf-8")
        # User-profile settings, rather than project suppression files, govern this scan.
        (project / ".bandit").write_text("[bandit]\nskips = B602,B607\n", encoding="utf-8")
        (project / "pyproject.toml").write_text("[tool.bandit]\nskips = ['B602', 'B607']\n", encoding="utf-8")
        profile = user / "bandit-profile.json"
        profile.write_text(json.dumps({
            "id": "bandit.live", "displayName": "Bandit static analysis", "version": "1.9.4",
            "analysisKind": "SAST", "format": "bandit",
            "runner": {"tool": "bandit", "executable": str(options.bandit), "timeoutMs": 30000},
            "allowedOperations": ["SCAN"], "requestedActions": ["WARNING"],
        }), encoding="utf-8")
        command = [str(options.sdk_python), "-I", "-m", "wrapperguard_sdk", "--profile", str(profile)]
        environment = dict(os.environ, PYTHONPATH=str(project), PYTHONSTARTUP=str(project / "sitecustomize.py"))
        plan = subprocess.run(command + ["--plan", "--project", str(project)], cwd=project,
                              env=environment, capture_output=True, text=True, timeout=10)
        assert plan.returncode == 0, "Bandit plan failed: " + plan.stderr
        assert isinstance(json.loads(plan.stdout), dict)
        assert not marker.exists(), "Planning imported project code."
        request = json.dumps({"apiVersion": 1, "projectPath": str(project), "operation": "SCAN"}) + "\n"

        def scan():
            process = subprocess.run(command, input=request, cwd=project, env=environment,
                                     capture_output=True, text=True, timeout=40)
            assert process.returncode == 0, "Bandit runner failed: " + process.stderr
            return json.loads(process.stdout)

        result = scan()
        assert result["complete"], result.get("notices")
        assert any(f["ruleId"] == "B602" for f in result["findings"]), "Project suppression affected the SDK scan."
        assert all(f.get("actions") == ["WARNING"] for f in result["findings"])
        assert result["scannedFiles"] >= 2
        assert not marker.exists(), "Static analysis executed project code."
        for finding in result["findings"]:
            assert not Path(finding["location"]["path"]).is_absolute()
            assert ".." not in Path(finding["location"]["path"]).parts
        outside = user / "outside.py"
        outside.write_text("eval('OUTSIDE_FILE_MUST_NOT_BE_READ')\n", encoding="utf-8")
        (project / "linked.py").symlink_to(outside)
        partial = scan()
        assert not partial["complete"], "A skipped source symlink must remain visible as incomplete coverage."
        assert all(f["location"]["path"] != "linked.py" for f in partial["findings"])
        assert "OUTSIDE_FILE_MUST_NOT_BE_READ" not in json.dumps(partial)
        assert not marker.exists()
    print("Real Bandit / installed SDK: snapshot analysis, suppression isolation, project-code nonexecution, path mapping and symlink coverage passed.")


if __name__ == "__main__":
    main()
