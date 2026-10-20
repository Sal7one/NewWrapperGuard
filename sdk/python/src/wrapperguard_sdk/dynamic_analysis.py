"""Advisory observations from a separately isolated, explicitly confirmed container."""
from pathlib import Path

from .models import make_finding
from .profile import load_json_document
from .sandbox import run_container
from .container_agent import EVENTS


_TITLES = {'network-attempt': 'Container observed a network API attempt',
           'process-attempt': 'Container observed a process API attempt',
           'file-write-attempt': 'Container observed a file write attempt',
           'native-load-attempt': 'Container observed a native library load attempt',
           'dynamic-code': 'Container observed dynamic Python code execution'}


def analyze_container(profile, snapshot: Path, owned: Path, deadline: float) -> dict:
    config = profile.runner
    if config.tool != 'container-python' or config.execution_confirmed is not True or config.sandbox is None:
        raise ValueError('Dynamic observations require explicitly confirmed container execution')
    if (not isinstance(config.entry_path, str) or not config.entry_path or '\\' in config.entry_path or ':' in config.entry_path or
            any(part in ('', '.', '..') for part in config.entry_path.split('/'))):
        raise ValueError('Invalid relative dynamic entry')
    entry = snapshot.joinpath(*config.entry_path.split('/'))
    if not entry.is_file() or entry.is_symlink() or not entry.resolve().is_relative_to(snapshot.resolve()) or entry.suffix.lower() != '.py':
        raise ValueError('The reviewed dynamic entry was not present in the bounded source snapshot')
    policy = owned / 'policy'; policy.mkdir()
    agent = policy / 'container_agent.py'
    agent.write_bytes(Path(__file__).with_name('container_agent.py').read_bytes())
    raw = run_container(config.sandbox, [str(config.executable), '-I', '-B', str(agent), str(entry),
                         str(config.execution_timeout_ms)], owned, snapshot, deadline, config.max_output_bytes)
    document = load_json_document(raw)
    if (not isinstance(document, dict) or set(document) != {'schemaVersion', 'events', 'executionStatus', 'complete'} or
            type(document['schemaVersion']) is not int or document['schemaVersion'] != 1 or
            type(document['complete']) is not bool or document['executionStatus'] not in {'finished', 'failed', 'timeout', 'limit'} or
            document['complete'] != (document['executionStatus'] == 'finished') or
            not isinstance(document['events'], dict) or len(document['events']) > len(EVENTS) or
            any(key not in EVENTS or type(value) is not int or not 1 <= value <= 1000 for key, value in document['events'].items())):
        raise ValueError('Invalid bounded container observation result')
    findings = []
    for key, value in sorted(document['events'].items()):
        finding = make_finding('DYNAMIC-PYTHON/'+key, _TITLES[key],
            f'The isolated Python audit hook reported {value} {key} observations. Hooks can be bypassed; absence of events does not prove safety.',
            'INFO', 'LOW', 'AUDIT', config.entry_path, actions=[])
        finding['kind'] = 'AUDIT_EVIDENCE'; findings.append(finding)
    return {'findings': findings, 'scannedFiles': 1, 'complete': document['complete'], 'notices': [
        {'code': 'WG-DYNAMIC-ADVISORY', 'message':
         'Explicit copied Python entry executed as a nonroot worker inside a no-network resource-limited container. A separate trusted UID0 timer has only setup/termination capabilities. Bypassable audit hooks are observational, not a sandbox or host-approved exception. No payload arguments, output, credentials or source were returned.'},
        {'code': 'WG-DYNAMIC-STATUS', 'message': 'Container entry execution status: '+document['executionStatus']+'.'}]}
