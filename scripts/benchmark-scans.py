#!/usr/bin/env python3
"""Measure an explicitly installed CLI on selected benign project roots.

Runs static CLI scans, never project wrappers, builds, imports, installers or target
startup. Optional --providers is an explicit user-owned configuration: its enabled
trusted adapters run as configured. No provider is discovered from the project.
Only aggregate counts/status/codes leave the CLI report. Project paths, findings,
messages, evidence and stderr are discarded. POSIX /usr/bin/time measures each run;
peak RSS is the OS process high-water value, not summed concurrent-provider memory.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import random
import re
import selectors
import signal
import statistics
import subprocess
import sys
import tempfile
import time

MAX_REPORT = 16*1024*1024


def _metrics(raw: str, system: str) -> int | None:
    pattern = r'^\s*(\d+)\s+maximum resident set size\s*$' if system == 'Darwin' else r'^WRAPPERGUARD_RSS_KIB=(\d+)\s*$'
    matches = re.findall(pattern, raw, re.M)
    if len(matches) != 1: return None
    value = int(matches[0]) * (1 if system == 'Darwin' else 1024)
    return value if 0 < value < 1024**5 else None


def _signal_group(process, signum):
    try:
        os.killpg(process.pid, signum)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        # Darwin excludes zombies from group signalling and can return EPERM
        # while the last exited member is being reaped (XNU kern_sig.c killpg1).
        # An exited leader alone proves nothing about descendants. Accept only
        # actual ESRCH from a bounded group probe; live/unknown groups still fail.
        if process.poll() is None: raise
        deadline = time.monotonic()+.5
        while time.monotonic() < deadline:
            try: os.killpg(process.pid, 0)
            except ProcessLookupError: return False
            except PermissionError: pass
            time.sleep(.01)
        raise


def _stop(process):
    if not _signal_group(process, signal.SIGTERM):
        process.wait(timeout=2)
        return
    try: process.wait(timeout=.3)
    except subprocess.TimeoutExpired: pass
    _signal_group(process, signal.SIGKILL)
    process.wait(timeout=2)


def measured_command(arguments: list[str], timeout: float, *, maximum=MAX_REPORT) -> tuple[dict, bytes]:
    if os.name != 'posix' or platform.system() not in {'Linux', 'Darwin'} or not Path('/usr/bin/time').is_file():
        raise ValueError('Resource benchmarks require Linux/macOS /usr/bin/time')
    if not math.isfinite(timeout) or not .001 <= timeout <= 600 or not 1 <= maximum <= MAX_REPORT:
        raise ValueError('Invalid benchmark bounds')
    system = platform.system(); started = time.monotonic(); output = bytearray(); status = 'FINISHED'
    with tempfile.TemporaryDirectory(prefix='wg-resource-measure-') as temporary:
        metrics = Path(temporary)/'resources.txt'
        timing = ['/usr/bin/time', '-l', '-o', str(metrics)] if system == 'Darwin' else [
            '/usr/bin/time', '--quiet', '--format=WRAPPERGUARD_RSS_KIB=%M', '--output='+str(metrics)]
        environment = dict(os.environ); environment['LC_ALL'] = 'C'; environment['LANG'] = 'C'
        process = subprocess.Popen(timing+arguments, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL, cwd=temporary, env=environment, start_new_session=True, bufsize=0)
        selector = selectors.DefaultSelector()
        try:
            descriptor = process.stdout.fileno(); os.set_blocking(descriptor, False)
            selector.register(descriptor, selectors.EVENT_READ); stream_open = True
            deadline = started+timeout
            while stream_open or process.poll() is None:
                if time.monotonic() >= deadline: status = 'CANCELLED'; _stop(process); break
                for key, _ in selector.select(min(.02, max(0, deadline-time.monotonic()))):
                    data = os.read(key.fd, min(65536, maximum-len(output)+1))
                    if not data: selector.unregister(key.fd); stream_open = False
                    else:
                        output.extend(data)
                        if len(output)>maximum:
                            status = 'OUTPUT_LIMIT'; _stop(process); break
                if status != 'FINISHED': break
            code = process.wait(timeout=2)
        except BaseException:
            _stop(process); raise
        finally:
            selector.close(); process.stdout.close()
        elapsed = time.monotonic()-started
        try:
            with metrics.open('rb') as stream: raw = stream.read(65537)
            peak = _metrics(raw.decode('ascii', 'strict'), system) if len(raw)<=65536 else None
        except (OSError, UnicodeError): peak = None
        result = {'elapsedMs': round(elapsed*1000, 3), 'peakRssBytes': peak,
            'exitCode': code, 'processStatus': status, 'measurementComplete': status == 'FINISHED' and peak is not None}
        return result, bytes(output[:maximum])


def scan_summary(raw: bytes, exit_code: int, process_status: str) -> dict:
    base = {'complete': False, 'stale': False, 'verdict': 'UNAVAILABLE', 'scannedFiles': None,
            'findingCount': None, 'noticeCodes': [], 'providerStatuses': [], 'reportStatus': 'INVALID_REPORT'}
    if process_status != 'FINISHED': base['reportStatus'] = process_status; return base
    try:
        document = json.loads(raw.decode('utf-8', 'strict'))
        if (not isinstance(document, dict) or type(document.get('schemaVersion')) is not int or document['schemaVersion'] != 1 or
                type(document.get('complete')) is not bool or not isinstance(document.get('findings'), list) or
                type(document.get('scannedFiles')) is not int or document['scannedFiles'] < 0 or
                not isinstance(document.get('notices', []), list) or not isinstance(document.get('providerRuns', []), list) or
                'stale' in document and type(document['stale']) is not bool or
                exit_code not in (0, 1, 2) or document['complete'] and exit_code == 1):
            return base
        verdict = document.get('verdict')
        allowed = {'CLEAN', 'REVIEW_REQUIRED', 'EXECUTION_BLOCKED', 'INCOMPLETE', 'STALE', 'WARNING', 'BLOCKED'}
        codes = sorted({notice['code'] for notice in document.get('notices', []) if isinstance(notice, dict) and
            isinstance(notice.get('code'), str) and re.fullmatch(r'WG-[A-Z0-9_-]{1,100}', notice['code'])})
        statuses = [item.get('status') for item in document.get('providerRuns', []) if isinstance(item, dict)]
        return {**base, 'complete': document['complete'] and not document.get('stale', False),
            'stale': document.get('stale') is True, 'verdict': verdict if verdict in allowed else 'OTHER',
            'scannedFiles': document['scannedFiles'], 'findingCount': len(document['findings']),
            'noticeCodes': codes, 'providerStatuses': [status for status in statuses if status in
                {'COMPLETED', 'FAILED', 'TIMED_OUT', 'DISABLED', 'CANCELLED', 'UNAVAILABLE'}], 'reportStatus': 'VALID_REPORT'}
    except (ValueError, UnicodeError, TypeError, RecursionError): return base


def scan(cli: Path, root: Path, timeout: float, providers: Path | None = None, methods: Path | None = None) -> dict:
    arguments = [str(cli), '--json']
    if providers is not None: arguments.extend(['--providers', str(providers)])
    if methods is not None: arguments.extend(['--methods', str(methods)])
    arguments.extend(['--', str(root)])
    measured, raw = measured_command(arguments, timeout)
    return {**measured, **scan_summary(raw, measured['exitCode'], measured['processStatus'])}


def aggregate(runs: list[dict]) -> dict:
    measured = [run for run in runs if not run.get('warmup') and run['processStatus'] == 'FINISHED']
    durations = sorted(run['elapsedMs'] for run in measured)
    peaks = [run['peakRssBytes'] for run in measured if run['peakRssBytes'] is not None]
    return {'measuredRuns': len(measured), 'completeRuns': sum(run['complete'] for run in measured),
        'incompleteRuns': sum(not run['complete'] for run in measured),
        'cancelledRuns': sum(run['processStatus'] == 'CANCELLED' for run in runs if not run.get('warmup')),
        'p50ElapsedMs': statistics.median(durations) if durations else None,
        'p95ElapsedMs': durations[max(0, math.ceil(.95*len(durations))-1)] if durations else None,
        'maxPeakRssBytes': max(peaks) if peaks else None}


def resource_acceptance(cli: Path, *, generated_files=4096, timeout=60) -> list[dict]:
    """Generated inert fixtures only; never execute their text as programs."""
    if type(generated_files) is not int or not 1 <= generated_files <= 4999: raise ValueError('Invalid generated fixture size')
    with tempfile.TemporaryDirectory(prefix='wg-resource-fixtures-') as temporary:
        owned = Path(temporary); results = []
        regular = owned/'regular'; regular.mkdir()
        for index in range(generated_files): (regular/f'script-{index:05}.sh').write_text('# Static fixture only; never run.\n# bounded regular text\n')
        result = scan(cli, regular, timeout); passed = result['complete'] and result['scannedFiles'] == generated_files
        results.append({'case': 'generated-regular-'+str(generated_files), 'expected': 'complete', 'passed': passed, **result})
        oversized = owned/'oversized'; oversized.mkdir()
        (oversized/'large.sh').write_bytes(b'#'+b' ' * 1_000_000)
        result = scan(cli, oversized, timeout)
        results.append({'case': 'generated-byte-cap', 'expected': 'incomplete-size-notice',
            'passed': not result['complete'] and result['exitCode'] != 0 and 'WG-FILE-SKIPPED' in result['noticeCodes'], **result})
        file_cap = owned/'file-cap'; file_cap.mkdir()
        for index in range(5001): (file_cap/f'input-{index:05}.sh').write_text('# inert\n')
        result = scan(cli, file_cap, timeout)
        results.append({'case': 'generated-file-seal-cap', 'expected': 'incomplete-or-invalid-never-clean',
            'passed': not result['complete'] and result['exitCode'] != 0, **result})
        depth_cap = owned/'depth-cap'; depth_cap.mkdir(); nested = depth_cap
        for _ in range(66): nested /= 'nested'; nested.mkdir()
        (nested/'input.sh').write_text('# inert\n')
        result = scan(cli, depth_cap, timeout)
        results.append({'case': 'generated-depth-cap', 'expected': 'incomplete-or-invalid-never-clean',
            'passed': not result['complete'] and result['exitCode'] != 0, **result})
        result = scan(cli, regular, .01)
        results.append({'case': 'generated-cancellation', 'expected': 'cancelled-incomplete',
            'passed': result['processStatus'] == 'CANCELLED' and not result['complete'] and result['elapsedMs']<3000, **result})
        return results


def _digest(path, maximum=128*1024*1024):
    digest = hashlib.sha256(); count = 0
    with path.open('rb') as stream:
        while data := stream.read(131072):
            count += len(data)
            if count > maximum: raise ValueError('Benchmark artifact exceeds metadata limit')
            digest.update(data)
    return digest.hexdigest()


def tool_metadata(cli):
    measured, raw = measured_command([str(cli), '--version'], 10, maximum=4096)
    version = raw.decode('ascii', 'ignore').strip()
    if measured['exitCode'] != 0 or not re.fullmatch(r'WrapperGuard [0-9A-Za-z.+_-]{1,64}', version): version = 'unknown'
    libraries = []
    directory = cli.parent.parent/'lib' if cli.parent.name == 'bin' else cli.parent/'__no_distribution_lib__'
    if directory.is_dir():
        for path in sorted(directory.glob('*.jar')):
            if len(libraries)>=100 or path.is_symlink() or not path.is_file(): raise ValueError('Unsupported benchmark library inventory')
            libraries.append({'name': path.name, 'sha256': _digest(path)})
    return {'version': version, 'launcherSha256': _digest(cli, 1024*1024), 'libraries': libraries}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cli', required=True, type=Path)
    parser.add_argument('--project', action='append', default=[], metavar='LABEL=/ABSOLUTE/ROOT')
    parser.add_argument('--providers', type=Path, help='Explicit trusted provider config outside every project; enabled adapters execute as configured')
    parser.add_argument('--methods', type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--repetitions', type=int, default=3)
    parser.add_argument('--warmups', type=int, default=1)
    parser.add_argument('--timeout-seconds', type=float, default=120)
    parser.add_argument('--acceptance', action='store_true', help='Run generated inert large/capped/cancelled fixtures with native defaults')
    parser.add_argument('--generated-files', type=int, default=4096)
    parser.add_argument('--max-elapsed-seconds', type=float)
    parser.add_argument('--max-peak-rss-mib', type=float)
    args = parser.parse_args(argv)
    if (not args.cli.is_absolute() or not args.cli.is_file() or not os.access(args.cli, os.X_OK) or
            not args.output.is_absolute() or args.output.exists() or not 1<=args.repetitions<=20 or not 0<=args.warmups<=5 or
            not math.isfinite(args.timeout_seconds) or not .001<=args.timeout_seconds<=600 or len(args.project)>32 or
            not args.project and not args.acceptance): parser.error('Choose an installed CLI, explicit roots/acceptance and a new absolute output file')
    cli = args.cli.resolve(strict=True); projects = {}; roots = []
    for item in args.project:
        label, separator, value = item.partition('=')
        if not separator or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,79}', label) or label in projects or not Path(value).is_absolute():
            parser.error('Use unique simple project labels and absolute project roots')
        root = Path(value).resolve(strict=True)
        if not root.is_dir() or cli.is_relative_to(root): parser.error('The reviewed CLI must be installed outside every scanned project')
        projects[label] = root; roots.append(root)
    for configuration in (args.providers, args.methods):
        if configuration is not None and (not configuration.is_absolute() or not configuration.is_file() or
                any(configuration.resolve().is_relative_to(root) for root in roots)):
            parser.error('User configuration must remain outside every selected project')
    if any(args.output.resolve().is_relative_to(root) for root in roots): parser.error('Benchmark output must be outside every scanned project')
    thresholds = (args.max_elapsed_seconds, args.max_peak_rss_mib)
    if any(value is not None and (not math.isfinite(value) or value<=0) for value in thresholds): parser.error('Thresholds must be positive finite numbers')
    tool_before = tool_metadata(cli)
    configuration_before = {'providersSha256': _digest(args.providers) if args.providers else None,
                            'methodsSha256': _digest(args.methods) if args.methods else None}
    cases = {label: [] for label in projects}; generator = random.Random(0)
    for index in range(args.warmups+args.repetitions):
        order = list(projects); generator.shuffle(order)
        for label in order:
            result = scan(cli, projects[label], args.timeout_seconds, args.providers, args.methods)
            cases[label].append({'iteration': index+1, 'warmup': index<args.warmups, **result})
    acceptance = resource_acceptance(cli, generated_files=args.generated_files, timeout=args.timeout_seconds) if args.acceptance else []
    violations = []
    if tool_metadata(cli) != tool_before: violations.append({'case': 'tool', 'reason': 'CLI_ARTIFACT_CHANGED'})
    configuration_after = {'providersSha256': _digest(args.providers) if args.providers else None,
                           'methodsSha256': _digest(args.methods) if args.methods else None}
    if configuration_before != configuration_after: violations.append({'case': 'configuration', 'reason': 'USER_CONFIG_CHANGED'})
    for label, runs in cases.items():
        for run in runs:
            if run['warmup']: continue
            if not run['measurementComplete']: violations.append({'case': label, 'reason': 'MEASUREMENT_INCOMPLETE'})
            if args.max_elapsed_seconds is not None and run['elapsedMs']>args.max_elapsed_seconds*1000:
                violations.append({'case': label, 'reason': 'ELAPSED_BUDGET'})
            if args.max_peak_rss_mib is not None and (run['peakRssBytes'] is None or run['peakRssBytes']>args.max_peak_rss_mib*1024*1024):
                violations.append({'case': label, 'reason': 'RSS_BUDGET'})
    for result in acceptance:
        if not result['passed']: violations.append({'case': result['case'], 'reason': 'RESOURCE_BOUNDARY_REGRESSION'})
        if result['processStatus'] == 'FINISHED' and not result['measurementComplete']:
            violations.append({'case': result['case'], 'reason': 'MEASUREMENT_INCOMPLETE'})
        if args.max_elapsed_seconds is not None and result['elapsedMs']>args.max_elapsed_seconds*1000:
            violations.append({'case': result['case'], 'reason': 'ELAPSED_BUDGET'})
        if args.max_peak_rss_mib is not None and result['processStatus'] == 'FINISHED' and (
                result['peakRssBytes'] is None or result['peakRssBytes']>args.max_peak_rss_mib*1024*1024):
            violations.append({'case': result['case'], 'reason': 'RSS_BUDGET'})
    report = {'schemaVersion': 1, 'generatedAt': datetime.now(timezone.utc).isoformat(),
        'platform': {'system': platform.system(), 'release': platform.release(), 'machine': platform.machine(), 'logicalCpus': os.cpu_count()},
        'tool': tool_before, 'scope': 'explicit-user-providers' if args.providers else 'native-builtins-only',
        'configuration': configuration_before,
        'measurement': {'clock': 'monotonic wall time including process/JVM startup', 'rss': 'OS time maximum resident set size normalized to bytes; not summed concurrent-process memory',
            'warmups': 'Separate process runs warm OS caches, not a persistent JVM', 'projectCodeExecutedByHarness': False,
            'sourceSastCoverage': 'Native builtins cover wrapper/build/script configuration. Python/Kotlin/Java application coverage requires explicitly configured providers.'},
        'cases': [{'label': label, 'aggregate': aggregate(runs), 'runs': runs} for label,runs in cases.items()],
        'acceptance': acceptance, 'violations': violations, 'passed': not violations,
        'passMeaning': 'Resource measurement/acceptance only. Partial scan coverage stays incomplete and is never security approval.',
        'allProjectScansComplete': all(run['complete'] for runs in cases.values() for run in runs if not run['warmup'])}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open('x', encoding='utf-8') as output: json.dump(report, output, indent=2, allow_nan=False); output.write('\n')
    print(json.dumps({'cases': len(cases), 'resourceChecks': len(acceptance), 'passed': not violations}))
    return 0 if not violations else 1


if __name__ == '__main__':
    try: raise SystemExit(main())
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError):
        print('Benchmark failed; review the explicit CLI, project/config paths and resource measurement access.', file=sys.stderr)
        raise SystemExit(1)
    except KeyboardInterrupt:
        print('Benchmark cancelled; active process group stopped.', file=sys.stderr)
        raise SystemExit(130)
