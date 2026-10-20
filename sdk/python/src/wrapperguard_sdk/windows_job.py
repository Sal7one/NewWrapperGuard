"""Windows 10+ fixed-tool process tree with atomic Job Object membership.

CreateProcessW receives PROC_THREAD_ATTRIBUTE_JOB_LIST, so the process is in the
kill-on-close job before any user code can run. Only explicit pipe/NUL handles are
inherited. Closing the SDK's job handle kills all descendants, including after an
abrupt SDK termination. This is lifecycle containment, not a filesystem/network
sandbox: the explicitly installed scanner remains trusted user-privileged code.

https://learn.microsoft.com/en-us/windows/win32/procthread/job-objects
https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-updateprocthreadattribute
"""
from __future__ import annotations

import ctypes as C
import math
import os
from pathlib import Path
import subprocess


class _Security(C.Structure):
    _fields_ = [("length", C.c_uint32), ("descriptor", C.c_void_p), ("inherit", C.c_int32)]


class _Startup(C.Structure):
    _fields_ = [("cb", C.c_uint32), ("reserved", C.c_wchar_p), ("desktop", C.c_wchar_p),
        ("title", C.c_wchar_p), ("x", C.c_uint32), ("y", C.c_uint32), ("width", C.c_uint32),
        ("height", C.c_uint32), ("chars_x", C.c_uint32), ("chars_y", C.c_uint32),
        ("fill", C.c_uint32), ("flags", C.c_uint32), ("show", C.c_uint16),
        ("reserved_size", C.c_uint16), ("reserved_bytes", C.c_void_p),
        ("stdin", C.c_void_p), ("stdout", C.c_void_p), ("stderr", C.c_void_p)]


class _StartupEx(C.Structure):
    _fields_ = [("startup", _Startup), ("attributes", C.c_void_p)]


class _ProcessInfo(C.Structure):
    _fields_ = [("process", C.c_void_p), ("thread", C.c_void_p), ("pid", C.c_uint32), ("tid", C.c_uint32)]


class _BasicLimits(C.Structure):
    _fields_ = [("process_time", C.c_int64), ("job_time", C.c_int64), ("flags", C.c_uint32),
        ("min_working_set", C.c_size_t), ("max_working_set", C.c_size_t), ("active_processes", C.c_uint32),
        ("affinity", C.c_size_t), ("priority", C.c_uint32), ("scheduling", C.c_uint32)]


class _IoCounters(C.Structure):
    _fields_ = [(name, C.c_uint64) for name in ("read_ops", "write_ops", "other_ops", "read_bytes", "write_bytes", "other_bytes")]


class _Limits(C.Structure):
    _fields_ = [("basic", _BasicLimits), ("io", _IoCounters), ("process_memory", C.c_size_t),
               ("job_memory", C.c_size_t), ("peak_process_memory", C.c_size_t), ("peak_job_memory", C.c_size_t)]


def _environment_block(environment: dict[str, str]) -> str:
    if (not environment or len(environment) > 512 or any(
            not isinstance(k, str) or not k or "=" in k or "\x00" in k or
            not isinstance(v, str) or "\x00" in v for k, v in environment.items()) or
            len({k.upper() for k in environment}) != len(environment)):
        raise ValueError("Invalid fixed tool environment")
    block = "\x00".join(k + "=" + v for k, v in sorted(environment.items(), key=lambda item: item[0].upper())) + "\x00\x00"
    if len(block) > 32767:
        raise ValueError("Fixed tool environment exceeds limit")
    return block


class WindowsJobProcess:
    """Minimal Popen-compatible handle owner used by the bounded pipe reader."""
    def __init__(self, argv: list[str], cwd: Path, environment: dict[str, str]):
        if os.name != "nt":
            raise NotImplementedError("Windows Job Objects require Windows")
        import msvcrt
        self.api = C.WinDLL("kernel32", use_last_error=True)
        api = self.api
        pointer = C.c_void_p
        signatures = {
            "CreateJobObjectW": ([pointer, C.c_wchar_p], pointer),
            "SetInformationJobObject": ([pointer, C.c_int32, pointer, C.c_uint32], C.c_int32),
            "CreatePipe": ([C.POINTER(pointer), C.POINTER(pointer), C.POINTER(_Security), C.c_uint32], C.c_int32),
            "SetHandleInformation": ([pointer, C.c_uint32, C.c_uint32], C.c_int32),
            "CreateFileW": ([C.c_wchar_p, C.c_uint32, C.c_uint32, C.POINTER(_Security), C.c_uint32, C.c_uint32, pointer], pointer),
            "InitializeProcThreadAttributeList": ([pointer, C.c_uint32, C.c_uint32, C.POINTER(C.c_size_t)], C.c_int32),
            "UpdateProcThreadAttribute": ([pointer, C.c_uint32, C.c_size_t, pointer, C.c_size_t, pointer, pointer], C.c_int32),
            "DeleteProcThreadAttributeList": ([pointer], None),
            "CreateProcessW": ([C.c_wchar_p, C.c_wchar_p, pointer, pointer, C.c_int32, C.c_uint32, pointer, C.c_wchar_p, C.POINTER(_StartupEx), C.POINTER(_ProcessInfo)], C.c_int32),
            "ResumeThread": ([pointer], C.c_uint32),
            "WaitForSingleObject": ([pointer, C.c_uint32], C.c_uint32),
            "GetExitCodeProcess": ([pointer, C.POINTER(C.c_uint32)], C.c_int32),
            "TerminateJobObject": ([pointer, C.c_uint32], C.c_int32),
            "CloseHandle": ([pointer], C.c_int32),
        }
        for name, (arguments, result) in signatures.items():
            function = getattr(api, name); function.argtypes = arguments; function.restype = result
        self.job = self.handle = None
        self.stdout = None
        self.returncode = None
        self.args = argv
        read, write, null = pointer(), pointer(), None
        info = _ProcessInfo()
        initialized = False
        attributes = None
        try:
            env = C.create_unicode_buffer(_environment_block(environment))
            command = subprocess.list2cmdline(argv)
            if len(command) > 32766:
                raise ValueError("Fixed tool command exceeds limit")
            self.job = api.CreateJobObjectW(None, None)
            if not self.job:
                raise OSError("Tool job could not be created")
            limits = _Limits()
            limits.basic.flags = 0x2000 | 0x8  # KILL_ON_JOB_CLOSE | ACTIVE_PROCESS
            limits.basic.active_processes = 64
            if not api.SetInformationJobObject(self.job, 9, C.byref(limits), C.sizeof(limits)):
                raise OSError("Tool job limits could not be set")
            security = _Security(C.sizeof(_Security), None, 1)
            if not api.CreatePipe(C.byref(read), C.byref(write), C.byref(security), 0):
                raise OSError("Tool pipe could not be created")
            if not api.SetHandleInformation(read, 1, 0):
                raise OSError("Tool pipe inheritance could not be restricted")
            null = api.CreateFileW("NUL", 0xC0000000, 3, C.byref(security), 3, 0, None)
            if null in (None, pointer(-1).value):
                null = None
                raise OSError("Tool null input could not be created")
            size = C.c_size_t()
            api.InitializeProcThreadAttributeList(None, 2, 0, C.byref(size))
            if not 0 < size.value <= 65536:
                raise OSError("Tool process attributes are unsupported")
            attributes = C.create_string_buffer(size.value)
            if not api.InitializeProcThreadAttributeList(attributes, 2, 0, C.byref(size)):
                raise OSError("Tool process attributes could not be initialized")
            initialized = True
            jobs = (pointer * 1)(self.job)
            handles = (pointer * 2)(write.value, null)
            for key, value in ((0x2000D, jobs), (0x20002, handles)):
                if not api.UpdateProcThreadAttribute(attributes, 0, key, value, C.sizeof(value), None, None):
                    raise OSError("Atomic tool job assignment is unsupported")
            startup = _StartupEx()
            startup.startup.cb = C.sizeof(startup)
            startup.startup.flags = 0x100  # STARTF_USESTDHANDLES
            startup.startup.stdin, startup.startup.stdout, startup.startup.stderr = null, write.value, null
            startup.attributes = C.cast(attributes, pointer)
            # Membership is atomic through JOB_LIST; suspend until all setup has succeeded.
            if not api.CreateProcessW(argv[0], C.create_unicode_buffer(command), None, None, 1,
                    0x80000 | 0x400 | 0x4 | 0x08000000, env, str(cwd), C.byref(startup), C.byref(info)):
                raise OSError("Fixed tool could not be started inside its job")
            self.handle, self.pid = info.process, int(info.pid)
            descriptor = msvcrt.open_osfhandle(read.value, os.O_RDONLY | os.O_BINARY)
            read.value = None
            self.stdout = os.fdopen(descriptor, "rb", buffering=0)
            if api.ResumeThread(info.thread) == 0xFFFFFFFF:
                raise OSError("Fixed tool could not be resumed")
        except BaseException:
            self.close()
            raise
        finally:
            if initialized:
                api.DeleteProcThreadAttributeList(attributes)
            for handle in (info.thread, read.value, write.value, null):
                if handle:
                    api.CloseHandle(handle)

    def poll(self) -> int | None:
        if self.returncode is None:
            result = self.api.WaitForSingleObject(self.handle, 0)
            if result == 258:
                return None
            code = C.c_uint32()
            if result != 0 or not self.api.GetExitCodeProcess(self.handle, C.byref(code)):
                raise OSError("Tool process state could not be read")
            self.returncode = int(code.value)
        return self.returncode

    def wait(self, timeout: float | None = None) -> int:
        if timeout is not None and (not math.isfinite(timeout) or timeout < 0):
            raise ValueError("Invalid tool wait timeout")
        milliseconds = 0xFFFFFFFF if timeout is None else min(0xFFFFFFFE, math.ceil(timeout * 1000))
        if self.api.WaitForSingleObject(self.handle, milliseconds) == 258:
            raise subprocess.TimeoutExpired("fixed-tool", timeout)
        result = self.poll()
        if result is None:
            raise OSError("Tool process did not finish")
        return result

    def terminate(self) -> None:
        if self.job and not self.api.TerminateJobObject(self.job, 1):
            raise OSError("Tool process tree could not be terminated")

    kill = terminate

    def close(self) -> None:
        if self.job:
            self.api.CloseHandle(self.job); self.job = None
        if self.stdout is not None:
            self.stdout.close(); self.stdout = None
        if self.handle:
            self.api.CloseHandle(self.handle); self.handle = None
