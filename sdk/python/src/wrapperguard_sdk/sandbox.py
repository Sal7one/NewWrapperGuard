"""Explicit Docker isolation backend for bounded snapshots, never a host fallback.

The Docker daemon and host kernel are trusted. Rootless mode is preferred; a rootful
daemon requires a separate explicit acknowledgement. Containers are not a VM or a
guarantee that real malware is safe to run. Acceptance uses controlled inert probes.
No image pull, Docker installation, daemon startup or repository Dockerfile runs.

https://docs.docker.com/engine/security/rootless/
https://docs.docker.com/engine/security/seccomp/
https://docs.docker.com/reference/cli/docker/container/run/
"""
from __future__ import annotations

from dataclasses import dataclass
import json
import math
import os
from pathlib import Path, PurePosixPath
import re
import signal
import stat
import threading
import time
import uuid

from .portable_process import bounded_process, ToolCancelledError


@dataclass(frozen=True)
class ContainerSandbox:
    executable: Path
    endpoint: Path
    image: str
    allow_rootful_daemon: bool = False
    memory_mib: int = 256
    cpus: int = 1
    pids_limit: int = 32


def _image(value) -> bool:
    return isinstance(value, str) and re.fullmatch(r'(?:[a-z0-9][a-z0-9._:/-]{0,199}@)?sha256:[0-9a-f]{64}', value) is not None


def image_executable(value: object) -> Path:
    if (not isinstance(value, str) or not value.startswith('/') or len(value) > 1024 or
            any(ord(c) < 33 or c in '\\,' for c in value) or
            any(part in ('', '.', '..') for part in value.split('/')[1:])):
        raise ValueError('A normalized absolute executable inside the reviewed image is required')
    return Path(value)


def parse_sandbox(value: object, root: Path) -> ContainerSandbox:
    allowed = {'engine', 'executable', 'endpoint', 'image', 'allowRootfulDaemon', 'memoryMiB', 'cpus', 'pidsLimit'}
    if (not isinstance(value, dict) or set(value)-allowed or
            not {'engine', 'executable', 'endpoint', 'image'} <= value.keys() or value['engine'] != 'docker' or
            not _image(value['image']) or os.name != 'posix'):
        raise ValueError('Sandbox requires an explicit Docker engine, local socket and immutable image on POSIX')
    selected = []
    for key in ('executable', 'endpoint'):
        name = value[key]
        if not isinstance(name, str) or not Path(name).is_absolute() or len(name) > 4096 or any(ord(c)<32 for c in name):
            raise ValueError('Sandbox engine and socket must use absolute user paths')
        actual = Path(name).resolve(strict=True)
        if actual.is_relative_to(root.resolve(strict=True)):
            raise ValueError('Sandbox engine and socket must remain outside the project')
        selected.append(actual)
    executable, endpoint = selected
    if not executable.is_file() or not os.access(executable, os.X_OK) or not stat.S_ISSOCK(endpoint.stat().st_mode):
        raise ValueError('Sandbox requires an installed Docker CLI and existing local Unix socket')
    allow = value.get('allowRootfulDaemon', False); memory = value.get('memoryMiB', 256)
    cpus = value.get('cpus', 1); pids = value.get('pidsLimit', 32)
    if (type(allow) is not bool or type(memory) is not int or not 64 <= memory <= 2048 or
            type(cpus) is not int or not 1 <= cpus <= 2 or type(pids) is not int or not 8 <= pids <= 128):
        raise ValueError('Invalid sandbox resource limits')
    return ContainerSandbox(executable, endpoint, value['image'], allow, memory, cpus, pids)


def _client(sandbox, owned):
    configuration = owned / 'engine-client'; configuration.mkdir(mode=0o700)
    home = configuration / 'home'; home.mkdir(mode=0o700)
    docker = configuration / 'docker'; docker.mkdir(mode=0o700)
    return [str(sandbox.executable), '--host', 'unix://' + str(sandbox.endpoint)], {
        'PATH': os.defpath, 'HOME': str(home), 'DOCKER_CONFIG': str(docker),
        'DOCKER_CLI_HINTS': 'false', 'LC_ALL': 'C', 'LANG': 'C'}


def _stage_mount(owned: Path, deadline: float) -> Path:
    # World-readable container inputs stay behind a private parent on the host.
    # Bind mounting the child gives the nonroot worker access without exposing
    # the original snapshot, client state or temporary directory to local users.
    owned.chmod(0o700)
    stage = owned / 'container-inputs'; stage.mkdir(mode=0o700)
    entries = total = 0
    def copy(source, destination, depth):
        nonlocal entries, total
        entries += 1
        if time.monotonic() >= deadline: raise TimeoutError('Container staging deadline exceeded')
        if entries > 20000 or depth > 68: raise ValueError('Container staging exceeds limits')
        if source.is_symlink(): raise ValueError('Owned container inputs cannot contain links')
        if source.is_dir():
            destination.mkdir(mode=0o755)
            for child in source.iterdir(): copy(child, destination/child.name, depth+1)
            return
        descriptor = os.open(source, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        with os.fdopen(descriptor, 'rb') as stream:
            before = os.fstat(stream.fileno())
            if not stat.S_ISREG(before.st_mode): raise ValueError('Owned container inputs must be regular files')
            data = stream.read(16*1024*1024+1)
            after = os.fstat(stream.fileno())
        total += len(data)
        if (len(data)>16*1024*1024 or total>64*1024*1024 or
                (before.st_dev,before.st_ino,before.st_size,before.st_mtime_ns,before.st_ctime_ns) !=
                (after.st_dev,after.st_ino,after.st_size,after.st_mtime_ns,after.st_ctime_ns)):
            raise ValueError('Container staging changed or exceeds limits')
        with destination.open('xb') as output: output.write(data)
        destination.chmod(0o444)
    for source in owned.iterdir():
        if source.name not in {'engine-client', 'container-inputs'}:
            copy(source, stage/source.name, 0)
    stage.chmod(0o755)
    return stage


def _translate(argument: str, owned: Path) -> str:
    if argument == str(owned): return '/workspace'
    prefix = str(owned) + '/'
    return '/workspace/' + argument[len(prefix):] if argument.startswith(prefix) else argument


def run_container(sandbox: ContainerSandbox, argv: list[str], owned: Path, cwd: Path,
                  deadline: float, maximum: int, accepted_codes=(0,), *, cancelled=None) -> bytes:
    """Run one fixed reviewed argv in a local no-network resource-limited container.

    Normal cancellation removes the named container and verifies absence. SIGKILL
    cannot run Python cleanup; a separate-UID timer and auto-removal provide an
    independent stop boundary that must pass actual platform acceptance tests.
    A separate UID0 timer/bootstrap has only KILL/SETUID/SETGID. The bootstrap drops
    all worker IDs/groups/capabilities before starting input analysis. No credentials,
    engine socket, original host directory or ambient Docker options enter the
    container. The daemon must already have the immutable image installed.
    """
    if (os.name != 'posix' or not _image(sandbox.image) or not math.isfinite(deadline) or
            not argv or not isinstance(argv[0], str) or type(sandbox.allow_rootful_daemon) is not bool or
            type(sandbox.memory_mib) is not int or not 64 <= sandbox.memory_mib <= 2048 or
            type(sandbox.cpus) is not int or not 1 <= sandbox.cpus <= 2 or
            type(sandbox.pids_limit) is not int or not 8 <= sandbox.pids_limit <= 128):
        raise ValueError('Invalid container execution boundary')
    image_executable(argv[0])
    owned = owned.resolve(strict=True); cwd = cwd.resolve(strict=True)
    if not cwd.is_relative_to(owned) or ',' in str(owned) or not owned.is_dir():
        raise ValueError('Container work directory must be inside the owned snapshot')
    owned.chmod(0o700)
    if not stat.S_ISSOCK(sandbox.endpoint.stat().st_mode): raise ValueError('Local Docker socket is unavailable')
    client, environment = _client(sandbox, owned)
    def command(arguments, *, budget=None, limit=16384, codes=(0,), cancellation=cancelled):
        stop = min(deadline, time.monotonic()+budget) if budget is not None else deadline
        return bounded_process(client + arguments, owned, environment, stop, limit, codes, cancelled=cancellation)
    from .profile import load_json_document
    info = load_json_document(command(['info', '--format', '{"os":{{json .OSType}},"security":{{json .SecurityOptions}},"version":{{json .ServerVersion}},"limits":{"memory":{{json .MemoryLimit}},"swap":{{json .SwapLimit}},"cpu":{{json .CPUCfsQuota}},"pids":{{json .PidsLimit}},"cgroup":{{json .CgroupVersion}},"driver":{{json .CgroupDriver}}}}'], budget=5))
    if (not isinstance(info, dict) or set(info) != {'os', 'security', 'version', 'limits'} or info['os'] != 'linux' or
            not isinstance(info['version'], str) or not re.match(r'^(?:2[0-9]|[3-9][0-9])\.', info['version']) or
            not isinstance(info['security'], list) or len(info['security']) > 32 or
            any(not isinstance(item, str) or len(item)>512 for item in info['security']) or
            not any('name=seccomp' in item and 'unconfined' not in item for item in info['security'])):
        raise ValueError('Sandbox requires a supported Linux Docker engine with seccomp')
    limits = info['limits']
    rootless = any('name=rootless' in item for item in info['security'])
    if (not isinstance(limits, dict) or set(limits) != {'memory', 'swap', 'cpu', 'pids', 'cgroup', 'driver'} or
            any(limits[key] is not True for key in ('memory', 'swap', 'cpu', 'pids')) or
            rootless and (limits['cgroup'] != '2' or limits['driver'] != 'systemd')):
        raise ValueError('Docker cannot enforce the required memory, CPU and PID limits')
    if not sandbox.allow_rootful_daemon and not rootless:
        raise ValueError('Rootful Docker requires explicit separate acknowledgement')
    image = load_json_document(command(['image', 'inspect', '--format', '{"id":{{json .Id}},"digests":{{json .RepoDigests}},"volumes":{{json .Config.Volumes}}}', sandbox.image], budget=5))
    if not isinstance(image, dict) or set(image) != {'id', 'digests', 'volumes'} or image['volumes'] not in (None, {}):
        raise ValueError('Reviewed image must not declare implicit writable volumes')
    if (sandbox.image.startswith('sha256:') and image['id'] != sandbox.image or
            '@sha256:' in sandbox.image and (not isinstance(image['digests'], list) or sandbox.image not in image['digests'])):
        raise ValueError('Installed container image digest does not match')
    policy = owned / 'policy'; policy.mkdir(exist_ok=True)
    bootstrap = policy / 'container_worker.py'
    bootstrap.write_bytes(Path(__file__).with_name('container_worker.py').read_bytes())
    mount = _stage_mount(owned, deadline)
    name = 'wrapperguard-' + uuid.uuid4().hex
    remaining = deadline - time.monotonic()
    if remaining <= .1: raise TimeoutError('Container deadline exceeded')
    ttl = f'{remaining:.3f}s'
    arguments = ['run', '--rm', '--pull', 'never', '--name', name, '--label', 'wrapperguard.ephemeral=true',
        '--read-only', '--network', 'none', '--cap-drop', 'ALL', '--cap-add', 'KILL', '--cap-add', 'SETUID', '--cap-add', 'SETGID',
        '--security-opt', 'no-new-privileges=true', '--user', '0:0', '--init', '--pids-limit', str(sandbox.pids_limit), '--cpus', str(sandbox.cpus),
        '--memory', str(sandbox.memory_mib)+'m', '--memory-swap', str(sandbox.memory_mib)+'m',
        '--ulimit', 'nofile=256:256', '--shm-size', '8m', '--no-healthcheck', '--log-driver', 'none',
        '--tmpfs', '/tmp:rw,noexec,nosuid,nodev,size=64m,mode=1777', '--mount',
        'type=bind,src='+str(mount)+',dst=/workspace,readonly,bind-propagation=rprivate',
        '--workdir', _translate(str(cwd), owned), '--entrypoint', '/usr/bin/timeout']
    for key, value in {'HOME': '/tmp', 'PATH': '/usr/local/bin:/usr/bin:/bin', 'PYTHONNOUSERSITE': '1',
                       'PYTHONDONTWRITEBYTECODE': '1', 'PYTHONSAFEPATH': '1', 'PYTHONUTF8': '1',
                       'WRAPPERGUARD_CONTAINER_AGENT': '1', 'SEMGREP_SEND_METRICS': 'off'}.items():
        arguments.extend(['--env', key+'='+value])
    arguments.extend([sandbox.image, '--signal=KILL', '--kill-after=1', ttl, '/usr/local/bin/python', '-I', '-B',
                      '/workspace/policy/container_worker.py', *[_translate(a, owned) for a in argv]])
    previous = None
    def terminate_signal(signum, frame): raise ToolCancelledError('Container invocation cancelled')
    if threading.current_thread() is threading.main_thread():
        previous = signal.signal(signal.SIGTERM, terminate_signal)
    try:
        return command(arguments, limit=maximum, codes=accepted_codes)
    finally:
        try:
            cleanup = time.monotonic()+5
            bounded_process(client+['rm', '--force', '--volumes', name], owned, environment, cleanup, 4096, (0, 1))
            result = bounded_process(client+['ps', '--all', '--no-trunc', '--filter', 'name=^/'+name+'$', '--format', '{{.ID}}'], owned,
                                     environment, cleanup, 4096)
            if result.strip(): raise ValueError('Container cleanup could not be verified')
        finally:
            if previous is not None: signal.signal(signal.SIGTERM, previous)
