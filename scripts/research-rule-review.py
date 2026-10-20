#!/usr/bin/env python3
"""Review bundled Semgrep signals on pinned benign source subsets without running projects.

Uses only an explicitly installed SDK and scanner. Findings retain rule IDs and relative
locations for contextual review; source, evidence, messages and absolute paths are omitted.
No count in this integration baseline establishes precision or application security.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
PACKS = {
    'legacy-android-ktor': (('.kt', '.kts', '.java'), ()),
    'android-manifest': ((), ('AndroidManifest.xml',)),
    'android-webview': (('.kt', '.java'), ()),
    'jvm-crypto': (('.kt', '.kts', '.java'), ()),
    'ktor-runtime': (('.kt', '.kts'), ()),
}
EXCLUDED = {'.git', '.hg', '.svn', '.venv', 'venv', 'env', 'node_modules', '__pycache__', 'build', 'dist', '.tox', '.nox'}
MAX_INPUT = 1024 * 1024
MAX_TOTAL = 64 * 1024 * 1024
MAX_OUTPUT = 4 * 1024 * 1024
SDK_CODE = (
    'import json,sys;from pathlib import Path;'
    'from wrapperguard_sdk.profile import load_profile;from wrapperguard_sdk.cli import evaluate;'
    'root=Path(sys.argv[2]);profile=load_profile(Path(sys.argv[1]),root);'
    'print(json.dumps(evaluate(profile,root,"SCAN"),separators=(",",":")))'
)
IDENTITY_CODE = (
    'import hashlib,importlib.metadata,json,pathlib;from wrapperguard_sdk.rule_packs import RULE_PACKS;'
    'import wrapperguard_sdk.rule_packs as p;'
    'print(json.dumps({"sdkVersion":importlib.metadata.version("wrapperguard-sdk"),'
    '"rulePackSha256":{key:hashlib.sha256((pathlib.Path(p.__file__).parent/"rules"/v["file"]).read_bytes()).hexdigest()'
    'for key,v in RULE_PACKS.items()}}))'
)


def relative(value: object) -> str:
    if (not isinstance(value, str) or not value or len(value) > 4096 or '\\' in value or ':' in value
            or any(ord(c) < 32 or ord(c) == 127 for c in value)
            or any(part in ('', '.', '..') for part in value.split('/'))):
        raise ValueError('Invalid inventory-relative location')
    if PurePosixPath(value).is_absolute():
        raise ValueError('Invalid inventory-relative location')
    return value


def regular_bytes(root: Path, name: str, limit: int) -> bytes:
    """Handle-relative no-follow reads for this POSIX CI driver; source stays inert."""
    if os.name != 'posix' or not hasattr(os, 'O_NOFOLLOW'):
        raise ValueError('This evaluation driver requires POSIX no-follow file handles')
    parts = relative(name).split('/')
    directory = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        for part in parts[:-1]:
            child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=directory)
            os.close(directory); directory = child
        fd = os.open(parts[-1], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=directory)
        with os.fdopen(fd, 'rb') as stream:
            if not stat.S_ISREG(os.fstat(stream.fileno()).st_mode):
                raise ValueError('Inventory inputs must be regular files')
            data = stream.read(limit + 1)
        if len(data) > limit:
            raise ValueError('Inventory byte limit exceeded')
        return data
    finally:
        os.close(directory)


def verified_source(directory: Path, source: dict) -> dict[str, str]:
    if (not isinstance(source, dict) or source.get('label') != 'benign-project'
            or not re.fullmatch(r'[a-z0-9][a-z0-9.-]{0,79}', source.get('id', ''))
            or not isinstance(source.get('files'), dict) or not 1 <= len(source['files']) <= 10000):
        raise ValueError('Only bounded, pinned benign source subsets may be reviewed')
    files = source['files']; total = 0
    project = directory / source['id']
    for name, expected in files.items():
        if not isinstance(expected, str) or not re.fullmatch(r'[0-9a-f]{64}', expected):
            raise ValueError('Missing inventory input digest')
        data = regular_bytes(project, name, MAX_INPUT)
        total += len(data)
        if total > MAX_TOTAL or hashlib.sha256(data).hexdigest() != expected:
            raise ValueError('Acquired source digest or byte budget differs from inventory')
    # An unexpected file could influence a fixed scanner snapshot. Verify the inventory
    # covers the entire acquired tree; directory links and special entries are rejected.
    actual = set()
    pending = [(project, 0)]
    entries_seen = 0
    while pending:
        current, depth = pending.pop()
        if depth > 64:
            raise ValueError('Acquired source depth limit exceeded')
        with os.scandir(current) as entries:
            for entry in entries:
                entries_seen += 1
                if entries_seen > 20000 or entry.is_symlink():
                    raise ValueError('Unexpected or linked acquired input')
                if entry.is_dir(follow_symlinks=False):
                    pending.append((Path(entry.path), depth + 1))
                elif entry.is_file(follow_symlinks=False):
                    actual.add(Path(entry.path).relative_to(project).as_posix())
                else:
                    raise ValueError('Unexpected acquired input type')
    if actual != set(files):
        raise ValueError('Acquired input set differs from inventory')
    return files


def candidates(files: dict[str, str], pack: str) -> set[str]:
    suffixes, names = PACKS[pack]
    return {name for name in files if not any(part in EXCLUDED for part in PurePosixPath(name).parts)
            and (PurePosixPath(name).suffix.lower() in suffixes or PurePosixPath(name).name in names)}


def minimize(document: object, allowed: set[str]) -> dict:
    if (not isinstance(document, dict) or type(document.get('complete')) is not bool
            or not isinstance(document.get('findings'), list) or len(document['findings']) > 5000
            or not isinstance(document.get('notices', []), list)):
        raise ValueError('Invalid SDK review result')
    findings = []
    for item in document['findings']:
        if not isinstance(item, dict):
            raise ValueError('Invalid SDK review finding')
        rule = item.get('ruleId'); location = item.get('location'); severity = item.get('severity')
        if (not isinstance(rule, str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.:/-]{0,119}', rule)
                or not isinstance(location, dict) or relative(location.get('path')) not in allowed
                or severity not in {'INFO', 'WARN', 'ERROR'}):
            raise ValueError('Invalid SDK review attribution')
        finding = {'ruleId': rule, 'severity': severity, 'path': location['path']}
        for key in ('line', 'column'):
            if location.get(key) is not None:
                if type(location[key]) is not int or location[key] < 1:
                    raise ValueError('Invalid SDK review position')
                finding[key] = location[key]
        findings.append(finding)
    codes = []
    for notice in document.get('notices', []):
        code = notice.get('code') if isinstance(notice, dict) else None
        if not isinstance(code, str) or not re.fullmatch(r'[A-Za-z0-9_.-]{1,120}', code):
            raise ValueError('Invalid SDK review notice')
        codes.append(code)
    return {'complete': document['complete'], 'findingCount': len(findings),
            'ruleCounts': dict(sorted(Counter(f['ruleId'] for f in findings).items())),
            'noticeCodes': sorted(set(codes)), 'findings': sorted(findings, key=lambda f:(f['path'], f.get('line',0), f['ruleId']))}


def invoke(argv: list[str], user: Path, timeout: float, maximum: int = MAX_OUTPUT) -> bytes:
    from wrapperguard_sdk.portable_process import bounded_process
    environment = {'PATH': os.defpath, 'HOME': str(user), 'TMPDIR': str(user), 'TEMP': str(user),
                   'TMP': str(user), 'LANG': 'C.UTF-8', 'LC_ALL': 'C.UTF-8',
                   'SEMGREP_SEND_METRICS': 'off', 'SEMGREP_ENABLE_VERSION_CHECK': '0', 'PYTHONNOUSERSITE': '1'}
    return bounded_process(argv, user, environment, time.monotonic() + timeout, maximum)


def evaluate(directory: Path, sdk_python: Path, semgrep: Path, selected: list[str], timeout: float = 120,
             *, run=invoke) -> dict:
    inventory_bytes = regular_bytes(directory, 'inventory.json', 16 * 1024 * 1024)
    inventory = json.loads(inventory_bytes)
    if (not isinstance(inventory, dict) or inventory.get('schemaVersion') != 1
            or not isinstance(inventory.get('sources'), list) or len(inventory['sources']) > 1000
            or not 1 <= len(selected) <= 8 or len(set(selected)) != len(selected)):
        raise ValueError('Invalid review inventory or source selection')
    sources = {source['id']: source for source in inventory['sources'] if isinstance(source, dict) and isinstance(source.get('id'),str)}
    if len(sources) != len(inventory['sources']) or any(key not in sources for key in selected):
        raise ValueError('Missing or duplicate selected source')
    results = []
    with tempfile.TemporaryDirectory(prefix='wg-rule-review-') as temporary:
        user = Path(temporary).resolve()
        identity = json.loads(run([str(sdk_python), '-I', '-c', IDENTITY_CODE], user, 30, 65536))
        if (not isinstance(identity, dict) or not isinstance(identity.get('sdkVersion'),str)
                or set(identity.get('rulePackSha256', {})) != set(PACKS)
                or any(not isinstance(v,str) or not re.fullmatch('[0-9a-f]{64}',v) for v in identity['rulePackSha256'].values())):
            raise ValueError('Unexpected installed SDK rule-pack identity')
        version = run([str(semgrep), '--version'], user, 30, 65536).decode('utf-8').strip()
        if not re.fullmatch(r'\d+\.\d+\.\d+', version):
            raise ValueError('Unexpected installed Semgrep identity')
        for source_id in selected:
            source = sources[source_id]; files = verified_source(directory, source)
            for pack in PACKS:
                allowed = candidates(files, pack)
                row = {'sourceId': source_id, 'rulePack': pack, 'inventoryFileCount': len(files),
                       'candidateFileCount': len(allowed), 'candidateSetSha256': hashlib.sha256(
                           json.dumps({name: files[name] for name in sorted(allowed)},separators=(',',':')).encode()).hexdigest()}
                if not allowed:
                    row.update({'status':'NO_SUPPORTED_INPUTS', 'complete':False, 'findingCount':0,
                                'ruleCounts':{}, 'noticeCodes':[], 'findings':[]})
                    results.append(row); continue
                profile = user / 'profile.json'
                profile.write_text(json.dumps({'id':'sast.semgrep', 'displayName':'Pinned source review', 'version':'1',
                    'analysisKind':'SAST','format':'sarif','allowedOperations':['SCAN'],'requestedActions':['WARNING'],
                    'runner':{'tool':'semgrep','executable':str(semgrep),'timeoutMs':int(timeout * 1000),
                              'rulePacks':[pack]}}),encoding='utf-8')
                started = time.monotonic()
                try:
                    raw = run([str(sdk_python), '-I', '-c', SDK_CODE, str(profile), str(directory / source_id)],user,timeout + 5)
                    row.update(minimize(json.loads(raw), allowed)); row['status'] = 'COMPLETE' if row['complete'] else 'INCOMPLETE'
                except (OSError, ValueError, TimeoutError) as error:
                    row.update({'status':'FAILED', 'complete':False,'findingCount':0,'ruleCounts':{},'noticeCodes':[],
                                'findings':[], 'failureKind':type(error).__name__})
                row['elapsedMs'] = round((time.monotonic() - started) * 1000,3)
                verified_source(directory, source)
                results.append(row)
    configured = [row for row in results if row['candidateFileCount']]
    return {'schemaVersion':1, **identity, 'semgrepVersion':version,
            'inventorySha256':hashlib.sha256(inventory_bytes).hexdigest(), 'projectCodeExecuted':False,
            'allConfiguredScansComplete':bool(configured) and all(row['complete'] for row in configured),
            'sources':results, 'contextualReviewPerformed':False, 'precisionMeasured':False,
            'provenance':'Input digests are checked against the acquired inventory before and after each run. The acquisition job separately verifies pinned upstream archive digests.',
            'limitations':['Pinned benign source subsets, not full upstream projects or a representative security benchmark.',
                'Finding counts are review signals, not false positives, malicious intent, precision or recall.',
                'Only one bundled pack is selected per run; configured scope completion is not universal app security.',
                'Candidate counts describe selected source inputs; processed file counts are not supplied by provider API1.',
                'No project wrapper, build, dependency installer or application is executed.',
                'Installed SDK/scanner and the host OS are trusted; fixed source snapshot is not an OS sandbox.']}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--sdk-python', type=Path, required=True)
    parser.add_argument('--semgrep', type=Path, required=True)
    parser.add_argument('--source', action='append', default=[])
    parser.add_argument('--timeout-seconds', type=float, default=120)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if (not all(path.is_absolute() for path in (args.directory,args.sdk_python,args.semgrep,args.output))
            or not 1 <= args.timeout_seconds <= 300):
        parser.error('Choose absolute input/output paths and a 1..300 second deadline')
    directory = args.directory.resolve(strict=True)
    if args.output.resolve().is_relative_to(directory):
        parser.error('Keep review output outside the acquired input tree')
    result = evaluate(directory,args.sdk_python,args.semgrep,args.source or ['now-in-android','ktor-samples'],args.timeout_seconds)
    args.output.parent.mkdir(parents=True,exist_ok=True)
    with args.output.open('x',encoding='utf-8') as stream:
        json.dump(result,stream,indent=2);stream.write('\n')
    print(json.dumps({'configuredScansComplete':result['allConfiguredScansComplete'],
                      'runs':len(result['sources']), 'precisionMeasured':False}))
    return 0 if result['allConfiguredScansComplete'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
