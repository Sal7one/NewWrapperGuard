#!/usr/bin/env python3
"""Real Windows fixed-tool acceptance through the installed SDK, using inert data."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tool', required=True, choices=['bandit', 'semgrep'])
    parser.add_argument('--executable', required=True, type=Path)
    args = parser.parse_args()
    if args.tool == 'semgrep':
        subprocess.run([sys.executable, str(Path(__file__).with_name('sdk-semgrep-smoke.py')),
            '--sdk-python', sys.executable, '--semgrep', str(args.executable)], check=True, timeout=90)
        return
    with tempfile.TemporaryDirectory(prefix='wg-real-windows-') as temporary:
        user = Path(temporary).resolve(); root = user / 'project'; root.mkdir()
        (root / 'review.py').write_text('import subprocess\nif False:\n    subprocess.run("echo inert", shell=True) # nosec\n')
        (root / '.bandit').write_text('[bandit]\nskips=B602,B607\n')
        profile = user / 'profile.json'
        profile.write_text(json.dumps({'id': 'test.windows-bandit', 'displayName': 'Windows Bandit acceptance',
            'version': '1', 'analysisKind': 'SAST', 'format': 'bandit',
            'runner': {'tool': 'bandit', 'executable': str(args.executable), 'timeoutMs': 30000},
            'requestedActions': ['WARNING'], 'allowedOperations': ['SCAN']}))
        process = subprocess.run([sys.executable, '-I', '-m', 'wrapperguard_sdk', '--profile', str(profile)],
            input=json.dumps({'apiVersion': 1, 'projectPath': str(root), 'operation': 'SCAN'}),
            text=True, capture_output=True, timeout=40)
        assert process.returncode == 0, process.stderr
        result = json.loads(process.stdout)
        assert result['complete'], result.get('notices')
        assert any(f['ruleId'] in ('B602', 'B607') and f['location']['path'] == 'review.py' for f in result['findings']), result
        assert all(f['actions'] == ['WARNING'] for f in result['findings'])
        print('Real Windows Bandit: fixed argv, inert snapshot, suppression isolation and source locations passed.')


if __name__ == '__main__': main()
