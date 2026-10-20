"""Bounded pipe capture for trusted fixed runners; not a subprocess security sandbox.

Windows children are assigned atomically to a kill-on-close Job Object, including
their descendants. POSIX children inherit the host's process group so host group
cancellation still reaches them; local POSIX cleanup stops the immediate process.

Windows pipe contract:
https://learn.microsoft.com/en-us/windows/win32/api/namedpipeapi/nf-namedpipeapi-peeknamedpipe
"""
from __future__ import annotations

import math
import os
from pathlib import Path
import select
import subprocess
import time
from typing import Callable


class ToolCancelledError(InterruptedError):
    """The host cancelled an explicitly configured tool invocation."""


def _stop_process(process: subprocess.Popen) -> None:
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=0.5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=0.5)


class _WindowsPipe:
    def __init__(self, descriptor: int):
        import ctypes
        import msvcrt
        from ctypes import wintypes
        self.ctypes = ctypes
        self.wintypes = wintypes
        self.handle = msvcrt.get_osfhandle(descriptor)
        self.kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        self.kernel.PeekNamedPipe.argtypes = [wintypes.HANDLE, wintypes.LPVOID, wintypes.DWORD,
                                             ctypes.POINTER(wintypes.DWORD), ctypes.POINTER(wintypes.DWORD),
                                             ctypes.POINTER(wintypes.DWORD)]
        self.kernel.PeekNamedPipe.restype = wintypes.BOOL

    def available(self) -> int | None:
        count = self.wintypes.DWORD()
        if not self.kernel.PeekNamedPipe(self.handle, None, 0, None, self.ctypes.byref(count), None):
            if self.ctypes.get_last_error() in (109, 232):  # Broken/closed anonymous pipe.
                return None
            raise OSError("Tool pipe could not be inspected")
        return int(count.value)


def bounded_process(argv: list[str], cwd: Path, environment: dict[str, str], deadline: float,
                    maximum: int, accepted_codes: tuple[int, ...] = (0,), *,
                    cancelled: Callable[[], bool] | None = None) -> bytes:
    """Capture bounded stdout without select-on-Windows-pipes or background reader leaks.

    Only one reader owns this anonymous pipe. Windows checks the available byte count
    before a synchronous read; POSIX uses a nonblocking descriptor. No shell expansion,
    inherited stdin, provider stderr, command arguments or source is included in errors.
    The caller supplies its reviewed environment and a monotonic absolute deadline.
    """
    if (not isinstance(maximum, int) or not 1 <= maximum <= 16 * 1024 * 1024 or
            not isinstance(deadline, (int, float)) or not math.isfinite(deadline) or
            not argv or len(argv) > 128 or any(not isinstance(v, str) or "\x00" in v or len(v) > 32768 for v in argv) or
            not Path(argv[0]).is_absolute() or not accepted_codes or len(accepted_codes) > 16):
        raise ValueError("Invalid bounded tool invocation")

    def check() -> None:
        if cancelled is not None and cancelled():
            raise ToolCancelledError("Tool invocation was cancelled")
        if time.monotonic() >= deadline:
            raise TimeoutError("Integration deadline exceeded")

    check()
    if os.name == "nt":
        from .windows_job import WindowsJobProcess
        process = WindowsJobProcess(argv, cwd, environment)
    else:
        process = subprocess.Popen(argv, cwd=cwd, env=environment, stdin=subprocess.DEVNULL,
                                   stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, bufsize=0,
                                   close_fds=True)
    assert process.stdout is not None
    descriptor = process.stdout.fileno()
    output = bytearray()
    try:
        windows = _WindowsPipe(descriptor) if os.name == "nt" else None
        if windows is None:
            os.set_blocking(descriptor, False)
        stream_open = True
        while stream_open or process.poll() is None:
            check()
            data: bytes | None = None
            if stream_open:
                remaining = min(65536, maximum - len(output) + 1)
                if windows is not None:
                    available = windows.available()
                    if available is None:
                        data = b""
                    elif available:
                        data = os.read(descriptor, min(remaining, available))
                elif select.select([descriptor], [], [], min(0.02, max(0.0, deadline - time.monotonic())))[0]:
                    try:
                        data = os.read(descriptor, remaining)
                    except BlockingIOError:
                        pass
                if data == b"":
                    stream_open = False
                elif data is not None:
                    output.extend(data)
                    if len(output) > maximum:
                        raise ValueError("Tool output exceeds limit")
            if (windows is not None or not stream_open) and data is None:
                time.sleep(min(0.02, max(0.0, deadline - time.monotonic())))
        if process.returncode not in accepted_codes:
            raise ValueError("Tool failed")
        return bytes(output)
    finally:
        try:
            _stop_process(process)
        finally:
            if os.name == "nt":
                process.close()  # Also terminates descendants after an exited parent.
            else:
                process.stdout.close()
