"""Offline synthetic report fixtures: no scanner installations or target requests."""
from __future__ import annotations
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from wrapperguard_sdk.adapters import parse_report
from wrapperguard_sdk.profile import TargetScope

FIXTURES = Path(__file__).parent / "fixtures"
FORMATS = ("sarif", "bandit", "psscriptanalyzer", "trivy", "osv", "zap")


class AdapterTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "project"
        self.root.mkdir()
        for name in ("src/app.py", "src/space name.py", "scripts/setup.ps1", "pom.xml", "requirements.txt"):
            p = self.root / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text("# inert synthetic fixture\n" * 8)
        self.scope = TargetScope.from_dict({"origins": ["https://demo.invalid"], "pathPrefixes": ["/app"]})

    def fixture(self, name):
        return json.loads((FIXTURES / (name + ".json")).read_text())

    def parse(self, format, document=None, actions=None, scope=None):
        return parse_report(format, self.fixture(format) if document is None else document,
                            self.root, ["WARNING"] if actions is None else actions,
                            self.scope if scope is None and format == "zap" else scope)

    def test_each_fixture_emits_host_compatible_metadata(self):
        for name in FORMATS:
            with self.subTest(name=name):
                report = self.parse(name)
                self.assertTrue(report["complete"], report["notices"])
                self.assertEqual(len(report["findings"]), 1)
                finding = report["findings"][0]
                self.assertIn(finding["severity"], ("INFO", "WARN", "ERROR"))
                self.assertEqual(finding["actions"], ["WARNING"])
                self.assertNotIn("providerId", finding)
                self.assertNotIn("policyAction", finding)
                self.assertNotIn("status", finding)
                self.assertNotIn("fingerprint", finding)
                self.assertNotIn("evidence", finding)
                self.assertEqual(report["scannedFiles"], 0 if name == "zap" else 1)

    def test_raw_source_and_http_material_never_leave_adapters(self):
        for name in FORMATS:
            with self.subTest(name=name):
                output = json.dumps(self.parse(name))
                self.assertNotIn("_SECRET", output)
                self.assertNotIn("FAKE_SCRIPT_BODY", output)
                self.assertNotIn("<p>", output)
                self.assertNotIn("<script", output)

    def test_actions_are_explicit_requests_not_adapter_defaults(self):
        for name in FORMATS:
            with self.subTest(name=name):
                report = self.parse(name, actions=["ALERT", "BLOCK_SYNC"])
                self.assertEqual(report["findings"][0]["actions"], ["ALERT", "BLOCK_SYNC"])
        self.assertEqual(self.parse("sarif", actions=[])["findings"][0]["actions"], [])
        for actions in (["BLOCK_EVERYTHING"], "WARNING", [True], ["WARNING", "WARNING"]):
            with self.assertRaises(ValueError):
                self.parse("sarif", actions=actions)

    def test_unknown_formats_and_malformed_envelopes_fail(self):
        with self.assertRaises(ValueError):
            self.parse("unknown", {})
        for name in FORMATS:
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.parse(name, {})

    def test_valid_empty_results_are_supported(self):
        cases = {"sarif": {"version": "2.1.0", "runs": []},
                 "bandit": {"results": [], "errors": [], "metrics": {}},
                 "psscriptanalyzer": [], "trivy": {"SchemaVersion": 2, "Results": []},
                 "osv": {"results": []}, "zap": {"site": [{"@name": "https://demo.invalid", "alerts": []}]}}
        for name, doc in cases.items():
            with self.subTest(name=name):
                report = self.parse(name, doc)
                self.assertTrue(report["complete"])
                self.assertEqual(report["findings"], [])

    def test_unknown_schema_versions_are_incomplete_not_clean(self):
        for name, doc in (("sarif", {"version": "9.0", "runs": []}), ("trivy", {"SchemaVersion": 9, "Results": []})):
            report = self.parse(name, doc)
            self.assertFalse(report["complete"])
            self.assertTrue(report["notices"])

    def test_path_traversal_absolute_outside_and_symlink_escape_are_skipped(self):
        outside = Path(self.temp.name) / "outside.py"
        outside.write_text("# inert outside fixture\n")
        (self.root / "linked").symlink_to(outside)
        for raw in ("../outside.py", str(outside), outside.as_uri(), "linked", "https://external.invalid/source.py"):
            with self.subTest(raw=raw):
                doc = self.fixture("bandit")
                doc["metrics"] = {}
                doc["results"][0]["filename"] = raw
                report = self.parse("bandit", doc)
                self.assertFalse(report["complete"])
                self.assertEqual(report["findings"], [])
                self.assertNotIn("outside.py", json.dumps(report))

    def test_partial_report_retains_valid_findings(self):
        doc = self.fixture("bandit")
        invalid = copy.deepcopy(doc["results"][0])
        invalid["filename"] = "../outside.py"
        doc["results"].extend([invalid, 3])
        report = self.parse("bandit", doc)
        self.assertFalse(report["complete"])
        self.assertEqual(len(report["findings"]), 1)

    def test_invalid_positions_are_omitted_and_incomplete(self):
        doc = self.fixture("bandit")
        doc["results"][0]["line_number"] = True
        report = self.parse("bandit", doc)
        self.assertFalse(report["complete"])
        self.assertNotIn("line", report["findings"][0]["location"])

    def test_large_positions_do_not_drop_valid_finding(self):
        doc = self.fixture("bandit")
        doc["results"][0]["line_number"] = 2 ** 63
        report = self.parse("bandit", doc)
        self.assertFalse(report["complete"])
        self.assertEqual(len(report["findings"]), 1)
        self.assertNotIn("line", report["findings"][0]["location"])

    def test_finding_budget_is_incomplete_and_bounded(self):
        doc = self.fixture("bandit")
        doc["results"] = doc["results"] * 5
        with patch("wrapperguard_sdk.adapters.MAX_FINDINGS", 2):
            report = self.parse("bandit", doc)
        self.assertFalse(report["complete"])
        self.assertEqual(len(report["findings"]), 2)

    def test_info_never_requests_blocking_actions(self):
        docs = {name: self.fixture(name) for name in FORMATS}
        docs["sarif"]["runs"][0]["results"][0]["level"] = "note"
        docs["bandit"]["results"][0]["issue_severity"] = "UNDEFINED"
        docs["psscriptanalyzer"][0]["Severity"] = 0
        docs["trivy"]["Results"][0]["Vulnerabilities"][0]["Severity"] = "UNKNOWN"
        docs["osv"]["results"][0]["packages"][0]["vulnerabilities"][0]["database_specific"]["severity"] = "UNKNOWN"
        docs["zap"]["site"][0]["alerts"][0]["riskcode"] = "0"
        for name, doc in docs.items():
            with self.subTest(name=name):
                finding = self.parse(name, doc, actions=["BLOCK_BUILD", "BLOCK_SYNC", "ALERT"])["findings"][0]
                self.assertEqual(finding["severity"], "INFO")
                self.assertEqual(finding["actions"], [])

    def test_bandit_tool_errors_do_not_echo_error_text(self):
        doc = self.fixture("bandit")
        doc["errors"] = [{"filename": "FAKE_ERROR_SECRET", "reason": "FAKE_ERROR_SECRET"}]
        report = self.parse("bandit", doc)
        self.assertFalse(report["complete"])
        self.assertNotIn("FAKE_ERROR_SECRET", json.dumps(report))

    def test_sarif_artifact_index_and_uri_base_resolution(self):
        doc = self.fixture("sarif")
        run = doc["runs"][0]
        run["originalUriBaseIds"] = {"ROOT": {"uri": self.root.as_uri() + "/"}}
        run["artifacts"] = [{"location": {"uri": "src/space%20name.py", "uriBaseId": "ROOT"}}]
        run["results"][0]["locations"][0]["physicalLocation"]["artifactLocation"] = {"index": 0}
        report = self.parse("sarif", doc)
        self.assertTrue(report["complete"], report["notices"])
        self.assertEqual(report["findings"][0]["location"]["path"], "src/space name.py")

    def test_sarif_does_not_double_decode_relative_uri(self):
        doc = self.fixture("sarif")
        doc["runs"][0]["artifacts"] = []
        doc["runs"][0]["results"][0]["locations"][0]["physicalLocation"]["artifactLocation"] = {"uri": "src/space%2520name.py"}
        report = self.parse("sarif", doc)
        self.assertFalse(report["complete"])
        self.assertEqual(report["findings"], [])

    def test_sarif_unknown_base_and_cyclic_index_fail_closed(self):
        for artifact in ({"uri": "src/app.py", "uriBaseId": "MISSING"}, {"index": 0}):
            doc = self.fixture("sarif")
            doc["runs"][0]["artifacts"] = [{"location": artifact}]
            doc["runs"][0]["results"][0]["locations"][0]["physicalLocation"]["artifactLocation"] = {"index": 0}
            report = self.parse("sarif", doc)
            self.assertFalse(report["complete"])
            self.assertEqual(report["findings"], [])

    def test_sarif_tool_failure_and_suppression_are_not_approval(self):
        doc = self.fixture("sarif")
        run = doc["runs"][0]
        run["invocations"][0]["executionSuccessful"] = False
        run["results"][0]["suppressions"] = [{"kind": "inSource", "status": "accepted"}]
        report = self.parse("sarif", doc)
        self.assertFalse(report["complete"])
        self.assertEqual(len(report["findings"]), 1)
        self.assertNotIn("status", report["findings"][0])

    def test_sarif_malformed_artifacts_never_become_clean(self):
        for artifacts in ({"bad": "shape"}, [3], [{"location": {"uri": "http://["}}]):
            with self.subTest(artifacts=artifacts):
                doc = self.fixture("sarif")
                doc["runs"][0]["artifacts"] = artifacts
                doc["runs"][0]["results"] = []
                self.assertFalse(self.parse("sarif", doc)["complete"])

    def test_sarif_rule_default_and_pass_kind(self):
        doc = self.fixture("sarif")
        record = doc["runs"][0]["results"][0]
        del record["level"]
        self.assertEqual(self.parse("sarif", doc)["findings"][0]["severity"], "ERROR")
        record["kind"] = "pass"
        finding = self.parse("sarif", doc, actions=["BLOCK_BUILD"])["findings"][0]
        self.assertEqual(finding["severity"], "INFO")
        self.assertEqual(finding["actions"], [])

    def test_psscriptanalyzer_single_record_numeric_enum_and_parse_error(self):
        doc = self.fixture("psscriptanalyzer")[0]
        doc["Severity"] = 2
        self.assertEqual(self.parse("psscriptanalyzer", doc)["findings"][0]["severity"], "ERROR")
        doc["Severity"] = 3
        report = self.parse("psscriptanalyzer", doc)
        self.assertFalse(report["complete"])
        self.assertEqual(report["findings"][0]["severity"], "ERROR")

    def test_trivy_official_omitted_empty_results(self):
        report = self.parse("trivy", {"SchemaVersion": 2, "ArtifactName": ".", "ArtifactType": "filesystem"})
        self.assertTrue(report["complete"])
        self.assertEqual(report["findings"], [])
        self.assertFalse(self.parse("trivy", {"SchemaVersion": 2})["complete"])

    def test_zap_no_targets_is_incomplete(self):
        self.assertFalse(self.parse("zap", {"site": []})["complete"])

    def test_trivy_unsupported_scanners_do_not_become_clean(self):
        doc = self.fixture("trivy")
        doc["Results"][0]["Secrets"] = [{"Match": "FAKE_TRIVY_MATCH_SECRET"}]
        report = self.parse("trivy", doc)
        self.assertFalse(report["complete"])
        self.assertNotIn("FAKE_TRIVY_MATCH_SECRET", json.dumps(report))
        doc["Results"][0]["Class"] = "os-pkgs"
        self.assertEqual(self.parse("trivy", doc)["findings"], [])

    def test_trivy_aggregate_labels_require_real_package_paths(self):
        doc = self.fixture("trivy")
        doc["Results"][0]["Target"] = "Java"
        report = self.parse("trivy", doc)
        self.assertFalse(report["complete"])
        self.assertEqual(report["findings"], [])
        self.assertEqual(report["scannedFiles"], 0)
        doc["Results"][0]["Vulnerabilities"][0]["PkgPath"] = "pom.xml"
        report = self.parse("trivy", doc)
        self.assertTrue(report["complete"])
        self.assertEqual(report["findings"][0]["location"]["path"], "pom.xml")
        doc["Results"][0]["Vulnerabilities"][0]["PkgPath"] = "../outside.jar"
        self.assertFalse(self.parse("trivy", doc)["complete"])

    def test_osv_group_scores_are_not_cvss_vector_strings(self):
        doc = self.fixture("osv")
        entry = doc["results"][0]["packages"][0]
        del entry["vulnerabilities"][0]["database_specific"]
        self.assertEqual(self.parse("osv", doc)["findings"][0]["severity"], "ERROR")
        entry["groups"][0]["max_severity"] = "CVSS:3.1/AV:N/FAKE"
        report = self.parse("osv", doc)
        self.assertFalse(report["complete"])
        self.assertEqual(report["findings"][0]["severity"], "WARN")

    def test_zap_requires_scope_and_never_sends_requests(self):
        with self.assertRaises(ValueError):
            parse_report("zap", self.fixture("zap"), self.root, ["WARNING"])
        with patch("urllib.request.urlopen", side_effect=AssertionError("network forbidden")), patch("socket.create_connection", side_effect=AssertionError("network forbidden")):
            report = self.parse("zap")
        finding = report["findings"][0]
        self.assertEqual(finding["location"], {"path": "."})
        self.assertIn("https://demo.invalid/app/login", finding["message"])
        self.assertNotIn("?", finding["message"])

    def test_zap_every_instance_needs_scope_not_just_site(self):
        bad_urls = ("https://external.invalid/app/login", "https://demo.invalid/app2/login", "http://demo.invalid/app/login", "https://demo.invalid:444/app/login", "https://user:FAKE_PASSWORD_SECRET@demo.invalid/app/login", "https://demo.invalid/app/%2e%2e/private", "https://demo.invalid/app/login#FAKE_FRAGMENT_SECRET")
        for uri in bad_urls:
            with self.subTest(uri=uri):
                doc = self.fixture("zap")
                doc["site"][0]["alerts"][0]["instances"].append({"uri": uri})
                report = self.parse("zap", doc)
                self.assertFalse(report["complete"])
                self.assertEqual(len(report["findings"]), 1)
                self.assertNotIn("FAKE_PASSWORD_SECRET", json.dumps(report))

    def test_zap_unscoped_empty_site_is_incomplete(self):
        report = self.parse("zap", {"site": [{"@name": "https://external.invalid", "alerts": []}]})
        self.assertFalse(report["complete"])

    def test_report_messages_strip_url_credentials_and_query_values(self):
        doc = self.fixture("bandit")
        doc["results"][0]["issue_text"] = "Inspect https://user:FAKE_PASSWORD_SECRET@demo.invalid/app?token=FAKE_QUERY_SECRET#FAKE_FRAGMENT_SECRET"
        output = json.dumps(self.parse("bandit", doc))
        self.assertNotIn("_SECRET", output)
        self.assertIn("https://demo.invalid/app", output)


if __name__ == "__main__":
    unittest.main()
