from dataclasses import replace
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import socket
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

from wrapperguard_sdk.cli import evaluate, build_plan
from wrapperguard_sdk.http_audit import run_http_audit
from wrapperguard_sdk.profile import load_profile


class Handler(BaseHTTPRequestHandler):
    calls = []
    def log_message(self, *args): pass
    def do_GET(self):
        self.server.calls.append((self.path, self.headers.get('Authorization')))
        if self.path.endswith('/trickle'):
            try:
                for value in b'HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: 0\r\n\r\n':
                    self.connection.sendall(bytes([value])); time.sleep(.025)
            except OSError: pass
            return
        self.send_response(302 if self.path.endswith('/redirect') else 200)
        if self.path.endswith('/redirect'): self.send_header('Location', self.server.redirect)
        self.send_header('Content-Type', 'text/html')
        if self.path.endswith('/secure'):
            self.send_header('X-Content-Type-Options', 'nosniff')
            self.send_header('Content-Security-Policy', "default-src 'self'; frame-ancestors 'none'")
            self.send_header('Set-Cookie', 'privateValue=do-not-leak; Secure; HttpOnly; SameSite=Strict')
        else:
            self.send_header('Set-Cookie', 'session=private-cookie-value')
        if self.path.endswith('/hugeheader'): self.send_header('X-Untrusted', 'x' * 40000)
        body = b'PRIVATE BODY do-not-disclose' * (100 if self.path.endswith('/large') else 1)
        self.send_header('Content-Length', str(len(body))); self.end_headers()
        try: self.wfile.write(body)
        except OSError: pass


class HttpAuditTests(unittest.TestCase):
    def setUp(self):
        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler); self.server.daemon_threads = True
        self.server.calls = []; self.server.redirect = 'http://127.0.0.1:1/private'
        thread = threading.Thread(target=self.server.serve_forever, daemon=True); thread.start()
        self.addCleanup(self.server.server_close); self.addCleanup(self.server.shutdown)
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.user = Path(self.temp.name).resolve(); self.root = self.user / 'project'; self.root.mkdir()
        self.origin = 'http://127.0.0.1:' + str(self.server.server_port)
        self.value = {'id': 'dast.owned-http', 'displayName': 'Owned target', 'version': '1', 'analysisKind': 'DAST',
            'format': 'http-audit', 'targetScope': {'origins': [self.origin], 'pathPrefixes': ['/acceptance']},
            'runner': {'tool': 'http-audit', 'ownershipConfirmed': True,
                       'targetUrls': [self.origin + '/acceptance/secure', self.origin + '/acceptance/weak']},
            'allowedOperations': ['SCAN'], 'requestedActions': ['WARNING']}

    def load(self):
        path = self.user / 'profile.json'; path.write_text(json.dumps(self.value)); return load_profile(path, self.root)

    def test_only_requested_targets_are_contacted_and_safe_headers_do_not_produce_findings(self):
        result = evaluate(self.load(), self.root, 'SCAN')
        self.assertTrue(result['complete']); self.assertEqual(2, len(self.server.calls))
        ids = {f['ruleId'].split('/')[0] for f in result['findings']}
        self.assertEqual({'HTTPSEC-NOSNIFF', 'HTTPSEC-CSP', 'HTTPSEC-FRAME', 'HTTPSEC-COOKIE'}, ids)
        self.assertTrue(all('target 2:' in f['message'] for f in result['findings']))
        self.assertTrue(all(f['actions'] == ['WARNING'] and f['location'] == {'path': '.'} for f in result['findings']))
        self.assertNotIn('PRIVATE BODY', json.dumps(result)); self.assertNotIn('private-cookie-value', json.dumps(result))

    def test_redirect_inside_or_outside_scope_is_never_followed_even_with_auth(self):
        self.value['runner']['targetUrls'] = [self.origin + '/acceptance/redirect']
        self.value['runner']['authHeadersEnv'] = {'Authorization': 'WG_TEST_HTTP_AUTH'}
        with patch.dict(os.environ, {'WG_TEST_HTTP_AUTH': 'Bearer private-auth'}), patch.dict(os.environ, {'HTTP_PROXY': 'http://127.0.0.1:1'}):
            for target in (self.origin + '/acceptance/secure', 'http://127.0.0.1:1/not-owned'):
                self.server.calls.clear(); self.server.redirect = target
                result = evaluate(self.load(), self.root, 'SCAN')
                self.assertFalse(result['complete']); self.assertEqual([('/acceptance/redirect', 'Bearer private-auth')], self.server.calls)
                self.assertIn('WG-DAST-REDIRECT-SKIPPED', [n['code'] for n in result['notices']])
                self.assertNotIn('private-auth', json.dumps(result))

    def test_ownership_scope_numeric_host_and_request_budgets_are_rejected_before_contact(self):
        for change in ({'ownershipConfirmed': False}, {'targetUrls': [self.origin + '/outside']},
                       {'targetUrls': [self.origin + '/acceptance/../outside']}, {'maxRequests': 1},
                       {'requestTimeoutMs': True}, {'authHeadersEnv': {'Host': 'SECRET'}}):
            original = self.value['runner'].copy(); self.value['runner'].update(change)
            with self.subTest(change=change), self.assertRaises(ValueError): self.load()
            self.value['runner'] = original
        self.value['targetScope']['origins'] = ['http://localhost:1234']
        self.value['runner']['targetUrls'] = ['http://localhost:1234/acceptance']
        with self.assertRaises(ValueError): self.load()
        self.assertFalse(self.server.calls)

    def test_headers_body_and_trickle_deadline_limits_are_incomplete_and_bounded(self):
        for suffix in ('hugeheader', 'large', 'trickle'):
            self.value['runner'].update({'targetUrls': [self.origin + '/acceptance/' + suffix], 'maxResponseBytes': 1024,
                                         'requestTimeoutMs': 200, 'timeoutMs': 300})
            started = time.monotonic(); result = evaluate(self.load(), self.root, 'SCAN')
            self.assertFalse(result['complete']); self.assertLess(time.monotonic() - started, 1)
            self.assertIn('WG-DAST-TARGET-INCOMPLETE', [n['code'] for n in result['notices']])

    def test_plan_disallowed_operations_missing_auth_and_cancellation_send_no_requests(self):
        profile = self.load()
        with patch('socket.socket', side_effect=AssertionError('no networking')):
            plan = build_plan(profile, self.root); self.assertFalse(plan['followsRedirects']); self.assertFalse(plan['startsTarget'])
            with self.assertRaises(ValueError): evaluate(profile, self.root, 'BUILD')
            with self.assertRaises(InterruptedError): run_http_audit(profile, self.root, cancelled=lambda: True)
        self.value['runner']['authHeadersEnv'] = {'Authorization': 'WG_MISSING_HTTP_AUTH'}
        with patch.dict(os.environ, {}, clear=True), self.assertRaises(ValueError): evaluate(self.load(), self.root, 'SCAN')
        self.assertFalse(self.server.calls)

    def test_runtime_rechecks_scope_and_ownership_for_manually_created_profiles(self):
        profile = self.load()
        for runner in (replace(profile.runner, ownership_confirmed=False),
                       replace(profile.runner, target_urls=('http://127.0.0.1:1/private',))):
            with self.assertRaises(ValueError): run_http_audit(replace(profile, runner=runner), self.root)
        self.assertFalse(self.server.calls)

    def test_cancellation_interrupts_a_live_trickling_response_and_stops_budget_watcher(self):
        self.value['runner']['targetUrls'] = [self.origin + '/acceptance/trickle']
        started = time.monotonic()
        with self.assertRaises(InterruptedError):
            run_http_audit(self.load(), self.root, cancelled=lambda: time.monotonic()-started > .15)
        self.assertLess(time.monotonic()-started, 1)
        self.assertFalse(any(t.is_alive() and t.name == 'wrapperguard-http-budget' for t in threading.enumerate()))


if __name__ == '__main__': unittest.main()
