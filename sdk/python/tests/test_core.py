from __future__ import annotations

import io
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

from wrapperguard_sdk import cli
from wrapperguard_sdk.models import make_finding, redact_text
from wrapperguard_sdk.paths import normalize_location
from wrapperguard_sdk.profile import (MAX_PROFILE_BYTES, TargetScope, bounded_read,
                                      load_json_document, load_profile)

ENTRY = "from wrapperguard_sdk.cli import main;raise SystemExit(main())"


class _Fixture:
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="wg-sdk-test-")
        self.addCleanup(self.temporary.cleanup)
        self.user = Path(self.temporary.name).resolve()
        self.root = self.user / "project"
        self.root.mkdir()
        self.report = self.user / "report.json"
        self.report.write_text(json.dumps({"results": [], "errors": [], "metrics": {}}), encoding="utf-8")
        self.profile = self.user / "profile.json"
        self.settings = {"id": "example.bandit", "displayName": "Bandit", "version": "1.0",
                         "analysisKind": "SAST", "format": "bandit", "reportPath": str(self.report)}
        self.save()

    def save(self):
        self.profile.write_text(json.dumps(self.settings), encoding="utf-8")

    def invoke(self, *args, operation="SCAN", request=None, environment=None, timeout=10):
        if request is None:
            request = {"apiVersion": 1, "projectPath": str(self.root), "operation": operation}
        return subprocess.run([sys.executable, "-I", "-c", ENTRY, "--profile", str(self.profile), *args],
                              input=json.dumps(request) + "\n", cwd=self.root, env=environment,
                              text=True, capture_output=True, timeout=timeout)

    def fake_bandit(self, body: str, **config):
        executable = self.user / "bandit"
        executable.write_text("#!" + sys.executable + "\n" + body, encoding="utf-8")
        executable.chmod(0o700)
        self.settings.pop("reportPath", None)
        self.settings["runner"] = {"tool": "bandit", "executable": str(executable), **config}
        self.save()
        return executable


class CoreTests(_Fixture, unittest.TestCase):
    def test_profile_defaults_request_warning_scan_without_enabling_host_provider(self):
        profile = load_profile(self.profile, self.root)
        self.assertEqual(profile.allowed_operations, ("SCAN",))
        self.assertEqual(profile.requested_actions, ("WARNING",))
        plan = cli.build_plan(profile, self.root)
        self.assertFalse(plan["startsToolProcess"])
        self.assertFalse(plan["executesProjectCode"])
        self.assertNotIn("enabled", plan)

    def test_profile_absolute_outside_project_and_resolved_symlink_boundary(self):
        inside = self.root / "profile.json"
        inside.write_text(self.profile.read_text(), encoding="utf-8")
        alias = self.user / "alias.json"
        alias.symlink_to(inside)
        for path in (Path("profile.json"), inside, alias):
            with self.subTest(path=path), self.assertRaises(ValueError):
                load_profile(path, self.root)

    def test_profile_strict_keys_mutual_exclusion_and_actions(self):
        changes = [{"enabled": True}, {"command": ["/bin/sh"]}, {"permissions": {}},
                   {"requestedActions": ["EXECUTE"]}, {"requestedActions": ["WARNING", "WARNING"]},
                   {"allowedOperations": []}, {"allowedOperations": ["SCAN", "SCAN"]},
                   {"analysisKind": "DAST"}, {"format": "unknown"},
                   {"runner": {"tool": "bandit", "executable": sys.executable}}]
        original = dict(self.settings)
        for changeset in changes:
            with self.subTest(changeset=changeset):
                self.settings = {**original, **changeset}
                self.save()
                with self.assertRaises(ValueError):
                    load_profile(self.profile, self.root)

    def test_duplicate_json_nonfinite_numbers_and_profile_size_are_rejected(self):
        for raw in ('{"id":"first","id":"second"}', '{"value":NaN}', '{"value":1e400}',
                    '{"nested":[-1e400]}', 'null'):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                load_json_document(raw)
        self.profile.write_bytes(b" " * (MAX_PROFILE_BYTES + 1))
        with self.assertRaises(ValueError):
            load_profile(self.profile, self.root)

    def test_bounded_file_read_and_nonregular_inputs(self):
        self.report.write_bytes(b"123456789")
        with self.assertRaises(ValueError):
            bounded_read(self.report, 8)
        self.assertEqual(bounded_read(self.report, 9), b"123456789")
        if hasattr(os, "mkfifo"):
            fifo = self.user / "input.fifo"
            os.mkfifo(fifo)
            with self.assertRaises(ValueError):
                bounded_read(fifo, 8)

    def test_dast_requires_scope_and_cannot_select_any_runner(self):
        self.settings.update(analysisKind="DAST", format="zap")
        self.save()
        with self.assertRaises(ValueError):
            load_profile(self.profile, self.root)
        self.settings["targetScope"] = {"origins": ["https://example.invalid"], "pathPrefixes": ["/api"]}
        self.save()
        self.assertTrue(load_profile(self.profile, self.root).target_scope.allows("https://example.invalid/api"))
        self.fake_bandit("pass\n")
        with self.assertRaises(ValueError):
            load_profile(self.profile, self.root)

    def test_target_scope_exact_origin_default_port_and_segment_prefix(self):
        scope = TargetScope(["HTTPS://Example.INVALID:443/"], ["/api/"])
        for url in ("https://example.invalid/api", "https://example.invalid/api/v1?token=secret"):
            self.assertTrue(scope.allows(url), url)
        for url in ("http://example.invalid/api", "https://example.invalid:444/api",
                    "https://sub.example.invalid/api", "https://example.invalid/api2",
                    "https://user:password@example.invalid/api", "https://example.invalid/api#fragment",
                    "https://example.invalid/api/%2e%2e/outside", "https://example.invalid/api/%252e%252e/outside",
                    "https://example.invalid/api/%5c../outside", "https://example.invalid./api"):
            self.assertFalse(scope.allows(url), url)

    def test_target_scope_ipv6_and_bad_origin_settings(self):
        self.assertTrue(TargetScope(["http://[::1]:80"]).allows("http://[::1]/"))
        for origin in ("https://example.invalid/api", "https://example.invalid?x=1",
                       "https://example.invalid#", "https://user@example.invalid", "file:///tmp",
                       "https://example.invalid:", "https://example.invalid:0", "https://*.example.invalid"):
            with self.subTest(origin=origin), self.assertRaises(ValueError):
                TargetScope([origin])
        with self.assertRaises(ValueError):
            TargetScope.from_dict({"origins": ["https://example.invalid"], "allowSubdomains": True})

    def test_locations_decode_file_uris_and_reject_containment_escapes(self):
        source = self.root / "with space.py"
        source.write_text("pass\n", encoding="utf-8")
        for raw in ("with%20space.py", source.as_uri(), str(source)):
            self.assertEqual(normalize_location(self.root, raw), "with space.py")
        outside = self.user / "outside.py"
        outside.write_text("pass\n", encoding="utf-8")
        (self.root / "escape").symlink_to(self.user, target_is_directory=True)
        for raw in ("../outside.py", "%2e%2e/outside.py", str(outside), outside.as_uri(),
                    "escape/outside.py", "https://example.invalid/code.py", "//server/code.py",
                    "file://server/code.py", "file:///tmp/code.py?query=1", "code.py\x00"):
            self.assertIsNone(normalize_location(self.root, raw), raw)

    def test_findings_minimize_fields_and_redact_credential_literals(self):
        finding = make_finding("B001", "Review token=secret-value", "Review the source", "WARN", "HIGH",
                               "EXECUTION", "app.py", line=3, evidence='call("unknown-secret-literal"); password="known-secret"')
        serialized = json.dumps(finding)
        for secret in ("secret-value", "unknown-secret-literal", "known-secret"):
            self.assertNotIn(secret, serialized)
        self.assertEqual(finding["actions"], ["WARNING"])
        self.assertTrue(finding["evidence"]["redacted"])
        for field in ("fingerprint", "providerId", "status", "policyAction"):
            self.assertNotIn(field, finding)
        with self.assertRaises(ValueError):
            make_finding("B001", "Review", "Message", "WARN", "HIGH", "EXECUTION", "../escape.py")
        with self.assertRaises(ValueError):
            make_finding("B001", "Review", "Message", "WARN", "HIGH", "EXECUTION", "app.py", line=True)
        with self.assertRaises(ValueError):
            make_finding("B001", "Review", "Message", [], "HIGH", "EXECUTION", "app.py")

    def test_redaction_visits_later_credentials_blocks_and_removes_url_credentials(self):
        raw = 'credentials { username="' + "a" * 100 + '" } credentials { identity="second-identity" password="second-secret" }'
        masked = redact_text(raw)
        self.assertNotIn("second-identity", masked)
        self.assertNotIn("second-secret", masked)
        self.assertEqual(redact_text(masked), masked)
        masked_url = redact_text("https://user:password@example.invalid/api?arbitrary=secret-value#fragment")
        self.assertNotIn("password", masked_url)
        self.assertNotIn("secret-value", masked_url)

    def test_plan_never_reads_report_or_starts_process(self):
        self.report.unlink()
        result = self.invoke("--plan", "--project", str(self.root))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)["mode"], "report-import")
        marker = self.user / "executed"
        self.fake_bandit("from pathlib import Path\nPath(" + repr(str(marker)) + ").touch()\n")
        result = self.invoke("--plan", "--project", str(self.root))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(marker.exists())

    def test_protocol_result_has_only_api1_output_and_actions_remain_requests(self):
        result = self.invoke()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stderr, "")
        report = json.loads(result.stdout)
        self.assertEqual(report["findings"], [])
        self.assertTrue(report["complete"])
        self.assertNotIn("profile", report)

    def test_protocol_rejects_boolean_version_unknown_fields_and_disallowed_operation(self):
        for request in ({"apiVersion": True, "projectPath": str(self.root), "operation": "SCAN"},
                        {"apiVersion": 1, "projectPath": str(self.root), "operation": "SCAN", "actions": ["BLOCK_BUILD"]}):
            result = self.invoke(request=request)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(result.stdout, "")
        marker = self.user / "executed"
        self.fake_bandit("from pathlib import Path\nPath(" + repr(str(marker)) + ").touch()\n")
        result = self.invoke(operation="BUILD")
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(marker.exists())

    def test_failures_are_generic_and_output_size_is_enforced_before_stdout(self):
        self.report.write_text('{"token":"PRIVATE_REPORT_VALUE", invalid}', encoding="utf-8")
        result = self.invoke()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "")
        self.assertNotIn("PRIVATE_REPORT_VALUE", result.stderr)
        output, errors = type("Buffered", (), {"buffer": io.BytesIO()})(), io.StringIO()
        request = type("Buffered", (), {"buffer": io.BytesIO(json.dumps({"apiVersion": 1,
                    "projectPath": str(self.root), "operation": "SCAN"}).encode())})()
        with patch.object(cli, "evaluate", return_value={"findings": [], "message": "x" * 100}), \
             patch.object(cli, "MAX_OUTPUT_BYTES", 64), patch.object(sys, "stdin", request), \
             patch.object(sys, "stdout", output), patch.object(sys, "stderr", errors):
            self.assertEqual(cli.main(["--profile", str(self.profile)]), 1)
        self.assertEqual(output.buffer.getvalue(), b"")


@unittest.skipUnless(os.name == "posix", "The initial Bandit runner requires POSIX")
class RunnerTests(_Fixture, unittest.TestCase):
    def test_snapshot_fixed_arguments_environment_and_no_symlink_input(self):
        marker = self.user / "observed.json"
        project_marker = self.user / "PROJECT_EXECUTED"
        (self.root / "app.py").write_text('from pathlib import Path\nPath(' + repr(str(project_marker)) + ').touch()\n', encoding="utf-8")
        (self.root / ".bandit").write_text("[bandit]\nskips=B001\n", encoding="utf-8")
        (self.root / "pyproject.toml").write_text("[tool.bandit]\nskips=['B001']\n", encoding="utf-8")
        outside = self.user / "outside.py"
        outside.write_text("OUTSIDE_SOURCE_MARKER\n", encoding="utf-8")
        (self.root / "linked.py").symlink_to(outside)
        self.fake_bandit("import json, os, sys\nfrom pathlib import Path\n" +
                         "Path(" + repr(str(marker)) + ").write_text(json.dumps({\n" +
                         "'argv':sys.argv[1:],'files':sorted(str(p) for p in Path('.').rglob('*')),\n" +
                         "'env':dict(os.environ),'cwd':os.getcwd()}))\n" +
                         "print(json.dumps({'results':[],'errors':[],'metrics':{'./app.py':{}}}))\n")
        result = self.invoke(environment=dict(os.environ, PYTHONPATH=str(self.root), SECRET_ENV_VALUE="do-not-forward"))
        self.assertEqual(result.returncode, 0, result.stderr)
        report = json.loads(result.stdout)
        self.assertFalse(report["complete"])
        observed = json.loads(marker.read_text())
        self.assertEqual(observed["files"], ["app.py"])
        self.assertNotEqual(observed["cwd"], str(self.root))
        self.assertNotIn("SECRET_ENV_VALUE", observed["env"])
        self.assertNotIn("PYTHONPATH", observed["env"])
        self.assertIn("--ignore-nosec", observed["argv"])
        self.assertIn("--exit-zero", observed["argv"])
        self.assertEqual(observed["argv"][-2:], ["-r", "."])
        self.assertFalse(project_marker.exists())

    def test_runner_timeout_and_output_budget_reap_known_child(self):
        (self.root / "app.py").write_text("pass\n", encoding="utf-8")
        pidfile = self.user / "child.pid"
        prefix = "import os\nfrom pathlib import Path\nPath(" + repr(str(pidfile)) + ").write_text(str(os.getpid()))\n"
        for body, config in (("import time\ntime.sleep(20)\n", {"timeoutMs": 750}),
                             ("import sys\nsys.stdout.write('x'*4096)\nsys.stdout.flush()\n", {"maxOutputBytes": 64})):
            with self.subTest(config=config):
                self.fake_bandit(prefix + body, **config)
                started = time.monotonic()
                result = self.invoke()
                self.assertLess(time.monotonic() - started, 3)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(result.stdout, "")
                with self.assertRaises(ProcessLookupError):
                    os.kill(int(pidfile.read_text()), 0)

    def test_empty_python_scope_is_incomplete_without_starting_tool(self):
        marker = self.user / "executed"
        self.fake_bandit("from pathlib import Path\nPath(" + repr(str(marker)) + ").touch()\n")
        result = self.invoke()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(json.loads(result.stdout)["complete"])
        self.assertFalse(marker.exists())

    def test_sdk_host_group_cancellation_reaches_bandit(self):
        (self.root / "app.py").write_text("pass\n", encoding="utf-8")
        pidfile = self.user / "child.pid"
        self.fake_bandit("import os,time\nfrom pathlib import Path\nPath(" + repr(str(pidfile)) +
                         ").write_text(str(os.getpid()))\ntime.sleep(20)\n")
        process = subprocess.Popen([sys.executable, "-I", "-c", ENTRY, "--profile", str(self.profile)],
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                   start_new_session=True)
        try:
            process.stdin.write((json.dumps({"apiVersion": 1, "projectPath": str(self.root), "operation": "SCAN"}) + "\n").encode())
            process.stdin.close()
            deadline = time.monotonic() + 3
            while not pidfile.exists() and time.monotonic() < deadline:
                time.sleep(0.02)
            self.assertTrue(pidfile.exists())
            pid = int(pidfile.read_text())
            self.assertEqual(os.getpgid(pid), process.pid)
            os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=3)
            for _ in range(100):
                status = subprocess.run(["/bin/ps", "-p", str(pid), "-o", "stat="], capture_output=True, text=True)
                if not status.stdout.strip() or status.stdout.strip().startswith("Z"):
                    break
                time.sleep(0.02)
            else:
                self.fail("Bandit remained running after host group cancellation")
        finally:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait(timeout=3)
            for stream in (process.stdout, process.stderr):
                stream.close()

    def test_deadline_is_bounded_when_descendant_holds_stdout_open(self):
        (self.root / "app.py").write_text("pass\n", encoding="utf-8")
        pidfile = self.user / "descendant.pid"
        child = "import os,time;from pathlib import Path;Path(" + repr(str(pidfile)) + ").write_text(str(os.getpid()));time.sleep(20)"
        self.fake_bandit("import subprocess,sys\nsubprocess.Popen([sys.executable,'-c'," + repr(child) + "])\n",
                         timeoutMs=750)
        try:
            started = time.monotonic()
            result = self.invoke(timeout=3)
            self.assertNotEqual(result.returncode, 0)
            self.assertLess(time.monotonic() - started, 2)
        finally:
            if pidfile.exists():
                try:
                    os.kill(int(pidfile.read_text()), signal.SIGKILL)
                except ProcessLookupError:
                    pass


if __name__ == "__main__":
    unittest.main()
