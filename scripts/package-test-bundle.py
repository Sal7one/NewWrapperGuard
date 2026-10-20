#!/usr/bin/env python3
"""Assemble a reviewable acceptance bundle from current built artifacts and clean source."""
import argparse
import hashlib
import html
import io
import json
from pathlib import Path
import re
import shutil
import stat
import subprocess
from urllib.parse import unquote, urlsplit
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def extract_cli_distribution(archive_bytes, output, version):
    """Extract only the audited archive, with portable paths and executable modes."""
    prefix = f'wrapperguard-cli-{version}/'
    with zipfile.ZipFile(io.BytesIO(archive_bytes)) as archive:
        entries = []
        names = set()
        for info in archive.infolist():
            name = info.filename
            if not name.startswith(prefix) or '\\' in name:
                raise ValueError('Unsafe CLI archive prefix or path')
            relative = name.removeprefix(prefix).removesuffix('/')
            if ':' in relative or relative and any(part in ('', '.', '..') for part in relative.split('/')):
                raise ValueError('Unsafe CLI archive path')
            if relative in names:
                raise ValueError('Duplicate CLI archive entry')
            names.add(relative)
            mode = info.external_attr >> 16
            if stat.S_IFMT(mode) not in (0, stat.S_IFREG, stat.S_IFDIR):
                raise ValueError('Unsupported CLI archive file type')
            if (stat.S_ISDIR(mode) and not info.is_dir()) or (stat.S_ISREG(mode) and info.is_dir()):
                raise ValueError('Inconsistent CLI archive file type')
            if not relative:
                if not info.is_dir():
                    raise ValueError('Unsafe CLI archive root entry')
                continue
            entries.append((info, relative, mode))
        files = {relative for info, relative, _ in entries if not info.is_dir()}
        if any(parent.as_posix() in files for _, relative, _ in entries for parent in Path(relative).parents if str(parent) != '.'):
            raise ValueError('Conflicting CLI archive file and directory entries')
        if not entries or output.exists():
            raise ValueError('CLI output must be new and archive must contain files')
        output.mkdir(parents=True)
        for info, relative, mode in entries:
            target = output/relative
            if info.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(archive.read(info))
                target.chmod(mode & 0o777 or 0o644)


def markdown_destinations(text):
    """Read inline/image and reference destinations, excluding literal code examples."""
    lines = []
    fence = None
    for line in text.splitlines():
        marker = re.match(r'^ {0,3}(`{3,}|~{3,})', line)
        if fence:
            if marker and marker[1][0] == fence[0] and len(marker[1]) >= len(fence):
                fence = None
            continue
        if marker:
            fence = marker[1]
            continue
        lines.append(line)
    text = re.sub(r'(`+).*?\1', '', '\n'.join(lines))

    def destination(start):
        if text[start:start+1] == '<':
            end = text.find('>', start+1)
            return text[start+1:end] if end != -1 else ''
        value = []
        depth = 0
        index = start
        while index < len(text):
            char = text[index]
            if char == '\\' and index+1 < len(text):
                value.extend((char, text[index+1])); index += 2; continue
            if char == ')' and depth == 0 or char.isspace() and depth == 0:
                break
            if char == '(':
                depth += 1
            elif char == ')':
                depth -= 1
            value.append(char); index += 1
        return ''.join(value)

    for match in re.finditer(r'\]\(\s*|^ {0,3}\[[^\]\n]+\]:\s*', text, re.M):
        value = destination(match.end())
        if value:
            yield html.unescape(re.sub(r'\\([\\`*{}\[\]()#+\-.!_> ])', r'\1', value))


def validate_markdown_links(directory):
    """Fail on missing or escaping local links in the actual copied offline material."""
    directory = directory.resolve()
    failures = []
    for document in sorted(directory.rglob('*.md')):
        for link in markdown_destinations(document.read_text(encoding='utf-8')):
            if link.startswith('#'):
                continue
            if re.match(r'^[A-Za-z]:[\\/]', link):
                failures.append(f'{document.relative_to(directory)}: outside bundle: {link}')
                continue
            target = urlsplit(link)
            if target.scheme.lower() == 'file':
                failures.append(f'{document.relative_to(directory)}: outside bundle: {link}')
                continue
            if target.scheme or target.netloc:
                continue
            path = unquote(target.path)
            candidate = (document.parent/path).resolve()
            if Path(path).is_absolute() or not candidate.is_relative_to(directory):
                failures.append(f'{document.relative_to(directory)}: outside bundle: {link}')
            elif not candidate.exists():
                failures.append(f'{document.relative_to(directory)}: missing destination: {link}')
    if failures:
        raise ValueError('Broken offline Markdown links:\n' + '\n'.join(failures))


def copy_review_material(root, output):
    """Preserve repository-relative paths used by the offline acceptance guides."""
    for directory in ('docs', 'schema', 'catalog', 'examples', 'research'):
        shutil.copytree(root / directory, output / directory,
                        ignore=shutil.ignore_patterns('__pycache__', 'build', '.gradle', '.kotlin', '.idea'))
    shutil.copytree(root / 'test-fixtures/newfirstparty', output / 'test-fixtures/newfirstparty')
    for name in ('HANDOVER.md', 'LICENSE', 'CHANGELOG.md', 'SECURITY.md', 'THIRD_PARTY_NOTICES.md',
                 'vscode-extension/README.md', '.github/workflows/android-device.yml'):
        target = output / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(root / name, target)
    for gallery in ('platform-0.6', 'platform-0.7', 'flexible-provider-platform'):
        relative = Path('demo/screenshots') / gallery
        if (root / relative).is_dir():
            shutil.copytree(root / relative, output / relative)


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
    audit = ROOT / 'build/distribution-audit'
    if not all((audit / name).is_file() for name in ('runtime.cdx.json', 'distribution-audit.json')):
        parser.error('Run scripts/distribution-audit.py with an absolute build/distribution-audit output first')
    audited_hashes = json.loads((audit / 'distribution-audit.json').read_text()).get('artifactSha256', {})
    artifact_bytes = {file: file.read_bytes() for file in files}
    if any(audited_hashes.get(file.name) != hashlib.sha256(data).hexdigest() for file, data in artifact_bytes.items()):
        parser.error('Re-run the distribution audit: an artifact changed after review')
    args.output.mkdir(parents=True)
    for file, data in artifact_bytes.items(): (args.output / file.name).write_bytes(data)
    extract_cli_distribution(artifact_bytes[files[2]], args.output / 'cli', version)
    copy_review_material(ROOT, args.output)
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
    validate_markdown_links(args.output)
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
