from __future__ import annotations

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

from wrapperguard_sdk.ai_review import run_ai_review
from wrapperguard_sdk.profile import IntegrationProfile, RunnerConfig


class AiReviewTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="wg-ai-review-")
        self.addCleanup(self.temporary.cleanup)
        self.user = Path(self.temporary.name).resolve()
        self.root = self.user / "project"
        self.root.mkdir()
        (self.root / "build.gradle").write_text("// source-never-read", encoding="utf-8")
        self.report = self.user / "reviewed.json"
        self.records = [{"ruleId": "WG-BUILD-001", "title": "Repository credential warning",
                         "message": 'Review token=sensitive-secret at /Users/person/private/config and C:\\Users\\person\\keys; UNC \\\\server\\private; password="quoted-secret".',
                         "severity": "ERROR", "location": {"path": "build.gradle", "line": 3},
                         "evidence": {"text": "evidence-secret-never-send"},
                         "actions": ["BLOCK_BUILD"], "policyAction": "BLOCK"}]
        self.save()
        self.requests = []
        self.status = 200
        self.extra_headers = {}
        self.delay = 0
        self.slow_headers = False
        self.custom_body = None
        self.response_reviews = [{"explanation": "Review the configured repository owner before building."}]
        fixture = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
                fixture.requests.append((self.path, dict(self.headers), json.loads(body)))
                if fixture.delay:
                    time.sleep(fixture.delay)
                output = fixture.custom_body
                if output is None:
                    output = json.dumps({"choices": [{"finish_reason": "stop", "message": {
                        "content": json.dumps({"reviews": fixture.response_reviews})}}]}).encode()
                try:
                    if fixture.slow_headers:
                        for byte in b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n":
                            self.connection.sendall(bytes([byte]))
                            time.sleep(0.02)
                        return
                    self.send_response(fixture.status)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(output)))
                    for key, value in fixture.extra_headers.items():
                        self.send_header(key, value)
                    self.end_headers()
                    self.wfile.write(output)
                except (BrokenPipeError, ConnectionResetError):
                    pass

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.daemon_threads = True
        self.thread = threading.Thread(target=lambda: self.server.serve_forever(poll_interval=0.01), daemon=True)
        self.thread.start()
        self.addCleanup(self.stop_server)
        self.endpoint = f"http://127.0.0.1:{self.server.server_port}/v1"

    def stop_server(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=1)

    def save(self):
        self.report.write_text(json.dumps({"schemaVersion": 1, "projectPath": str(self.root), "findings": self.records}), encoding="utf-8")

    def profile(self, **changes):
        values = {"tool": "local-ai", "endpoint": self.endpoint, "model": "local-test-model",
                  "input_report_path": self.report, "timeout_ms": 1000, "max_output_bytes": 65_536}
        values.update(changes)
        return IntegrationProfile("ai.external-model", "Local AI", "1", "AI", "ai-review", None,
                                  RunnerConfig(**values), ("SCAN",), ("BLOCK_BUILD",), None)

    def test_real_loopback_http_request_minimizes_metadata_and_keeps_actions_host_owned(self):
        result = run_ai_review(self.profile(), self.root)
        self.assertEqual(len(self.requests), 1)
        path, headers, payload = self.requests[0]
        self.assertEqual(path, "/v1/chat/completions")
        self.assertNotIn("Authorization", headers)
        observations = json.loads(payload["messages"][1]["content"])["observations"]
        self.assertEqual(set(observations[0]), {"ruleId", "title", "message", "severity"})
        serialized = json.dumps(payload)
        for secret in ("sensitive-secret", "quoted-secret", "evidence-secret-never-send", str(self.root),
                       "/Users/person", "C:\\\\Users", "server", "build.gradle", "source-never-read"):
            self.assertNotIn(secret, serialized)
        self.assertEqual(result["findings"][0]["location"], {"path": "build.gradle", "line": 3})
        self.assertEqual(result["findings"][0]["severity"], "INFO")
        self.assertEqual(result["findings"][0]["kind"], "AUDIT_EVIDENCE")
        self.assertEqual(result["findings"][0]["actions"], [])
        self.assertEqual(result["scannedFiles"], 0)
        self.assertTrue(result["complete"])

    def test_structured_output_schema_binds_each_review_and_has_explicit_compatibility_mode(self):
        run_ai_review(self.profile(), self.root)
        definition = self.requests[0][2]["response_format"]
        self.assertEqual(definition["type"], "json_schema")
        schema = definition["json_schema"]["schema"]
        self.assertFalse(schema["additionalProperties"])
        self.assertEqual(schema["properties"]["reviews"]["minItems"], len(self.records))
        self.assertEqual(schema["properties"]["reviews"]["maxItems"], len(self.records))
        self.assertEqual(schema["properties"]["reviews"]["items"]["required"], ["explanation"])
        run_ai_review(self.profile(response_format="json_object"), self.root)
        self.assertEqual(self.requests[1][2]["response_format"], {"type": "json_object"})

    def test_invalid_response_format_fails_before_contacting_model(self):
        with self.assertRaises(ValueError):
            run_ai_review(self.profile(response_format="free_text"), self.root)
        self.assertEqual(self.requests, [])

    def test_hostile_model_fields_cannot_change_actions_location_or_render_active_markup(self):
        self.response_reviews = [{"explanation": '<script>alert("bad")</script><b>Review</b> [click](javascript:danger) &lt;img src=x&gt; token=output-secret',
                                  "location": {"path": "../../outside"}, "severity": "ERROR",
                                  "actions": ["BLOCK_BUILD"], "ruleId": "FORGED", "policyAction": "ALLOW"}]
        finding = run_ai_review(self.profile(), self.root)["findings"][0]
        self.assertEqual(finding["ruleId"], "WG-AI-REVIEW")
        self.assertEqual(finding["location"]["path"], "build.gradle")
        self.assertEqual(finding["actions"], [])
        self.assertEqual(finding["severity"], "INFO")
        for dangerous in ("<script", "<img", "javascript:", "output-secret", "alert("):
            self.assertNotIn(dangerous, finding["message"])
        self.assertNotIn("policyAction", finding)

    def test_redirects_are_rejected_without_a_second_request(self):
        self.status = 302
        self.extra_headers["Location"] = "http://192.0.2.1:1234/v1/chat/completions"
        with self.assertRaisesRegex(ValueError, "Local AI review could not complete"):
            run_ai_review(self.profile(), self.root)
        self.assertEqual(len(self.requests), 1)

    def test_remote_dns_and_invalid_endpoints_are_rejected_before_network_io(self):
        for endpoint in ("http://localhost:1234/v1", "http://192.0.2.1:1234/v1", "https://127.0.0.1:1234/v1",
                         "http://user:secret@127.0.0.1:1234/v1", "http://127.0.0.1:1234/v1?key=secret",
                         "http://[::1%lo0]:1234/v1"):
            with self.subTest(endpoint=endpoint), self.assertRaises(ValueError):
                run_ai_review(self.profile(endpoint=endpoint), self.root)
        self.assertEqual(self.requests, [])

    def test_proxy_environment_does_not_redirect_local_model_request(self):
        with patch.dict("os.environ", {"HTTP_PROXY": "http://192.0.2.1:1", "ALL_PROXY": "http://192.0.2.1:1", "NO_PROXY": ""}):
            self.assertTrue(run_ai_review(self.profile(), self.root)["complete"])
        self.assertEqual(len(self.requests), 1)

    def test_input_report_path_and_locations_must_stay_in_their_trust_boundaries(self):
        inside = self.root / "reviewed.json"
        inside.write_text(self.report.read_text(), encoding="utf-8")
        with self.assertRaises(ValueError):
            run_ai_review(self.profile(input_report_path=inside), self.root)
        self.records[0]["location"]["path"] = "../../outside.py"
        self.save()
        with self.assertRaises(ValueError):
            run_ai_review(self.profile(), self.root)
        outside = self.user / "outside.py"
        outside.write_text("secret", encoding="utf-8")
        (self.root / "linked.py").symlink_to(outside)
        self.records[0]["location"]["path"] = "linked.py"
        self.save()
        with self.assertRaises(ValueError):
            run_ai_review(self.profile(), self.root)
        self.assertEqual(self.requests, [])

    def test_empty_or_malformed_input_is_explicit_and_never_sent(self):
        self.records = []
        self.save()
        result = run_ai_review(self.profile(), self.root)
        self.assertFalse(result["complete"])
        self.assertEqual(result["findings"], [])
        self.assertTrue(any(n["code"] == "WG-AI-NO-INPUT" for n in result["notices"]))
        self.report.write_text('{"schemaVersion":1,"findings":"not-an-array"}', encoding="utf-8")
        with self.assertRaises(ValueError):
            run_ai_review(self.profile(), self.root)
        self.assertEqual(self.requests, [])

    def test_five_finding_limit_is_explicit_and_does_not_silently_review_extra_records(self):
        self.records = self.records * 6
        self.save()
        self.response_reviews *= 5
        result = run_ai_review(self.profile(), self.root)
        observations = json.loads(self.requests[0][2]["messages"][1]["content"])["observations"]
        self.assertEqual(len(observations), 5)
        self.assertEqual(len(result["findings"]), 5)
        self.assertFalse(result["complete"])
        self.assertTrue(any(n["code"] == "WG-AI-LIMIT" for n in result["notices"]))

    def test_failed_truncated_malformed_or_oversized_model_response_has_no_partial_result(self):
        for body in (b"not-json", b'{"choices":[]}', json.dumps({"choices": [{"finish_reason": "length", "message": {"content": "{}"}}]}).encode(),
                     json.dumps({"choices": [{"message": {"content": '{"reviews":[]}'}}]}).encode(), b" " * 1025):
            self.custom_body = body
            with self.subTest(body=body[:80]), self.assertRaisesRegex(ValueError, "Local AI review could not complete"):
                run_ai_review(self.profile(max_output_bytes=1024), self.root)

    def test_total_deadline_interrupts_slowly_trickled_response_headers(self):
        self.slow_headers = True
        started = time.monotonic()
        with self.assertRaisesRegex(ValueError, "Local AI review could not complete"):
            run_ai_review(self.profile(timeout_ms=100), self.root)
        self.assertLess(time.monotonic() - started, 0.4)

    def test_total_response_timeout_is_bounded_and_generic(self):
        self.delay = 0.5
        started = time.monotonic()
        with self.assertRaisesRegex(ValueError, "Local AI review could not complete"):
            run_ai_review(self.profile(timeout_ms=100), self.root)
        self.assertLess(time.monotonic() - started, 0.4)


if __name__ == "__main__":
    unittest.main()
