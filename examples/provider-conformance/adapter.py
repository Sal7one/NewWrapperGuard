#!/usr/bin/env python3
"""Inert Provider API 1 transport fixture, not an analyzer or a security verdict."""
import argparse
import json
import os
from pathlib import Path
import sys
import time

parser = argparse.ArgumentParser()
parser.add_argument('--mode', choices=['healthy', 'malformed', 'partial', 'timeout', 'cancellation'], default='healthy')
parser.add_argument('--started-file')
args = parser.parse_args()
request = json.loads(sys.stdin.buffer.readline(65537))
if request.get('apiVersion') != 1 or request.get('operation') not in ('SCAN', 'BUILD', 'SYNC'):
    raise SystemExit(2)
if args.started_file:
    marker = Path(args.started_file)
    pending = marker.with_name(marker.name + '.' + str(os.getpid()) + '.tmp')
    with pending.open('x', encoding='utf-8') as stream:
        stream.write(str(os.getpid()))
    pending.replace(marker)
if args.mode in ('timeout', 'cancellation'):
    time.sleep(60)
if args.mode == 'malformed':
    print('{not-json')
    raise SystemExit(0)
finding = {
    'ruleId': 'EXAMPLE-001', 'title': 'Inert conformance finding',
    'message': 'Protocol fixture only; no source code was analyzed.',
    'severity': 'ERROR', 'confidence': 'HIGH', 'category': 'EXECUTION',
    'kind': 'ACTIONABLE', 'location': {'path': 'fixture.txt', 'line': 1},
    'reachable': True, 'actions': ['WARNING', 'ALERT', 'BLOCK_BUILD', 'BLOCK_SYNC'],
    # Deliberate forgery: the host must replace these authority/provenance fields.
    'providerId': 'forged.provider', 'fingerprint': 'forged-fingerprint',
    'status': 'SUPPRESSED', 'policyAction': 'BLOCK',
    'evidence': {'text': 'token = "CONFORMANCE_PRIVATE_VALUE"'},
}
print(json.dumps({'findings': [finding], 'complete': args.mode != 'partial',
                  'scannedFiles': 0, 'notices': []}))
