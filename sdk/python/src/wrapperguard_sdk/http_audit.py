"""Explicitly owned, fixed-URL HTTP response audit; no crawler or exploit engine.

Only GET is sent to reviewed numeric addresses. There are no proxy lookups, DNS,
redirects, target startup, browser execution, content execution or attack payloads.
TLS certificates are verified. Response bodies and authentication values are never
returned. This intentionally limited audit does not replace application testing.
"""
from __future__ import annotations

import hashlib
import http.client
import ipaddress
import os
import re
import socket
import ssl
import threading
import time
from urllib.parse import urlsplit

from .models import make_finding


class _HeaderBudget:
    def __init__(self, stream): self.stream, self.total = stream, 0
    def readline(self, limit=-1):
        selected = min(8193, 32769 - self.total, limit if limit >= 0 else 8193)
        if selected <= 0: raise ValueError("HTTP headers exceed limit")
        data = self.stream.readline(selected); self.total += len(data)
        if len(data) > 8192 or self.total > 32768: raise ValueError("HTTP headers exceed limit")
        return data
    def __getattr__(self, name): return getattr(self.stream, name)


class _Response(http.client.HTTPResponse):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs); self.fp = _HeaderBudget(self.fp)


def _exchange(url, headers, deadline, maximum, cancelled):
    parsed = urlsplit(url); address = ipaddress.ip_address(parsed.hostname or '')
    connection = http.client.HTTPConnection(parsed.hostname, parsed.port or (443 if parsed.scheme == 'https' else 80))
    connection.response_class = _Response
    connection.sock = socket.socket(socket.AF_INET6 if address.version == 6 else socket.AF_INET, socket.SOCK_STREAM)
    stop = threading.Event(); expired = threading.Event(); active_socket = [connection.sock]
    def check():
        if cancelled is not None and cancelled(): raise InterruptedError("HTTP audit cancelled")
        if expired.is_set() or time.monotonic() >= deadline: raise TimeoutError("HTTP audit deadline exceeded")
    def watch():
        while not stop.wait(.02):
            if time.monotonic() >= deadline or cancelled is not None and cancelled():
                expired.set()
                try: active_socket[0].shutdown(socket.SHUT_RDWR)
                except OSError: pass
                return
    watcher = threading.Thread(target=watch, daemon=True, name='wrapperguard-http-budget'); watcher.start()
    try:
        check(); connection.sock.settimeout(max(.001, deadline - time.monotonic()))
        connection.sock.connect((str(address), connection.port))
        if parsed.scheme == 'https':
            connection.sock = ssl.create_default_context().wrap_socket(connection.sock,
                server_hostname=parsed.hostname, do_handshake_on_connect=False)
            active_socket[0] = connection.sock
            connection.sock.do_handshake()
        check()
        path = parsed.path or '/'
        if parsed.query: path += '?' + parsed.query
        connection.request('GET', path, headers={'User-Agent': 'WrapperGuard-owned-target-audit/1',
            'Accept': '*/*', 'Accept-Encoding': 'identity', 'Connection': 'close', **headers})
        response = connection.getresponse()
        length = response.getheader('Content-Length')
        if length is not None and (not length.isdigit() or int(length) > maximum):
            raise ValueError('HTTP response exceeds limit')
        received = 0
        while True:
            check(); data = response.read1(min(65536, maximum - received + 1))
            if not data: break
            received += len(data)
            if received > maximum: raise ValueError('HTTP response exceeds limit')
        if length is not None and received != int(length): raise ValueError('HTTP response truncated')
        check()
        return response.status, response.headers
    except (OSError, http.client.HTTPException, ValueError):
        check()
        raise ValueError('Owned HTTP target audit could not complete') from None
    finally:
        stop.set(); connection.close(); watcher.join(timeout=.2)


def run_http_audit(profile, project_root, *, cancelled=None):
    config = profile.runner; scope = profile.target_scope
    if (config is None or config.tool != 'http-audit' or config.ownership_confirmed is not True or
            scope is None or not isinstance(config.target_urls, tuple) or not 1 <= len(config.target_urls) <= config.max_requests <= 32 or
            type(config.timeout_ms) is not int or not 100 <= config.timeout_ms <= 300000 or
            type(config.request_timeout_ms) is not int or not 100 <= config.request_timeout_ms <= 10000 or
            type(config.max_response_bytes) is not int or not 1024 <= config.max_response_bytes <= 1048576):
        raise ValueError('HTTP audit requires explicit ownership, scope and bounded targets')
    for url in config.target_urls:
        if not isinstance(url, str) or not scope.allows(url) or not url.isascii(): raise ValueError('HTTP target outside reviewed scope')
        ipaddress.ip_address(urlsplit(url).hostname or '')
    headers = {}
    for name, variable in config.auth_headers_env:
        if (not re.fullmatch(r'[A-Za-z][A-Za-z0-9-]{0,63}', name) or
                name.lower() in {'host', 'content-length', 'transfer-encoding', 'connection', 'accept-encoding', 'proxy-authorization'} or
                not re.fullmatch(r'[A-Z_][A-Z0-9_]{0,127}', variable)):
            raise ValueError('Invalid HTTP authentication reference')
        value = os.environ.get(variable)
        if not value or len(value) > 8192 or any(ord(c) < 32 or ord(c) > 126 for c in value):
            raise ValueError('HTTP authentication reference is unavailable or invalid')
        headers[name] = value
    if sum(len(name)+len(value) for name, value in headers.items()) > 16384:
        raise ValueError('HTTP authentication headers exceed limit')
    deadline = time.monotonic() + config.timeout_ms / 1000
    findings, notices = [], []; complete = True; visited = 0
    def add(code, title, detail, index, url):
        identifier = code + '/' + hashlib.sha256(url.encode('ascii')).hexdigest()[:24]
        findings.append(make_finding(identifier, title, f'Configured target {index}: {detail}',
            'WARN', 'HIGH', 'NETWORK', '.', actions=list(profile.requested_actions),
            remediation='Review this response setting for the explicitly owned endpoint and test application behavior before changing policy.'))
    for index, url in enumerate(config.target_urls, 1):
        if cancelled is not None and cancelled(): raise InterruptedError('HTTP audit cancelled')
        if time.monotonic() >= deadline: raise TimeoutError('HTTP audit deadline exceeded')
        try:
            status, response = _exchange(url, headers, min(deadline, time.monotonic()+config.request_timeout_ms/1000), config.max_response_bytes, cancelled)
            visited += 1
        except InterruptedError: raise
        except (ValueError, OSError, TimeoutError):
            complete = False
            notices.append({'code': 'WG-DAST-TARGET-INCOMPLETE', 'message': f'Configured target {index} could not complete within the response, connection or time budget.'})
            continue
        if 300 <= status < 400:
            complete = False
            notices.append({'code': 'WG-DAST-REDIRECT-SKIPPED', 'message': f'Configured target {index} redirected; no redirect was followed, including within scope.'})
            continue
        if status >= 400:
            complete = False
            notices.append({'code': 'WG-DAST-STATUS-INCOMPLETE', 'message': f'Configured target {index} returned HTTP {status}; authenticated application coverage is incomplete.'})
            continue
        html = 'text/html' in response.get('Content-Type', '').lower()
        if response.get('X-Content-Type-Options', '').strip().lower() != 'nosniff':
            add('HTTPSEC-NOSNIFF', 'Response lacks MIME sniffing protection', 'X-Content-Type-Options is not nosniff.', index, url)
        if html and not response.get('Content-Security-Policy', '').strip():
            add('HTTPSEC-CSP', 'HTML response lacks a content security policy', 'No Content-Security-Policy response header was observed.', index, url)
        if html and response.get('X-Frame-Options', '').strip().upper() not in {'DENY', 'SAMEORIGIN'} and 'frame-ancestors' not in response.get('Content-Security-Policy', '').lower():
            add('HTTPSEC-FRAME', 'HTML response lacks framing protection', 'Neither X-Frame-Options nor CSP frame-ancestors was observed.', index, url)
        hsts = re.search(r'(?:^|;)\s*max-age\s*=\s*(\d+)(?:\s*;|\s*$)', response.get('Strict-Transport-Security', ''), re.I)
        if urlsplit(url).scheme == 'https' and (hsts is None or int(hsts[1]) == 0):
            add('HTTPSEC-HSTS', 'HTTPS response lacks transport persistence', 'No Strict-Transport-Security response header was observed.', index, url)
        for cookie in response.get_all('Set-Cookie', []):
            # Cookie values and names never enter findings or log output.
            attributes = {part.strip().split('=', 1)[0].lower(): part.partition('=')[2].strip().lower() for part in cookie.split(';')[1:]}
            if not {'secure', 'httponly'} <= attributes.keys() or attributes.get('samesite') not in {'strict', 'lax', 'none'}:
                add('HTTPSEC-COOKIE', 'Response cookie lacks defensive attributes', 'A Set-Cookie header lacks Secure, HttpOnly or SameSite.', index, url)
                break
    notices.append({'code': 'WG-DAST-LIMITED-SCOPE', 'message':
        f'Completed responses from {visited} explicitly owned configured targets. GET response headers/cookie attributes only; no crawling, redirects, exploit payloads, browser execution or target startup. This does not establish full application security.'})
    return {'findings': findings, 'scannedFiles': 0, 'complete': complete, 'notices': notices}
