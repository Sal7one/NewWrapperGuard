"""Container-only controlled Python observation helper; never run payloads on a host.

Python audit hooks are bypassable and are not the sandbox. Docker namespaces,
read-only mounts, no network, dropped capabilities and resource caps are the outer
boundary. Observations are low-confidence advisory signals without policy authority.
No arguments, source, stdout/stderr, paths, credentials or payload data are reported.

https://docs.python.org/3/library/sys.html#sys.addaudithook
"""
from __future__ import annotations

import json
import os
from pathlib import Path
import selectors
import signal
import subprocess
import sys
import time

EVENTS = frozenset({'network-attempt', 'process-attempt', 'file-write-attempt', 'native-load-attempt', 'dynamic-code'})


def _guard():
    if (os.name != 'posix' or os.environ.get('WRAPPERGUARD_CONTAINER_AGENT') != '1' or
            os.getuid() != 65532 or not Path('/.dockerenv').is_file()):
        raise ValueError('Dynamic observations require the isolated container runtime')
    values = dict(line.split(':', 1) for line in Path('/proc/self/status').read_text().splitlines() if ':' in line)
    if (int(values.get('CapEff', '1').strip(), 16) != 0 or values.get('NoNewPrivs', '').strip() != '1' or
            values.get('Seccomp', '').strip() != '2' or set(os.listdir('/sys/class/net')) != {'lo'}):
        raise ValueError('Required container isolation controls are unavailable')
    mounts = [line.split() for line in Path('/proc/mounts').read_text().splitlines()]
    for target in ('/', '/workspace'):
        if not any(len(fields) > 3 and fields[1] == target and 'ro' in fields[3].split(',') for fields in mounts):
            raise ValueError('Dynamic observations require read-only container inputs')


_CHILD = r'''
import os,sys,runpy,json
from pathlib import Path
entry,report = sys.argv[1],int(sys.argv[2])
def emit(name):
    os.write(report,(json.dumps({'event':name},separators=(',',':'))+'\n').encode('ascii'))
def observe(event,args):
    if event in ('socket.__new__','socket.connect'):
        emit('network-attempt'); raise PermissionError('Observed network attempt')
    if event in ('subprocess.Popen','os.system','os.exec','os.posix_spawn'):
        emit('process-attempt'); raise PermissionError('Observed process attempt')
    if event == 'ctypes.dlopen':
        emit('native-load-attempt'); raise PermissionError('Observed native load attempt')
    if event == 'open':
        mode = args[1] if len(args)>1 else None
        flags = args[2] if len(args)>2 and isinstance(args[2],int) else 0
        if isinstance(mode,str) and any(c in mode for c in 'wax+') or flags & (os.O_WRONLY|os.O_RDWR|os.O_CREAT|os.O_TRUNC):
            emit('file-write-attempt')
    if event == 'exec' and args and getattr(args[0],'co_filename','') in ('<string>','<eval>'):
        emit('dynamic-code')
sys.path.insert(0,str(Path(entry).parent))
sys.addaudithook(observe)
runpy.run_path(entry,run_name='__main__')
'''


def observe(entry: Path, timeout_ms: int) -> dict:
    _guard()
    if not entry.is_absolute() or not entry.is_relative_to(Path('/workspace/source')) or entry.suffix.lower() != '.py' or not entry.is_file():
        raise ValueError('A copied Python entry inside the container snapshot is required')
    if type(timeout_ms) is not int or not 100 <= timeout_ms <= 10000:
        raise ValueError('Invalid dynamic execution budget')
    read, write = os.pipe(); process = None; selector = selectors.DefaultSelector()
    events = {}; buffer = bytearray(); total = 0; status = 'finished'
    try:
        process = subprocess.Popen([sys.executable, '-I', '-B', '-c', _CHILD, str(entry), str(write)],
            stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            pass_fds=(write,), close_fds=True, start_new_session=True)
        os.close(write); write = None; os.set_blocking(read, False); selector.register(read, selectors.EVENT_READ)
        deadline = time.monotonic()+timeout_ms/1000; stream_open = True
        while stream_open or process.poll() is None:
            if time.monotonic() >= deadline: status = 'timeout'; break
            for _, _ in selector.select(min(.02, max(0, deadline-time.monotonic()))):
                chunk = os.read(read, 4096)
                if not chunk:
                    selector.unregister(read); stream_open = False; continue
                buffer.extend(chunk); total += len(chunk)
                if total > 16384: status = 'limit'; break
                while b'\n' in buffer:
                    line, _, remainder = buffer.partition(b'\n'); buffer = bytearray(remainder)
                    try: record = json.loads(line)
                    except (ValueError, UnicodeError): status = 'limit'; break
                    if (not isinstance(record, dict) or set(record) != {'event'} or
                            not isinstance(record['event'], str) or record['event'] not in EVENTS):
                        status = 'limit'; break
                    name = record['event']; events[name] = min(events.get(name, 0)+1, 1000)
                if status != 'finished': break
            if status != 'finished': break
        if status == 'finished' and (buffer or process.wait(timeout=.2) != 0): status = 'failed'
        return {'schemaVersion': 1, 'events': events, 'executionStatus': status,
                'complete': status == 'finished'}
    finally:
        if process is not None:
            try: os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError: pass
            try: process.wait(timeout=.5)
            except subprocess.TimeoutExpired: pass
        selector.close(); os.close(read)
        if write is not None: os.close(write)


def main():
    try:
        if len(sys.argv) != 3: raise ValueError('Explicit entry and execution budget required')
        result = observe(Path(sys.argv[1]), int(sys.argv[2]))
        print(json.dumps(result, separators=(',', ':'), allow_nan=False))
        return 0
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError):
        print('Container observation could not complete', file=sys.stderr)
        return 1


if __name__ == '__main__': raise SystemExit(main())
