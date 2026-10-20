#!/usr/bin/env python3
"""Acquire pinned inert research inputs and evaluate an explicitly installed SDK/CLI.

No imports, installs, builds or payload execution from the corpus. Malware source
is written without execute bits only to an explicit private scratch directory.
Archive extraction uses bounded byte parsers, never extractall or shell commands.
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import stat
import subprocess
import tarfile
import time
import urllib.request
from urllib.parse import urlsplit
import zipfile

ROOT = Path(__file__).resolve().parents[1]
MAX_ARCHIVE = 32 * 1024 * 1024
MAX_EXPANDED = 128 * 1024 * 1024
MAX_FILE = 1024 * 1024
MAX_ENTRIES = 10000
EXTENSIONS = {'.py', '.ps1', '.psm1', '.kt', '.kts', '.java', '.gradle', '.toml', '.xml', '.properties', '.bat', '.sh'}


def relative(name: str) -> PurePosixPath:
    if not isinstance(name, str) or not name or '\\' in name or ':' in name or any(ord(c) < 32 for c in name):
        raise ValueError('Invalid corpus member path')
    candidate = PurePosixPath(name)
    if candidate.is_absolute() or any(part in ('', '.', '..') for part in name.rstrip('/').split('/')):
        raise ValueError('Escaping corpus member')
    return candidate


def selected(name: str) -> bool:
    path = relative(name)
    return path.suffix.lower() in EXTENSIONS or path.name in {'gradlew', 'gradle-wrapper.jar', 'pom.xml', 'LICENSE', 'LICENSE.txt', 'NOTICE'}


def parse_archive(raw: bytes, kind: str) -> list[tuple[str, bytes]]:
    result = []
    total = 0
    def add(name, content):
        nonlocal total
        relative(name)
        total += len(content)
        if len(content) > MAX_FILE or total > 64 * 1024 * 1024:
            raise ValueError('Corpus extraction byte budget exceeded')
        result.append((name, content))
    if kind in {'encrypted-zip', 'zip'}:
        with zipfile.ZipFile(io.BytesIO(raw)) as archive:
            entries = archive.infolist()
            if len(entries) > MAX_ENTRIES or sum(entry.file_size for entry in entries) > MAX_EXPANDED:
                raise ValueError('Corpus ZIP expanded budget exceeded')
            for entry in entries:
                relative(entry.filename)
                if entry.is_dir():
                    continue
                mode = (entry.external_attr >> 16) & 0xffff
                if stat.S_ISLNK(mode):
                    raise ValueError('Corpus links are forbidden')
                if selected(entry.filename):
                    if entry.file_size > MAX_FILE:
                        raise ValueError('Corpus source exceeds per-file budget')
                    with archive.open(entry, pwd=b'infected' if kind == 'encrypted-zip' else None) as stream:
                        add(entry.filename, stream.read(MAX_FILE + 1))
    elif kind == 'tar-gz':
        with gzip.GzipFile(fileobj=io.BytesIO(raw)) as stream:
            expanded = stream.read(MAX_EXPANDED + 1)
        if len(expanded) > MAX_EXPANDED:
            raise ValueError('Corpus TAR expanded budget exceeded')
        with tarfile.open(fileobj=io.BytesIO(expanded), mode='r:') as archive:
            for count, entry in enumerate(archive):
                if count >= MAX_ENTRIES:
                    raise ValueError('Corpus member budget exceeded')
                relative(entry.name)
                if entry.isdir():
                    continue
                # Upstream links, binaries and devices are never materialized.
                if not entry.isfile() or not selected(entry.name):
                    continue
                if entry.size > MAX_FILE:
                    continue
                stream = archive.extractfile(entry)
                if stream is not None:
                    with stream:
                        add(entry.name, stream.read(MAX_FILE + 1))
    else:
        raise ValueError('Unsupported archive kind')
    names = [name for name, _ in result]
    if len(set(names)) != len(names) or len({name.casefold() for name in names}) != len(names):
        raise ValueError('Duplicate corpus member names')
    return result


def acquire(lock: dict, destination: Path) -> dict:
    if lock.get('schemaVersion') != 1 or not 1 <= len(lock.get('sources', [])) <= 1000:
        raise ValueError('Unsupported corpus manifest')
    destination.mkdir(mode=0o700, parents=True, exist_ok=False)
    inventory = {'schemaVersion': 1, 'sources': [], 'projectCodeExecuted': False}
    for source in lock['sources']:
        if not re.fullmatch(r'[a-z0-9][a-z0-9.-]{0,79}', source['id']) or not re.fullmatch(r'[0-9a-f]{40}(?:[0-9a-f]{24})?', source['revision']):
            raise ValueError('Unpinned corpus identity')
        project = destination / source['id']
        project.mkdir(mode=0o700)
        entries = []
        for artifact in source['artifacts']:
            url = artifact['url']; parsed = urlsplit(url)
            github = parsed.hostname in {'raw.githubusercontent.com', 'codeload.github.com'} and source['revision'] in parsed.path
            pypi = parsed.hostname == 'files.pythonhosted.org' and artifact['kind'] == 'zip' and '/packages/' in parsed.path
            if parsed.scheme != 'https' or not (github or pypi):
                raise ValueError('Corpus URL must be a pinned official GitHub source')
            if not 0 < artifact['bytes'] <= MAX_ARCHIVE or not re.fullmatch(r'[0-9a-f]{64}', artifact['sha256']):
                raise ValueError('Invalid corpus artifact budget or digest')
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            with opener.open(url, timeout=45) as response:
                if response.geturl() != url:
                    raise ValueError('Corpus redirects are not accepted')
                raw = response.read(artifact['bytes'] + 1)
            if len(raw) != artifact['bytes'] or hashlib.sha256(raw).hexdigest() != artifact['sha256']:
                raise ValueError('Corpus artifact checksum/size mismatch')
            if artifact['kind'] == 'file':
                entries.append((artifact['path'], raw))
            else:
                parsed_entries = parse_archive(raw, artifact['kind'])
                if artifact['kind'] == 'tar-gz' and parsed_entries:
                    # GitHub tarballs wrap the actual project in one archive directory.
                    # Strip that wrapper so the CLI sees the real settings/wrapper root.
                    prefixes = {relative(name).parts[0] for name, _ in parsed_entries}
                    if len(prefixes) == 1 and all(len(relative(name).parts) > 1 for name, _ in parsed_entries):
                        parsed_entries = [('/'.join(relative(name).parts[1:]), data) for name, data in parsed_entries]
                entries.extend(parsed_entries)
        hashes = {}
        for name, content in entries:
            target = project.joinpath(*relative(name).parts)
            target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            with target.open('xb') as stream:
                stream.write(content)
            target.chmod(0o400)
            hashes[name] = hashlib.sha256(content).hexdigest()
        inventory['sources'].append({**{key: source[key] for key in ('id', 'label', 'referenceDate', 'group', 'family', 'split', 'license')},
                                     'files': hashes, 'bytes': sum(len(content) for _, content in entries),
                                     'selection': 'Regular source/configuration files and exact Gradle wrapper JAR, at most 1 MiB per file; other binaries, links and large files are excluded'})
    (destination / 'inventory.json').write_text(json.dumps(inventory, indent=2) + '\n', encoding='utf-8')
    return inventory


def evaluate(directory: Path, sdk_python: Path, cli: Path | None) -> dict:
    import tempfile
    inventory = json.loads((directory / 'inventory.json').read_text())
    results = []
    observed_hashes = {}
    cross_group_duplicates = set()
    for source in inventory['sources']:
        project = directory / source['id']
        for name, expected in source['files'].items():
            path = project.joinpath(*relative(name).parts)
            with path.open('rb') as stream:
                data = stream.read(MAX_FILE + 1)
            if path.is_symlink() or len(data) > MAX_FILE or hashlib.sha256(data).hexdigest() != expected:
                raise ValueError('Corpus source changed after acquisition')
            if expected in observed_hashes and observed_hashes[expected] != source['group']:
                cross_group_duplicates.add(expected)
            observed_hashes[expected] = source['group']
        with tempfile.TemporaryDirectory(prefix='wg-corpus-profile-') as temporary:
            profile = Path(temporary) / 'profile.json'
            profile.write_text(json.dumps({'id': 'research.script-semantics', 'displayName': 'Static corpus signals', 'version': '1',
                'analysisKind': 'SAST', 'format': 'script-semantics', 'runner': {'tool': 'script-semantics', 'timeoutMs': 30000},
                'allowedOperations': ['SCAN'], 'requestedActions': ['WARNING']}))
            started = time.monotonic()
            run = subprocess.run([str(sdk_python), '-I', '-m', 'wrapperguard_sdk', '--profile', str(profile)],
                input=json.dumps({'apiVersion': 1, 'projectPath': str(project), 'operation': 'SCAN'}) + '\n',
                text=True, capture_output=True, timeout=40)
            if run.returncode != 0:
                raise RuntimeError('Installed static SDK did not complete: ' + run.stderr[:200])
            report = json.loads(run.stdout)
            result = {key: source[key] for key in ('id', 'label', 'referenceDate', 'group', 'family', 'split')}
            result.update({'seconds': round(time.monotonic() - started, 3), 'complete': report['complete'],
                           'scannedFiles': report['scannedFiles'], 'findingCount': len(report['findings']),
                           'ruleIds': sorted({finding['ruleId'] for finding in report['findings']}),
                           'notices': [notice['code'] for notice in report.get('notices', [])]})
            if cli and source['label'] == 'benign-project':
                started = time.monotonic()
                host = subprocess.run([str(cli), str(project), '--operation', 'scan', '--json'], text=True, capture_output=True, timeout=40)
                host_report = json.loads(host.stdout)
                result['cli'] = {'seconds': round(time.monotonic() - started, 3), 'complete': host_report['complete'],
                                 'scannedFiles': host_report['scannedFiles'], 'findingCount': len(host_report['findings']),
                                 'verdict': host_report['verdict']}
            results.append(result)
    eligible = [result for result in results if result['scannedFiles'] > 0 and result['complete']]
    matrix = {'maliciousWithSignal': 0, 'maliciousWithoutSignal': 0, 'benignWithSignal': 0, 'benignWithoutSignal': 0}
    for result in eligible:
        key = ('malicious' if result['label'] == 'malicious-package' else 'benign') + ('WithSignal' if result['findingCount'] else 'WithoutSignal')
        matrix[key] += 1
    tp, fn, fp, tn = (matrix[key] for key in ('maliciousWithSignal', 'maliciousWithoutSignal', 'benignWithSignal', 'benignWithoutSignal'))
    malicious = [row for row in results if row['label'] == 'malicious-package']
    identity = json.loads(subprocess.check_output([str(sdk_python), '-I', '-c',
        'import hashlib,importlib.metadata,json,pathlib,wrapperguard_sdk.semantics as s;'
        'print(json.dumps({"sdkVersion":importlib.metadata.version("wrapperguard-sdk"),'
        '"scriptSemanticsSha256":hashlib.sha256(pathlib.Path(s.__file__).read_bytes()).hexdigest()}))'], text=True))
    cli_hashes = {path.name: hashlib.sha256(path.read_bytes()).hexdigest()
                  for path in sorted((cli.parent.parent / 'lib').glob('*.jar'))} if cli else {}
    return {'schemaVersion': 1, **identity, 'cliJarSha256': cli_hashes,
            'inventorySha256': hashlib.sha256((directory / 'inventory.json').read_bytes()).hexdigest(),
            'projectCodeExecuted': False, 'sources': results, 'signalMatrix': matrix,
            'matrixScope': 'Complete scans with at least one supported source input; incomplete/unsupported sources listed separately',
            'allMaliciousAttempts': len(malicious), 'allMaliciousWithSignal': sum(row['findingCount'] > 0 for row in malicious),
            'allMaliciousSignalRate': sum(row['findingCount'] > 0 for row in malicious) / len(malicious) if malicious else None,
            'unassessedSources': [row['id'] for row in results if row['scannedFiles'] == 0 or not row['complete']],
            'signalPrecision': tp / (tp + fp) if tp + fp else None,
            'signalRecall': tp / (tp + fn) if tp + fn else None,
            'benignSignalRate': fp / (fp + tn) if fp + tn else None,
            'crossGroupDuplicateFileHashes': len(cross_group_duplicates),
            'familySplitVerified': False, 'sourceHoldoutVerified': False, 'trainingPerformed': False,
            'limitations': ['Tiny integration baseline, not a statistically representative malware benchmark.',
                           'Any advisory signal is counted as positive; signals do not establish malicious intent.',
                           'Malware comes from one vendor-selected source; unknown families prevent family-disjoint claims.',
                           'Dates mix upstream discovery and commit time; class/date and language confounding remain.',
                           'Android/Ktor inputs unsupported by script semantics are not counted as true negatives.',
                           'A complete scan means completion of configured scope, never universal security coverage.']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--lock', type=Path, default=ROOT / 'research/corpus.lock.json')
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--fetch', action='store_true')
    parser.add_argument('--sdk-python', type=Path)
    parser.add_argument('--cli', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    if not args.directory.is_absolute():
        parser.error('Choose an explicit absolute scratch directory outside the checkout')
    if args.directory.resolve().is_relative_to(ROOT):
        parser.error('Keep acquired samples outside the source checkout')
    if args.fetch:
        inventory = acquire(json.loads(args.lock.read_text()), args.directory)
        print(json.dumps({'acquiredSources': len(inventory['sources']), 'projectCodeExecuted': False}))
    if args.sdk_python:
        if not args.sdk_python.is_absolute() or not args.output:
            parser.error('Evaluation requires an absolute installed SDK Python and an output path')
        result = evaluate(args.directory, args.sdk_python, args.cli)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with args.output.open('x') as stream:
            json.dump(result, stream, indent=2); stream.write('\n')
        print(json.dumps({key: value for key, value in result.items() if key != 'sources'}))
    elif not args.fetch:
        parser.error('Choose --fetch or --sdk-python evaluation')


if __name__ == '__main__':
    main()
