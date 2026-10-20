#!/usr/bin/env python3
"""Build the first-party, zero-runtime-dependency wheel bundled by both GUI hosts."""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def build_python():
    candidates = [os.environ.get('WRAPPERGUARD_BUILD_PYTHON'), sys.executable]
    candidates += [shutil.which(name) for name in ['python3.12', 'python3.14', 'python3.13', 'python3.11', 'python3.10', 'python3']]
    for candidate in dict.fromkeys(value for value in candidates if value):
        check = subprocess.run([candidate, '-I', '-c',
                                'import sys,pip;raise SystemExit(0 if sys.version_info >= (3,10) else 1)'],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if check.returncode == 0:
            return candidate
    raise RuntimeError('Building the bundled SDK requires Python 3.10+ with pip. Set WRAPPERGUARD_BUILD_PYTHON to that interpreter.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--copy-vscode', action='store_true')
    options = parser.parse_args()
    version = re.search(r'^version = "([0-9.]+)"$', (ROOT / 'sdk/python/pyproject.toml').read_text(), re.M).group(1)
    output = ROOT / 'build/sdk'
    output.mkdir(parents=True, exist_ok=True)
    wheel = output / ('wrapperguard_sdk-' + version + '-py3-none-any.whl')
    # Stage clean sources so a removed module or an older build cannot enter the GUI.
    # A unique output folder also makes UNKNOWN/misnamed archives fail immediately.
    with tempfile.TemporaryDirectory(prefix='wrapperguard-wheel-') as temporary:
        stage = Path(temporary)
        source = stage / 'source'
        shutil.copytree(ROOT / 'sdk/python', source,
                        ignore=shutil.ignore_patterns('__pycache__', '*.egg-info', 'build', 'dist', '.venv'))
        subprocess.run([build_python(), '-I', '-m', 'pip', 'wheel', '--disable-pip-version-check', '--no-deps',
                        '--wheel-dir', str(stage), str(source)], check=True)
        built = stage / wheel.name
        with zipfile.ZipFile(built) as archive:
            metadata = archive.read('wrapperguard_sdk-' + version + '.dist-info/METADATA').decode()
            if ('Requires-Dist:' in metadata or 'Requires-Python: >=3.10' not in metadata
                    or '\nName: wrapperguard-sdk\n' not in metadata or '\nVersion: ' + version + '\n' not in metadata):
                raise RuntimeError('Unexpected bundled SDK dependency/runtime contract')
            for module in ('ai_review.py', 'semantics.py', 'runner.py'):
                if archive.read('wrapperguard_sdk/' + module) != (source / 'src/wrapperguard_sdk' / module).read_bytes():
                    raise RuntimeError('Bundled SDK source mismatch')
        shutil.copyfile(built, wheel)
    if options.copy_vscode:
        target = ROOT / 'vscode-extension/resources/sdk'
        target.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(wheel, target / wheel.name)
    print('Bundled SDK wheel: ' + str(wheel))


if __name__ == '__main__':
    main()
