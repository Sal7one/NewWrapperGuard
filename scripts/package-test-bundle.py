#!/usr/bin/env python3
"""Assemble a reviewable acceptance bundle from current built artifacts and clean source."""
import argparse
import hashlib
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--output', type=Path, required=True, help='New absolute output directory')
    args = parser.parse_args()
    version = re.search(r'^\s*version\s*=\s*"([^"]+)"', (ROOT / 'build.gradle.kts').read_text(), re.M).group(1)
    sdk = re.search(r'^version = "([^"]+)"', (ROOT / 'sdk/python/pyproject.toml').read_text(), re.M).group(1)
    if not re.fullmatch(r'v' + re.escape(version) + r'(?:-rc\.[1-9][0-9]*)?', args.tag):
        parser.error('Tag must match the current app version (optionally -rc.N)')
    if not args.output.is_absolute() or args.output.exists():
        parser.error('Output must be a new absolute directory')
    dirty = subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT, text=True)
    if dirty.strip():
        parser.error('Commit source changes before producing a release acceptance bundle')
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    files = [ROOT / f'wrapperguard-intellij/build/distributions/wrapperguard-intellij-{version}.zip',
             ROOT / f'vscode-extension/wrapperguard-{version}.vsix',
             ROOT / f'wrapperguard-cli/build/distributions/wrapperguard-cli-{version}.zip',
             ROOT / f'build/sdk/wrapperguard_sdk-{sdk}-py3-none-any.whl']
    if not all(file.is_file() for file in files):
        parser.error('Build both IDE packages, the CLI distribution and SDK first')
    args.output.mkdir(parents=True)
    for file in files: shutil.copy2(file, args.output / file.name)
    shutil.copytree(ROOT / 'wrapperguard-cli/build/install/wrapperguard-cli', args.output / 'cli')
    for directory in ('docs', 'schema', 'catalog', 'examples', 'research'):
        shutil.copytree(ROOT / directory, args.output / directory,
                        ignore=shutil.ignore_patterns('__pycache__', 'build', '.gradle', '.kotlin', '.idea'))
    shutil.copytree(ROOT / 'test-fixtures/newfirstparty', args.output / 'test-fixtures/newfirstparty')
    for name in ('HANDOVER.md', 'LICENSE', 'CHANGELOG.md', 'SECURITY.md', 'THIRD_PARTY_NOTICES.md'):
        shutil.copy2(ROOT / name, args.output / name)
    for gallery in ('platform-0.6', 'platform-0.7', 'flexible-provider-platform'):
        screenshots = ROOT / 'demo/screenshots' / gallery
        if screenshots.is_dir(): shutil.copytree(screenshots, args.output / 'screenshots' / gallery)
    audit = ROOT / 'build/distribution-audit'
    if not all((audit / name).is_file() for name in ('runtime.cdx.json', 'distribution-audit.json')):
        parser.error('Run scripts/distribution-audit.py --output build/distribution-audit (absolute) first')
    shutil.copytree(audit, args.output / 'distribution-audit')
    smoke = (ROOT / 'scripts/provider-smoke.py').read_text()
    smoke = smoke.replace('Path(__file__).resolve().parents[1]', 'Path(__file__).resolve().parent')
    smoke = smoke.replace('wrapperguard-cli/build/install/wrapperguard-cli/bin', 'cli/bin')
    smoke, count = re.subn(r'expected_version = re.search\([^\n]+', f'expected_version = "{version}"', smoke)
    if count != 1: raise RuntimeError('Provider smoke version contract changed')
    (args.output / 'CHECK-GATES.py').write_text(smoke)
    (args.output / 'SOURCE-COMMIT.txt').write_text(commit + '\n')
    (args.output / 'TESTING.md').write_text(f'''# WrapperGuard {args.tag} acceptance build

Source: `{commit}`. App {version}; SDK {sdk}; provider API 1. This candidate is for
acceptance testing. See [verification](docs/verification.md), [roadmap](docs/roadmap.md)
and [integration setup](docs/integrations.md) for completed evidence and remaining work.

1. Verify `SHA256SUMS` from this extracted directory: `shasum -a 256 -c SHA256SUMS`.
2. IntelliJ/Android Studio: Plugins → gear → Install Plugin from Disk; choose
   `wrapperguard-intellij-{version}.zip`, restart. Minimum platform 252.
3. VS Code: Extensions → … → Install from VSIX; choose `wrapperguard-{version}.vsix`, reload.
4. Open `test-fixtures/newfirstparty/` as source only. It has no runnable build. Do not
   execute fixture files. Follow the ten-minute source-analysis and additional platform
   acceptance steps in [verification](docs/verification.md#install-and-test-the-build).
5. Use Tools for methods/presets. Native custom SDK/storage controls are in Tools;
   VS Code custom SDK/storage controls are in Providers. History starts off; opt in explicitly. New commands start disabled.
6. Run `python3 CHECK-GATES.py` with JDK 21 and Python 3.10+ to exercise this packaged
   CLI's actual provider protocol, grants and failure behavior. If extraction lost modes,
   first `chmod +x cli/bin/wrapperguard-cli` on macOS/Linux.

The SDK wheel installs offline; optional scanner downloads are separate choices. Direct
snapshot runners support tested POSIX/Windows boundaries; imports and loopback review
remain independently available. Native live BUILD/SYNC acceptance and cooperative
VS Code integrations are separate from the packaged CLI smoke. Android device evidence
uses an Android16 emulator and an owned test target, not a mobile WrapperGuard plugin.
Screenshots distinguish real editor setup from synthetic provider/history examples.
''')
    records = []
    for file in sorted(args.output.rglob('*')):
        if file.is_file():
            records.append(hashlib.sha256(file.read_bytes()).hexdigest() + '  ' + file.relative_to(args.output).as_posix())
    (args.output / 'SHA256SUMS').write_text('\n'.join(records) + '\n')
    archive = args.output / f'WrapperGuard-{args.tag.removeprefix("v")}-test-bundle.zip'
    with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as output:
        for file in sorted(args.output.rglob('*')):
            if file.is_file() and file != archive:
                output.write(file, file.relative_to(args.output).as_posix())
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    (args.output / 'BUNDLE-SHA256SUMS.txt').write_text(digest + '  ' + archive.name + '\n')
    print(str(archive))
    print(digest)


if __name__ == '__main__': main()
