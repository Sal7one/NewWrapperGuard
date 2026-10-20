#!/usr/bin/env python3
"""Explicit controlled-probe acceptance against an already running Linux Docker engine.

Never run a malware corpus here. Dynamic code is an inert owned test fixture copied
as data and executed only by the locked-down provider container. No Docker image is
built or pulled by this driver; CI/setup does that as a separate explicit action.
"""
import argparse
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time


def invoke(profile, root, owned, *, succeeds=True):
    path = owned / 'profile.json'; path.write_text(json.dumps(profile))
    process = subprocess.run([sys.executable, '-I', '-m', 'wrapperguard_sdk', '--profile', str(path)],
        input=json.dumps({'apiVersion': 1, 'projectPath': str(root), 'operation': 'SCAN'}),
        text=True, capture_output=True, timeout=45)
    if succeeds:
        assert process.returncode == 0, process.stderr[:500]
        return json.loads(process.stdout)
    assert process.returncode != 0 and not process.stdout, 'Failed container must not produce clean partial success'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--docker', required=True, type=Path)
    parser.add_argument('--endpoint', required=True, type=Path)
    parser.add_argument('--python-image', required=True)
    parser.add_argument('--static-image', required=True)
    parser.add_argument('--allow-rootful-daemon', action='store_true')
    args = parser.parse_args()
    assert args.docker.is_absolute() and args.endpoint.is_absolute()
    client = [str(args.docker), '--host', 'unix://'+str(args.endpoint)]
    def remaining():
        result = subprocess.run(client+['ps', '--all', '--filter', 'label=wrapperguard.ephemeral=true', '--format', '{{.ID}}'],
            check=True, text=True, capture_output=True, timeout=5)
        assert not result.stdout.strip(), 'Ephemeral provider containers must have been removed'
    with tempfile.TemporaryDirectory(prefix='wg-contained-acceptance-') as temporary:
        owned = Path(temporary).resolve(); root = owned / 'project'; root.mkdir()
        sentinel = owned / 'host-sentinel'; sentinel.write_text('HOST SENTINEL MUST NOT CHANGE')
        sandbox = {'engine': 'docker', 'executable': str(args.docker), 'endpoint': str(args.endpoint),
            'image': args.static_image, 'allowRootfulDaemon': args.allow_rootful_daemon,
            'memoryMiB': 1024, 'pidsLimit': 32, 'cpus': 1}
        (root / 'review.py').write_text('import subprocess\nif False:\n    subprocess.run("echo inert", shell=True) # nosec\n')
        profile = {'id': 'acceptance.container', 'displayName': 'Controlled isolated acceptance', 'version': '1',
            'analysisKind': 'SAST', 'format': 'bandit', 'runner': {'tool': 'bandit', 'executable': '/usr/local/bin/bandit',
            'timeoutMs': 30000, 'sandbox': sandbox}, 'requestedActions': ['WARNING']}
        result = invoke(profile, root, owned)
        assert result['complete'] and any(f['ruleId'] in ('B602', 'B607') for f in result['findings']), result
        remaining()
        (root / 'Application.kt').write_text('fun configure() { anyHost() }\n')
        profile['format'] = 'sarif'; profile['runner'].update({'tool': 'semgrep', 'executable': '/usr/local/bin/semgrep'})
        result = invoke(profile, root, owned)
        assert result['complete'] and any(f['ruleId'] == 'wrapperguard.ktor.unrestricted-cors' and f['location']['path'] == 'Application.kt' for f in result['findings']), result
        remaining()
        # Controlled probes, not malicious samples: denied operations are caught;
        # any successful file write is restricted to the container's disposable /tmp.
        (root / 'controlled.py').write_text('''import os,socket,subprocess,signal
from pathlib import Path
assert os.getuid() == 65532
assert not Path('/var/run/docker.sock').exists()
assert int(Path('/sys/fs/cgroup/memory.max').read_text()) <= 128*1024*1024
assert int(Path('/sys/fs/cgroup/pids.max').read_text()) <= 32
quota,period = map(int,Path('/sys/fs/cgroup/cpu.max').read_text().split())
assert quota <= period
parent = os.getppid()
found_timer = False
for step in range(8):
    if parent <= 1: break
    status = dict(line.split(':',1) for line in Path('/proc/'+str(parent)+'/status').read_text().splitlines() if ':' in line)
    if Path('/proc/'+str(parent)+'/comm').read_text().strip() == 'timeout':
        assert int(status['Uid'].split()[0]) == 0
        try: os.kill(parent,signal.SIGSTOP)
        except PermissionError: pass
        else: raise AssertionError('Untrusted worker stopped the trusted timer')
        found_timer = True
    parent = int(status['PPid'])
assert found_timer, 'A different-UID timeout ancestor must enforce the outer deadline'
for operation in (lambda: socket.socket(), lambda: subprocess.run(['/bin/true']), lambda: __import__('ctypes').CDLL(None),
                  lambda: Path('/workspace/source/should-not-write').write_text('inert')):
    try: operation()
    except (PermissionError,OSError): pass
Path('/tmp/controlled-inert-probe').write_text('inert')
exec('value = 1 + 1')
''')
        profile['format'] = 'container-python'; profile['runner'] = {'tool': 'container-python',
            'entryPath': 'controlled.py', 'executionConfirmed': True, 'executionTimeoutMs': 1000, 'timeoutMs': 10000,
            'sandbox': {**sandbox, 'image': args.python_image, 'memoryMiB': 128}}
        result = invoke(profile, root, owned)
        assert result['complete'], result
        expected = {'network-attempt', 'process-attempt', 'native-load-attempt', 'file-write-attempt', 'dynamic-code'}
        assert expected <= {f['ruleId'].split('/')[-1] for f in result['findings']}, result
        assert all(f['severity'] == 'INFO' and f['kind'] == 'AUDIT_EVIDENCE' and f['actions'] == [] for f in result['findings'])
        assert sentinel.read_text() == 'HOST SENTINEL MUST NOT CHANGE'
        assert not (root / 'should-not-write').exists(); remaining()
        # Budgeted benign loop verifies actual container cancellation and cleanup.
        (root / 'bounded-loop.py').write_text('while True:\n    pass\n')
        profile['runner']['entryPath'] = 'bounded-loop.py'; profile['runner']['executionTimeoutMs'] = 150
        result = invoke(profile, root, owned)
        assert not result['complete']; remaining()
        # Exercise host cancellation as well as the child's own deadline. SIGKILL
        # cannot enter Python finally; the independent container TTL must expire.
        for forced in (False, True):
            profile['runner']['executionTimeoutMs'] = 10000
            profile['runner']['timeoutMs'] = 4000
            path = owned / 'profile.json'; path.write_text(json.dumps(profile))
            host = subprocess.Popen([sys.executable, '-I', '-m', 'wrapperguard_sdk', '--profile', str(path)],
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, start_new_session=True)
            try:
                host.stdin.write(json.dumps({'apiVersion': 1, 'projectPath': str(root), 'operation': 'SCAN'})); host.stdin.close(); host.stdin = None
                stop = time.monotonic()+5; started = False
                while time.monotonic() < stop:
                    ids = subprocess.check_output(client+['ps', '--filter', 'label=wrapperguard.ephemeral=true', '--format', '{{.ID}}'], text=True, timeout=2)
                    if ids.strip(): started = True; break
                    if host.poll() is not None: break
                    time.sleep(.05)
                assert started, 'Container must start before SDK cancellation test'
                if forced: os.killpg(host.pid, signal.SIGKILL)
                else: host.terminate()
                stdout, stderr = host.communicate(timeout=8)
                assert host.returncode != 0 and not stdout, 'Cancelled SDK must not return a clean report'
                stop = time.monotonic()+7
                while True:
                    ids = subprocess.check_output(client+['ps', '--all', '--filter', 'label=wrapperguard.ephemeral=true', '--format', '{{.ID}}'], text=True, timeout=2)
                    if not ids.strip(): break
                    assert time.monotonic() < stop, 'Container exceeded independent cancellation TTL'
                    time.sleep(.05)
                remaining()
            finally:
                if host.poll() is None: os.killpg(host.pid, signal.SIGKILL); host.wait(timeout=2)
        # Adversarial controlled probe: stop the same-UID observation parent, try
        # to stop the different-UID timeout, then kill the SDK itself. Only the
        # trusted outer deadline can clean up this container after that point.
        (root / 'timer-attack.py').write_text('''import os,signal
from pathlib import Path
parent = os.getppid()
timer = parent
found = False
for step in range(8):
    if timer <= 1: break
    status = dict(line.split(':',1) for line in Path('/proc/'+str(timer)+'/status').read_text().splitlines() if ':' in line)
    if Path('/proc/'+str(timer)+'/comm').read_text().strip() == 'timeout':
        assert int(status['Uid'].split()[0]) == 0
        try: os.kill(timer,signal.SIGSTOP)
        except PermissionError: pass
        else: raise AssertionError('Trusted timer was stopped')
        found = True
    timer = int(status['PPid'])
assert found
os.kill(parent,signal.SIGSTOP)
Path('/tmp/timer-attack-ready').write_text('controlled probe reached')
while True: pass
''')
        profile['runner']['entryPath'] = 'timer-attack.py'
        profile['runner']['timeoutMs'] = 5000
        path = owned / 'profile.json'; path.write_text(json.dumps(profile))
        host = subprocess.Popen([sys.executable, '-I', '-m', 'wrapperguard_sdk', '--profile', str(path)],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, start_new_session=True)
        try:
            host.stdin.write(json.dumps({'apiVersion': 1, 'projectPath': str(root), 'operation': 'SCAN'})); host.stdin.close(); host.stdin = None
            stop = time.monotonic()+4; ready = False
            while time.monotonic()<stop:
                ids = subprocess.check_output(client+['ps', '--filter', 'label=wrapperguard.ephemeral=true', '--format', '{{.ID}}'], text=True, timeout=2).split()
                if ids:
                    check = subprocess.run(client+['exec', '--user', '65532:65532', ids[0], '/usr/bin/test', '-f', '/tmp/timer-attack-ready'],
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=2)
                    if check.returncode == 0: ready = True; break
                if host.poll() is not None: break
                time.sleep(.05)
            assert ready, 'Timer attack must reach SIGSTOP before SDK is killed'
            os.killpg(host.pid, signal.SIGKILL); stdout, stderr = host.communicate(timeout=3)
            assert host.returncode != 0 and not stdout
            stop = time.monotonic()+7
            while True:
                ids = subprocess.check_output(client+['ps', '--all', '--filter', 'label=wrapperguard.ephemeral=true', '--format', '{{.ID}}'], text=True, timeout=2)
                if not ids.strip(): break
                assert time.monotonic()<stop, 'Different-UID timeout failed after parent SIGSTOP and SDK SIGKILL'
                time.sleep(.05)
            remaining()
        finally:
            if host.poll() is None: os.killpg(host.pid, signal.SIGKILL); host.wait(timeout=2)
        profile['runner']['sandbox']['image'] = 'sha256:'+'0'*64
        invoke(profile, root, owned, succeeds=False); remaining()
        print('Real Docker SDK: isolated Bandit/Semgrep, controlled Python events, readonly host boundary, no authority, budgets, missing-image failure and cleanup passed.')


if __name__ == '__main__': main()
