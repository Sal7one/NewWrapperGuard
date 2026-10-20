#!/usr/bin/env python3
"""Run an explicitly installed scanner through the installed SDK on inert data.

PowerShell performs static parsing only. Trivy/OSV use a tiny npm lockfile and may
contact their documented database/services; they never install or execute a package.
"""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import subprocess
import tempfile
import time


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk-python', required=True, type=Path)
    parser.add_argument('--tool', required=True, choices=['psscriptanalyzer', 'trivy', 'osv'])
    parser.add_argument('--executable', required=True, type=Path)
    parser.add_argument('--module', type=Path, help='Reviewed PSScriptAnalyzer manifest')
    parser.add_argument('--cache', type=Path, help='Reusable Trivy database cache outside projects')
    args = parser.parse_args()
    assert args.sdk_python.is_absolute() and args.executable.is_absolute()
    with tempfile.TemporaryDirectory(prefix='wg-real-tool-') as temporary:
        user = Path(temporary).resolve(); root = user / 'project'; root.mkdir()
        runner = {'tool': args.tool, 'executable': str(args.executable), 'timeoutMs': 300000}
        if args.tool == 'psscriptanalyzer':
            assert args.module and args.module.is_absolute()
            runner['modulePath'] = str(args.module)
            (root / 'review.ps1').write_text("# STATIC ANALYZER INPUT ONLY. Never execute.\nif ($false) { Invoke-Expression 'Write-Output example' }\n", encoding='utf-8')
        else:
            package = {'name': 'wrapperguard-inert-test', 'version': '1.0.0', 'lockfileVersion': 2,
                       'requires': True, 'packages': {'': {'name': 'wrapperguard-inert-test', 'version': '1.0.0', 'dependencies': {'lodash': '4.17.11'}},
                       'node_modules/lodash': {'version': '4.17.11', 'resolved': 'https://registry.npmjs.org/lodash/-/lodash-4.17.11.tgz'}},
                       'dependencies': {'lodash': {'version': '4.17.11', 'resolved': 'https://registry.npmjs.org/lodash/-/lodash-4.17.11.tgz'}}}
            (root / 'package-lock.json').write_text(json.dumps(package), encoding='utf-8')
            if args.tool == 'trivy' and args.cache:
                assert args.cache.is_absolute(); runner['cachePath'] = str(args.cache)
        profile = user / 'profile.json'
        profile.write_text(json.dumps({'id': 'test.real-' + args.tool, 'displayName': 'Real scanner acceptance', 'version': '1',
                           'analysisKind': 'SAST' if args.tool == 'psscriptanalyzer' else 'SCA', 'format': args.tool,
                           'runner': runner, 'allowedOperations': ['SCAN'], 'requestedActions': ['WARNING']}), encoding='utf-8')
        request = json.dumps({'apiVersion': 1, 'projectPath': str(root), 'operation': 'SCAN'}) + '\n'
        started = time.monotonic()
        process = subprocess.run([str(args.sdk_python), '-I', '-m', 'wrapperguard_sdk', '--profile', str(profile)],
                                 input=request, text=True, capture_output=True, timeout=310)
        assert process.returncode == 0, 'Installed SDK/tool failed: ' + process.stderr[:500]
        result = json.loads(process.stdout)
        assert result['complete'], result.get('notices')
        assert result['findings'], 'Expected the known static fixture finding, not an empty report'
        assert all(set(f.get('actions', [])) <= {'WARNING'} for f in result['findings'])
        if args.tool == 'psscriptanalyzer':
            assert any('AvoidUsingInvokeExpression' in f['ruleId'] for f in result['findings'])
        else:
            assert any('lodash' in (f['title'] + f['message']).lower() for f in result['findings'])
        assert all(not f['location']['path'].startswith(str(user)) for f in result['findings'])
        print(json.dumps({'tool': args.tool, 'findings': len(result['findings']), 'complete': True,
                          'durationSeconds': round(time.monotonic() - started, 2), 'projectProgramsExecuted': False}))


if __name__ == '__main__':
    main()
