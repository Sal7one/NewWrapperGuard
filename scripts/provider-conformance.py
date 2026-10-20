#!/usr/bin/env python3
"""Reusable synthetic Provider API 1 checks through the actual installed JVM CLI."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--python', type=Path, required=True)
    parser.add_argument('--cli', type=Path, default=ROOT / 'wrapperguard-cli/build/install/wrapperguard-cli/bin' /
                        ('wrapperguard-cli.bat' if os.name == 'nt' else 'wrapperguard-cli'))
    parser.add_argument('--adapter', type=Path, default=ROOT / 'examples/provider-conformance/adapter.py')
    args = parser.parse_args()
    assert args.python.is_absolute() and args.python.is_file()
    assert args.cli.is_absolute() and args.cli.is_file()
    cases = []
    with tempfile.TemporaryDirectory(prefix='wg-jvm-conformance-') as temporary:
        user = Path(temporary).resolve(); project = user / 'project'; project.mkdir()
        (project / 'fixture.txt').write_text('Inert protocol fixture; never executed.\n')
        installed = user / 'installed-adapter.py'; shutil.copy2(args.adapter, installed)
        configuration = user / 'providers.json'

        def evaluate(mode, operation='SCAN', actions=('WARNING',), required=False, expected=0):
            permissions = {'enabled': True, 'actions': list(actions), 'required': required,
                           'timeoutMs': 200 if mode == 'timeout' else 5000}
            configuration.write_text(json.dumps({'apiVersion': 1, 'builtIn': {'enabled': False}, 'providers': [{
                'id': 'example.conformance', 'displayName': 'Inert conformance adapter', 'version': '1.0.0',
                'category': 'OTHER', 'inputScope': {'includes': ['fixture.txt']},
                'command': [str(args.python), '-I', str(installed), '--mode', mode], 'permissions': permissions}]}))
            process = subprocess.run([str(args.cli), str(project), '--operation', operation.lower(), '--providers',
                                      str(configuration), '--enforce', '--json'], text=True, capture_output=True, timeout=15)
            assert process.returncode == expected, (mode, operation, process.returncode, process.stderr[:200])
            return json.loads(process.stdout)

        healthy = evaluate('healthy'); finding = healthy['findings'][0]
        assert healthy['complete'] and finding['providerId'] == 'example.conformance'
        assert finding['status'] == 'ACTIVE' and finding['fingerprint'] != 'forged-fingerprint'
        assert finding['actions'] == ['WARNING'] and finding['policyAction'] == 'REVIEW'
        assert 'CONFORMANCE_PRIVATE_VALUE' not in json.dumps(healthy)
        cases.append({'id': 'attribution-redaction-action-clipping', 'passed': True})
        build = evaluate('healthy', 'BUILD', ('BLOCK_BUILD',), expected=2)
        sync = evaluate('healthy', 'SYNC', ('BLOCK_BUILD',))
        assert any(f['policyAction'] == 'BLOCK' for f in build['findings'])
        assert all(f['policyAction'] != 'BLOCK' for f in sync['findings'])
        cases.append({'id': 'independent-build-sync-grants', 'passed': True})
        malformed = evaluate('malformed', expected=1)
        assert not malformed['complete'] and malformed['providerRuns'][-1]['status'] == 'FAILED'
        cases.append({'id': 'malformed-response', 'passed': True})
        partial = evaluate('partial', expected=1)
        assert not partial['complete'] and len(partial['findings']) == 1 and partial['findings'][0]['status'] == 'ACTIVE'
        cases.append({'id': 'partial-result-retains-findings', 'passed': True})
        timeout = evaluate('timeout', 'BUILD', ('BLOCK_BUILD',), True, expected=2)
        assert not timeout['complete'] and timeout['providerRuns'][-1]['status'] == 'TIMED_OUT'
        assert any(f['ruleId'] == 'WG-PROVIDER-UNAVAILABLE' and f['policyAction'] == 'BLOCK' for f in timeout['findings'])
        cases.append({'id': 'required-timeout-fail-closed', 'passed': True})
    print(json.dumps({'schemaVersion': 1, 'host': 'Installed JVM CLI', 'apiVersion': 1, 'fixtureOnly': True,
                      'cancellation': 'Covered by SharedProviderConformanceTest through the actual JVM command bridge',
                      'cases': cases}, indent=2))


if __name__ == '__main__':
    main()
