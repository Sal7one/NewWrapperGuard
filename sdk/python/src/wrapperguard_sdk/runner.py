"""The only active integration: installed Bandit over an inert, bounded snapshot."""
from __future__ import annotations

import os
from pathlib import Path
import selectors
import stat
import subprocess
import tempfile
import time

from .profile import IntegrationProfile, load_json_document

MAX_SOURCE_FILES = 5_000
MAX_SOURCE_FILE_BYTES = 1024 * 1024
MAX_SOURCE_BYTES = 32 * 1024 * 1024
MAX_SOURCE_DEPTH = 64
EXCLUDED_DIRECTORIES = frozenset({".git", ".hg", ".svn", ".venv", "venv", "env", "node_modules",
                                  "__pycache__", "build", "dist", ".tox", ".nox"})


def _check_deadline(deadline: float) -> None:
    if time.monotonic() >= deadline:
        raise TimeoutError("Integration deadline exceeded")


def _snapshot(project_root: Path, destination: Path, deadline: float) -> tuple[int, bool]:
    """Copy source data via anchored no-follow descriptors, never project programs."""
    count, total, incomplete = 0, 0, False
    flags = os.O_RDONLY | os.O_NOFOLLOW
    directory_flags = flags | os.O_DIRECTORY

    def walk(directory: int, parts: tuple[str, ...]) -> None:
        nonlocal count, total, incomplete
        _check_deadline(deadline)
        if len(parts) > MAX_SOURCE_DEPTH:
            incomplete = True
            return
        with os.scandir(directory) as entries:
            for entry in entries:
                _check_deadline(deadline)
                if count >= MAX_SOURCE_FILES or total >= MAX_SOURCE_BYTES:
                    incomplete = True
                    return
                if entry.name in EXCLUDED_DIRECTORIES:
                    continue
                try:
                    if entry.is_symlink():
                        # Directory targets may hide Python inputs; do not follow them.
                        incomplete = True
                        continue
                    relative = (*parts, entry.name)
                    if entry.is_dir(follow_symlinks=False):
                        child = os.open(entry.name, directory_flags, dir_fd=directory)
                        try:
                            walk(child, relative)
                        finally:
                            os.close(child)
                    elif entry.name.endswith(".py") and entry.is_file(follow_symlinks=False):
                        fd = os.open(entry.name, flags | os.O_NONBLOCK, dir_fd=directory)
                        with os.fdopen(fd, "rb") as source:
                            if not stat.S_ISREG(os.fstat(source.fileno()).st_mode):
                                incomplete = True
                                continue
                            data = source.read(MAX_SOURCE_FILE_BYTES + 1)
                        if len(data) > MAX_SOURCE_FILE_BYTES or total + len(data) > MAX_SOURCE_BYTES:
                            incomplete = True
                            continue
                        output = destination.joinpath(*relative)
                        output.parent.mkdir(parents=True, exist_ok=True)
                        with output.open("xb") as target:
                            target.write(data)
                        output.chmod(0o400)
                        count += 1
                        total += len(data)
                except OSError:
                    incomplete = True

    fd = os.open(project_root, directory_flags)
    try:
        walk(fd, ())
    finally:
        os.close(fd)
    return count, incomplete


def _stop_child(process: subprocess.Popen) -> None:
    # Keep the host's process group: when the host cancels this SDK with SIGKILL,
    # Bandit must be included in the same group rather than becoming an orphan.
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=0.5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=0.5)


def _bounded_process(argv: list[str], cwd: Path, environment: dict[str, str],
                     deadline: float, maximum: int) -> bytes:
    _check_deadline(deadline)
    process = subprocess.Popen(argv, cwd=cwd, env=environment, stdin=subprocess.DEVNULL,
                               stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    output = bytearray()
    selector = selectors.DefaultSelector()
    try:
        assert process.stdout is not None
        descriptor = process.stdout.fileno()
        os.set_blocking(descriptor, False)
        selector.register(descriptor, selectors.EVENT_READ)
        stream_open = True
        while process.poll() is None or stream_open:
            _check_deadline(deadline)
            for key, _ in selector.select(min(0.02, max(0, deadline - time.monotonic()))):
                data = os.read(key.fd, min(65_536, maximum - len(output) + 1))
                if not data:
                    selector.unregister(key.fd)
                    stream_open = False
                else:
                    output.extend(data)
                    if len(output) > maximum:
                        raise ValueError("Tool output exceeds limit")
        if process.returncode != 0:
            raise ValueError("Tool failed")
        return bytes(output)
    finally:
        _stop_child(process)
        selector.close()
        if process.stdout is not None:
            process.stdout.close()


def run_bandit(profile: IntegrationProfile, project_root: Path) -> tuple[dict, list[dict]]:
    """Analyze static .py data; no project imports, dependency install or active network scan."""
    if os.name != "posix" or not hasattr(os, "O_NOFOLLOW") or profile.runner is None:
        raise ValueError("The active Bandit runner requires POSIX no-follow file access")
    config = profile.runner
    deadline = time.monotonic() + config.timeout_ms / 1000
    with tempfile.TemporaryDirectory(prefix="wrapperguard-bandit-") as temporary:
        owned = Path(temporary)
        snapshot = owned / "source"
        snapshot.mkdir(mode=0o700)
        count, incomplete = _snapshot(project_root, snapshot, deadline)
        notices = []
        if incomplete:
            notices.append({"code": "WG-SDK-PARTIAL-SNAPSHOT", "message":
                            "Some source inputs were skipped because of symlinks, read failures or snapshot limits."})
        if count == 0:
            notices.append({"code": "WG-SDK-NO-PYTHON-INPUTS", "message":
                            "No regular Python source files were available in the configured snapshot scope."})
            return {"results": [], "errors": [], "metrics": {}}, notices
        ini, yaml = owned / "bandit.ini", owned / "bandit.yaml"
        ini.write_text("[bandit]\n", encoding="utf-8")
        yaml.write_text("{}\n", encoding="utf-8")
        home = owned / "home"
        home.mkdir(mode=0o700)
        environment = {"PATH": os.defpath, "HOME": str(home), "TMPDIR": str(owned),
                       "TEMP": str(owned), "TMP": str(owned), "PYTHONNOUSERSITE": "1",
                       "PYTHONDONTWRITEBYTECODE": "1", "PYTHONSAFEPATH": "1", "PYTHONUTF8": "1"}
        argv = [str(config.executable), "--ini", str(ini), "--configfile", str(yaml),
                "--ignore-nosec", "--exit-zero", "-q", "-f", "json", "-r", "."]
        raw = _bounded_process(argv, snapshot, environment, deadline, config.max_output_bytes)
        document = load_json_document(raw)
        if not isinstance(document, dict):
            raise ValueError("Invalid Bandit document")
        return document, notices
