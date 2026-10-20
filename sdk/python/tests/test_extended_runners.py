from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

from wrapperguard_sdk import cli
from wrapperguard_sdk.profile import load_profile
from wrapperguard_sdk.runner import run_tool


@unittest.skipUnless(os.name == "posix" and hasattr(os, "O_NOFOLLOW"), "Snapshot runners require POSIX")
class ExtendedRunnerTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="wg-extended-runner-")
        self.addCleanup(self.temporary.cleanup)
        self.user = Path(self.temporary.name).resolve()
        self.root = self.user / "untrusted project"
        self.root.mkdir()
        self.capture = self.user / "capture.json"
        self.profile_file = self.user / "profile.json"
        self.marker = self.user / "must-not-execute"
        self.executable = self.user / "installed scanner with spaces"
        self.module = self.user / "trusted-module.psd1"
        self.module.write_text("@{}", encoding="utf-8")
        for name, text in {
            "script.ps1": "Write-Output 'data only'", "module.psm1": "function hello {}", "data.psd1": "@{}",
            "app.py": f"from pathlib import Path;Path({str(self.marker)!r}).write_text('BAD')",
            "pom.xml": "<project><modelVersion>4.0.0</modelVersion></project>", "gradle.lockfile": "example:thing:1.0=runtimeClasspath",
            "requirements.txt": "example==1.0", "package-lock.json": '{"lockfileVersion":3}',
            "build.gradle.kts": f'file("{self.marker}").writeText("BAD")',
            ".trivyignore": "CVE-ignore-all", "trivy.yaml": "scan: {skip-dirs: ['.']}",
            "osv-scanner.toml": "[[IgnoredVulns]]\nid='ignore-everything'", "PSScriptAnalyzerSettings.psd1": "@{ExcludeRules='*'}",
            ".bandit": "[bandit]\nskips = B602", "sitecustomize.py": f"from pathlib import Path;Path({str(self.marker)!r}).touch()",
        }.items():
            (self.root / name).write_text(text, encoding="utf-8")
        (self.root / "build").mkdir()
        (self.root / "build" / "skip.ps1").write_text("ignored", encoding="utf-8")
        self.settings = {}

    def fake(self, tool, *, body=None, exit_code=0, **runner_options):
        script = f'''#!{sys.executable}
import json, os, pathlib, stat, sys
root=pathlib.Path.cwd()
files=sorted(str(p.relative_to(root)) for p in root.rglob('*') if p.is_file())
config={{}}
for i,arg in enumerate(sys.argv[:-1]):
 if arg in ('--config','--ignorefile','-File'):
  path=pathlib.Path(sys.argv[i+1]);config[arg]=path.read_text() if path.is_file() else None
pathlib.Path({str(self.capture)!r}).write_text(json.dumps({{'argv':sys.argv,'files':files,'env':dict(os.environ),'config':config,'cwd':str(root),'modes':{{name:stat.S_IMODE((root/name).stat().st_mode) for name in files}}}}))
'''
        if body is None:
            if tool == "psscriptanalyzer":
                body = "print(json.dumps([{'RuleName':'PSAvoidUsingWriteHost','Message':'Review output','Severity':'Warning','ScriptPath':str(root/'script.ps1'),'Extent':{'File':str(root/'script.ps1'),'StartLineNumber':1}}]))"
            elif tool == "trivy":
                body = "print(json.dumps({'SchemaVersion':2,'Results':[{'Target':str(root/'pom.xml'),'Class':'lang-pkgs','Type':'pom','Vulnerabilities':[{'VulnerabilityID':'CVE-2099-TEST','PkgName':'fixture','InstalledVersion':'1.0','Severity':'HIGH','Title':'Synthetic finding'}]}]}))"
            elif tool == "semgrep":
                body = "print(json.dumps({'version':'2.1.0','runs':[{'tool':{'driver':{'name':'fake-semgrep'}},'results':[{'ruleId':'wrapperguard.ktor.unrestricted-cors','level':'warning','message':{'text':'Review unrestricted origins'},'locations':[{'physicalLocation':{'artifactLocation':{'uri':(root/'Main.kt').as_uri()},'region':{'startLine':1}}}]}]}]}))"
            else:
                body = "print(json.dumps({'results':[{'source':{'path':str(root/'requirements.txt'),'type':'lockfile'},'packages':[{'package':{'name':'fixture','version':'1.0'},'vulnerabilities':[{'id':'PYSEC-2099-TEST','summary':'Synthetic finding','database_specific':{'severity':'HIGH'}}]}]}]}))"
        self.executable.write_text(script + body + f"\nsys.exit({exit_code})\n", encoding="utf-8")
        self.executable.chmod(0o700)
        config = {"tool": tool, "executable": str(self.executable), "timeoutMs": 1000, **runner_options}
        if tool == "psscriptanalyzer":
            config["modulePath"] = str(self.module)
        self.settings = {"id": "test." + tool, "displayName": "Test", "version": "1", "analysisKind": "SAST" if tool in {"psscriptanalyzer", "semgrep"} else "SCA",
                         "format": "sarif" if tool == "semgrep" else tool, "runner": config, "requestedActions": ["WARNING"]}
        self.save()
        return load_profile(self.profile_file, self.root)

    def save(self):
        self.profile_file.write_text(json.dumps(self.settings), encoding="utf-8")

    def captured(self):
        return json.loads(self.capture.read_text())

    def test_powershell_has_fixed_trusted_script_module_and_source_only_snapshot(self):
        profile = self.fake("psscriptanalyzer")
        result = cli.evaluate(profile, self.root, "SCAN")
        capture = self.captured()
        args = capture["argv"][1:]
        self.assertEqual(args[:3], ["-NoProfile", "-NonInteractive", "-File"])
        self.assertEqual(args[4], str(self.module))
        self.assertEqual(Path(args[5]).resolve(), Path(capture["cwd"]).resolve())
        self.assertEqual(set(capture["files"]), {"script.ps1", "module.psm1", "data.psd1"})
        script = capture["config"]["-File"]
        for required in ("Import-Module -Name $ModulePath", "Invoke-ScriptAnalyzer", "-IncludeDefaultRules", "-IncludeSuppressed", "-Settings @{}", "$PSModuleAutoLoadingPreference = 'None'"):
            self.assertIn(required, script)
        self.assertEqual(result["findings"][0]["location"]["path"], "script.ps1")
        self.assertTrue(result["complete"])
        self.assertFalse(self.marker.exists())

    def test_sca_tools_receive_manifest_only_snapshot_and_owned_empty_configs(self):
        for tool in ("trivy", "osv"):
            with self.subTest(tool=tool):
                profile = self.fake(tool, exit_code=1 if tool == "osv" else 0)
                with patch.dict(os.environ, {"TOKEN": "parent-secret", "PYTHONPATH": str(self.root), "PSModulePath": str(self.root), "HTTP_PROXY": "http://secret.example"}):
                    result = cli.evaluate(profile, self.root, "SCAN")
                capture = self.captured()
                self.assertEqual(set(capture["files"]), {"pom.xml", "gradle.lockfile", "requirements.txt", "package-lock.json"})
                for key in ("TOKEN", "PYTHONPATH", "PSModulePath", "HTTP_PROXY"):
                    self.assertNotIn(key, capture["env"])
                self.assertTrue(all(mode == 0o400 for mode in capture["modes"].values()))
                self.assertNotEqual(Path(capture["cwd"]), self.root)
                self.assertIn(capture["config"]["--config"], ("", "{}\n"))
                args = capture["argv"][1:]
                if tool == "trivy":
                    for expected in ("--offline-scan", "--disable-telemetry", "--skip-version-check", "--module-dir", "--cache-dir"):
                        self.assertIn(expected, args)
                    self.assertEqual(args[:2], ["fs", "--config"])
                    self.assertEqual(capture["config"]["--ignorefile"], "")
                else:
                    self.assertEqual(args[:4], ["scan", "source", "--recursive", "--no-resolve"])
                self.assertEqual(result["findings"][0]["location"]["path"], "pom.xml" if tool == "trivy" else "requirements.txt")
                self.assertFalse(self.marker.exists())

    def test_disabled_operation_and_plan_do_not_start_installed_tool(self):
        profile = self.fake("trivy")
        plan = cli.build_plan(profile, self.root)
        self.assertTrue(plan["startsToolProcess"])
        self.assertFalse(plan["executesProjectCode"])
        self.assertFalse(self.capture.exists())
        with self.assertRaises(ValueError):
            cli.evaluate(profile, self.root, "BUILD")
        self.assertFalse(self.capture.exists())

    def test_partial_symlink_snapshot_is_not_reported_as_complete(self):
        outside = self.user / "external.txt"
        outside.write_text("secret-not-in-snapshot", encoding="utf-8")
        (self.root / "linked.ps1").symlink_to(outside)
        result = cli.evaluate(self.fake("psscriptanalyzer"), self.root, "SCAN")
        self.assertNotIn("linked.ps1", self.captured()["files"])
        self.assertFalse(result["complete"])
        self.assertTrue(any(n["code"] == "WG-SDK-PARTIAL-SNAPSHOT" for n in result["notices"]))

    def test_nonzero_malformed_hanging_and_overflowing_scanner_fail_without_partial_success(self):
        for options in ({"exit_code": 9}, {"body": "print('not JSON')"},
                        {"body": "import time;time.sleep(10)", "timeoutMs": 100},
                        {"body": "print('x'*2048)", "maxOutputBytes": 1024}):
            with self.subTest(options=options):
                started = time.monotonic()
                profile = self.fake("trivy", **options)
                with self.assertRaises((ValueError, TimeoutError)):
                    run_tool(profile, self.root)
                self.assertLess(time.monotonic() - started, 2)

    def test_cross_tool_profile_fields_and_repository_executables_are_rejected(self):
        for tool, fields in (("trivy", {"modulePath": str(self.module)}), ("osv", {"endpoint": "http://127.0.0.1:1234/v1"}),
                             ("psscriptanalyzer", {"inputReportPath": str(self.user / "report.json")})):
            with self.subTest(tool=tool):
                with self.assertRaises(ValueError):
                    self.fake(tool, **fields)
        self.fake("trivy")
        self.settings["runner"]["tool"] = "osv"
        self.save()
        with self.assertRaises(ValueError):
            load_profile(self.profile_file, self.root)
        project_tool = self.root / "scanner"
        project_tool.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
        project_tool.chmod(0o700)
        self.settings["runner"] = {"tool": "trivy", "executable": str(project_tool)}
        self.save()
        with self.assertRaises(ValueError):
            load_profile(self.profile_file, self.root)

    def test_semgrep_uses_fixed_local_rules_and_kotlin_java_snapshot_without_project_suppressions(self):
        (self.root / "Main.kt").write_text("fun app() { anyHost() } // nosemgrep\n", encoding="utf-8")
        (self.root / "Main.java").write_text("class Main {}", encoding="utf-8")
        (self.root / ".semgrep.yml").write_text("rules: []", encoding="utf-8")
        (self.root / ".semgrepignore").write_text("*.kt\n", encoding="utf-8")
        result = cli.evaluate(self.fake("semgrep"), self.root, "SCAN")
        capture = self.captured()
        self.assertEqual(set(capture["files"]), {"Main.kt", "Main.java", "build.gradle.kts"})
        args = capture["argv"][1:]
        self.assertEqual(args[:2], ["scan", "--config"])
        self.assertEqual(args[3:], ["--sarif", "--metrics", "off", "--disable-version-check", "--no-git-ignore", "--disable-nosem", "--no-rewrite-rule-ids", "--max-target-bytes", "1048576", "--timeout", "10", "--jobs", "1", "--quiet", "."])
        rules = capture["config"]["--config"]
        self.assertIn("wrapperguard.ktor.unrestricted-cors", rules)
        self.assertIn("wrapperguard.android.javascript-bridge", rules)
        self.assertEqual(result["findings"][0]["location"]["path"], "Main.kt")
        self.assertTrue(result["complete"])
        self.assertFalse(self.marker.exists())

    def test_semgrep_custom_rules_must_be_reviewed_external_files_not_registry_or_repository_paths(self):
        rules = self.user / "reviewed-rules.yml"
        rules.write_text("rules: []\n", encoding="utf-8")
        profile = self.fake("semgrep", rulesPath=str(rules))
        self.assertEqual(profile.runner.rules_path, rules)
        for path in ("p/owasp-top-ten", str(self.root / "repo-rules.yml")):
            if path.startswith(str(self.root)):
                Path(path).write_text("rules: []\n", encoding="utf-8")
            self.settings["runner"]["rulesPath"] = path
            self.save()
            with self.subTest(path=path), self.assertRaises(ValueError):
                load_profile(self.profile_file, self.root)
        self.fake("trivy")
        self.settings["runner"]["rulesPath"] = str(rules)
        self.save()
        with self.assertRaises(ValueError):
            load_profile(self.profile_file, self.root)

    def test_semgrep_no_source_inputs_and_tool_errors_cannot_report_clean(self):
        (self.root / "build.gradle.kts").unlink()
        profile = self.fake("semgrep")
        with self.assertRaises(ValueError):
            cli.evaluate(profile, self.root, "SCAN")
        self.assertFalse(self.capture.exists())
        (self.root / "Main.kt").write_text("fun main() {}", encoding="utf-8")
        with self.assertRaises(ValueError):
            cli.evaluate(self.fake("semgrep", exit_code=2), self.root, "SCAN")

    def test_trivy_only_cache_path_stays_outside_project_and_has_sca_defaults(self):
        self.fake("trivy")
        self.settings["runner"].pop("timeoutMs")
        self.settings["runner"]["cachePath"] = str(self.user / "tool-cache")
        self.save()
        profile = load_profile(self.profile_file, self.root)
        self.assertEqual(profile.runner.timeout_ms, 120_000)
        self.assertEqual(profile.runner.cache_path, self.user / "tool-cache")
        self.settings["runner"]["cachePath"] = str(self.root / "cache")
        self.save()
        with self.assertRaises(ValueError):
            load_profile(self.profile_file, self.root)
        self.fake("osv")
        self.settings["runner"]["cachePath"] = str(self.user / "tool-cache")
        self.save()
        with self.assertRaises(ValueError):
            load_profile(self.profile_file, self.root)

    def test_snapshot_file_budget_produces_partial_coverage_notice(self):
        profile = self.fake("trivy")
        with patch("wrapperguard_sdk.runner.MAX_SOURCE_FILES", 1):
            _, notices = run_tool(profile, self.root)
        self.assertTrue(any(n["code"] == "WG-SDK-PARTIAL-SNAPSHOT" for n in notices))
        self.assertEqual(len(self.captured()["files"]), 1)


if __name__ == "__main__":
    unittest.main()
