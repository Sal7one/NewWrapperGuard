#!/usr/bin/env python3
"""Exercise an explicitly configured real loopback model through the installed SDK.

No model/server is downloaded or started. This checks interoperability and the advisory
boundary; it does not measure model quality or security detection accuracy.
"""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk-python', required=True)
    parser.add_argument('--endpoint', required=True, help='Explicit numeric-loopback HTTP /v1 address')
    parser.add_argument('--model', required=True)
    parser.add_argument('--response-format', choices=['json_schema', 'json_object'], default='json_schema')
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='wg-real-ai-') as temporary:
        user = Path(temporary).resolve()
        root = user / 'project'
        root.mkdir()
        (root / 'build.gradle.kts').write_text('// inert fixture\n')
        report = user / 'report.json'
        report.write_text(json.dumps({'schemaVersion': 1, 'findings': [{
            'ruleId': 'WG-WRAPPER-002', 'title': 'Missing distribution checksum',
            'message': 'Verify the Gradle distribution checksum before using a wrapper distribution.',
            'severity': 'WARN', 'location': {'path': 'build.gradle.kts', 'line': 1}}]}))
        profile = user / 'profile.json'
        profile.write_text(json.dumps({'id': 'ai.real-model-test', 'displayName': 'Real local model',
            'version': '1', 'analysisKind': 'AI', 'format': 'ai-review',
            'runner': {'tool': 'local-ai', 'endpoint': args.endpoint, 'model': args.model,
                       'inputReportPath': str(report), 'timeoutMs': 120000, 'responseFormat': args.response_format},
            'allowedOperations': ['SCAN'], 'requestedActions': []}))
        start = time.monotonic()
        process = subprocess.run([args.sdk_python, '-I', '-m', 'wrapperguard_sdk', '--profile', str(profile)],
            input=json.dumps({'apiVersion': 1, 'projectPath': str(root), 'operation': 'SCAN'}),
            text=True, capture_output=True, timeout=130)
        if process.returncode:
            raise AssertionError('SDK / model did not complete: ' + process.stderr)
        result = json.loads(process.stdout)
        assert result['complete'] and len(result['findings']) == 1, 'Incomplete review'
        finding = result['findings'][0]
        assert finding['severity'] == 'INFO' and finding['kind'] == 'AUDIT_EVIDENCE' and not finding['actions']
        assert finding['location']['path'] == 'build.gradle.kts', 'Model must not choose source locations'
        print(json.dumps({'model': args.model, 'responseFormat': args.response_format,
            'durationSeconds': round(time.monotonic() - start, 2),
            'complete': True, 'advisoryOnly': True, 'messageLength': len(finding['message'])}))


if __name__ == '__main__': main()
