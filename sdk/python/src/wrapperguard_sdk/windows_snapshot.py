"""Experimental, unwired Windows handle-relative snapshot reader.

Every source component opens relative to a held directory handle with NtCreateFile;
reparse points are opened as reparse points and rejected before bytes are read.
Directory enumeration also uses a handle. There is deliberately no path-based or
POSIX-flag fallback. Only explicit local drive paths are currently accepted.

This module is not an announcement of Windows fixed-runner support. Real Windows
CI, process-tree cancellation and host integration must pass before it is wired.
Disk operations are synchronous; the enclosing SDK process deadline remains the
hard cancellation boundary if a filesystem/filter driver stalls an OS call.

Primary API contracts:
https://learn.microsoft.com/en-us/windows-hardware/drivers/ddi/ntifs/nf-ntifs-ntcreatefile
https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-getfileinformationbyhandleex
https://learn.microsoft.com/en-us/windows/win32/api/winbase/ns-winbase-file_full_dir_info
"""
from __future__ import annotations

import ctypes
from dataclasses import dataclass
import os
import math
from pathlib import Path, PureWindowsPath
import re
import struct
import time
from typing import Callable, Iterator


@dataclass(frozen=True)
class SnapshotLimits:
    max_files: int = 5000
    max_file_bytes: int = 1024 * 1024
    max_bytes: int = 32 * 1024 * 1024
    max_depth: int = 64
    max_entries: int = 50000

    def __post_init__(self):
        if not (1 <= self.max_files <= 100000 and 1 <= self.max_file_bytes <= 16 * 1024 * 1024 and
                1 <= self.max_bytes <= 1024 * 1024 * 1024 and 1 <= self.max_depth <= 256 and
                1 <= self.max_entries <= 1000000):
            raise ValueError("Invalid source snapshot limits")


def _component(name: str) -> bool:
    return (bool(name) and name not in (".", "..") and not name.endswith((".", " ")) and
            not any(ord(c) < 32 or c in '\\/:\x00<>\"|?*' for c in name) and
            not re.fullmatch(r"(?:CON|CONIN\$|CONOUT\$|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³])(?:\..*)?", name, re.IGNORECASE))


def _directory_entries(data: bytes) -> Iterator[tuple[str, int]]:
    """Decode bounded FILE_FULL_DIR_INFO records, checking every offset before use."""
    offset = 0
    while True:
        if offset + 68 > len(data):
            raise ValueError("Invalid directory entry buffer")
        next_offset = struct.unpack_from("<I", data, offset)[0]
        attributes, length = struct.unpack_from("<II", data, offset + 56)
        if not length or length % 2 or length > 512 or offset + 68 + length > len(data):
            raise ValueError("Invalid directory entry name")
        name = data[offset + 68:offset + 68 + length].decode("utf-16-le", errors="strict")
        if name not in (".", ".."):
            if not _component(name):
                raise ValueError("Unsupported source filename")
            yield name, attributes
        if not next_offset:
            return
        if next_offset % 8 or next_offset < 68 + length or offset + next_offset >= len(data):
            raise ValueError("Invalid directory entry offset")
        offset += next_offset


class _UnicodeString(ctypes.Structure):
    _fields_ = [("Length", ctypes.c_uint16), ("MaximumLength", ctypes.c_uint16), ("Buffer", ctypes.c_void_p)]


class _ObjectAttributes(ctypes.Structure):
    _fields_ = [("Length", ctypes.c_uint32), ("RootDirectory", ctypes.c_void_p),
                ("ObjectName", ctypes.POINTER(_UnicodeString)), ("Attributes", ctypes.c_uint32),
                ("SecurityDescriptor", ctypes.c_void_p), ("SecurityQualityOfService", ctypes.c_void_p)]


class _StatusUnion(ctypes.Union):
    _fields_ = [("Status", ctypes.c_int32), ("Pointer", ctypes.c_void_p)]


class _IoStatusBlock(ctypes.Structure):
    _anonymous_ = ("value",)
    _fields_ = [("value", _StatusUnion), ("Information", ctypes.c_size_t)]


class _Api:
    def __init__(self):
        if os.name != "nt":
            raise NotImplementedError("Windows handle snapshots require Windows")
        from ctypes import wintypes
        self.kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        self.nt = ctypes.WinDLL("ntdll", use_last_error=True)
        self.kernel.CreateFileW.argtypes = [wintypes.LPCWSTR, ctypes.c_uint32, ctypes.c_uint32,
                                             ctypes.c_void_p, ctypes.c_uint32, ctypes.c_uint32, ctypes.c_void_p]
        self.kernel.CreateFileW.restype = ctypes.c_void_p
        self.kernel.CloseHandle.argtypes = [ctypes.c_void_p]
        self.kernel.CloseHandle.restype = wintypes.BOOL
        self.kernel.GetFileType.argtypes = [ctypes.c_void_p]
        self.kernel.GetFileType.restype = ctypes.c_uint32
        self.kernel.GetFileInformationByHandleEx.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_void_p, ctypes.c_uint32]
        self.kernel.GetFileInformationByHandleEx.restype = wintypes.BOOL
        self.kernel.ReadFile.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_uint32,
                                        ctypes.POINTER(ctypes.c_uint32), ctypes.c_void_p]
        self.kernel.ReadFile.restype = wintypes.BOOL
        self.kernel.GetDriveTypeW.argtypes = [wintypes.LPCWSTR]
        self.kernel.GetDriveTypeW.restype = ctypes.c_uint32
        self.nt.NtCreateFile.argtypes = [ctypes.POINTER(ctypes.c_void_p), ctypes.c_uint32,
            ctypes.POINTER(_ObjectAttributes), ctypes.POINTER(_IoStatusBlock), ctypes.c_void_p,
            ctypes.c_uint32, ctypes.c_uint32, ctypes.c_uint32, ctypes.c_uint32, ctypes.c_void_p, ctypes.c_uint32]
        self.nt.NtCreateFile.restype = ctypes.c_int32

    def close(self, handle: int) -> None:
        self.kernel.CloseHandle(handle)

    def info(self, handle: int, kind: int, size: int) -> bytes:
        value = ctypes.create_string_buffer(size)
        if not self.kernel.GetFileInformationByHandleEx(handle, kind, value, size):
            raise OSError("Source handle metadata could not be read")
        return value.raw

    def attributes(self, handle: int) -> int:
        attributes = struct.unpack_from("<I", self.info(handle, 9, 8))[0]
        if attributes & 0x400 or self.kernel.GetFileType(handle) != 1:  # reparse or non-disk
            raise OSError("Reparse points and non-disk source inputs are unsupported")
        return attributes

    def drive(self, drive: str) -> int:
        if self.kernel.GetDriveTypeW(drive + "\\") != 3:  # DRIVE_FIXED only; no UNC/removable/device paths
            raise ValueError("Source snapshot requires a local fixed drive")
        handle = self.kernel.CreateFileW("\\\\?\\" + drive + "\\", 0x100081, 7, None, 3, 0x02200000, None)
        if handle in (None, ctypes.c_void_p(-1).value):
            raise OSError("Source drive could not be opened")
        try:
            if not self.attributes(handle) & 0x10:
                raise OSError("Source anchor is not a directory")
            return handle
        except BaseException:
            self.close(handle)
            raise

    def child(self, parent: int, name: str, directory: bool) -> int:
        if not _component(name):
            raise ValueError("Invalid source path component")
        encoded = name.encode("utf-16-le")
        text = ctypes.create_string_buffer(encoded + b"\x00\x00")
        string = _UnicodeString(len(encoded), len(encoded) + 2, ctypes.cast(text, ctypes.c_void_p))
        # OBJ_CASE_INSENSITIVE | OBJ_DONT_REPARSE, single component relative to held parent.
        attributes = _ObjectAttributes(ctypes.sizeof(_ObjectAttributes), parent, ctypes.pointer(string), 0x1040, None, None)
        handle = ctypes.c_void_p()
        status_block = _IoStatusBlock()
        # FILE_OPEN_REPARSE_POINT | FILE_SYNCHRONOUS_IO_NONALERT plus exact object type.
        options = 0x200000 | 0x20 | (0x1 if directory else 0x40)
        status = self.nt.NtCreateFile(ctypes.byref(handle), 0x100081, ctypes.byref(attributes), ctypes.byref(status_block),
                                      None, 0, 7 if directory else 1, 1, options, None, 0)
        if status != 0 or not handle.value:
            if handle.value:
                self.close(handle.value)
            raise OSError("Source component could not be opened safely")
        try:
            attrs = self.attributes(handle.value)
            if bool(attrs & 0x10) != directory:
                raise OSError("Source component changed type")
            return handle.value
        except BaseException:
            self.close(handle.value)
            raise

    def entries(self, directory: int) -> Iterator[tuple[str, int]]:
        buffer = ctypes.create_string_buffer(65536)
        while True:
            if not self.kernel.GetFileInformationByHandleEx(directory, 14, buffer, len(buffer)):
                if ctypes.get_last_error() == 18:  # ERROR_NO_MORE_FILES
                    return
                raise OSError("Source directory could not be enumerated")
            yield from _directory_entries(buffer.raw)

    def read(self, handle: int, maximum: int, check: Callable[[], None]) -> bytes:
        before = self.info(handle, 1, 24)  # FILE_STANDARD_INFO
        size = struct.unpack_from("<q", before, 8)[0]
        if not 0 <= size <= maximum or before[21]:
            raise ValueError("Source file exceeds snapshot limits")
        output = bytearray()
        buffer = ctypes.create_string_buffer(65536)
        while True:
            check()
            read = ctypes.c_uint32()
            if not self.kernel.ReadFile(handle, buffer, min(len(buffer), maximum - len(output) + 1), ctypes.byref(read), None):
                raise OSError("Source file could not be read")
            if not read.value:
                break
            output.extend(buffer.raw[:read.value])
            if len(output) > maximum:
                raise ValueError("Source file exceeds snapshot limits")
        if before != self.info(handle, 1, 24):
            raise OSError("Source file changed while being copied")
        self.attributes(handle)
        return bytes(output)


def copy_windows_snapshot(project_root: Path, destination: Path, deadline: float, *,
                          extensions: frozenset[str] = frozenset({".py"}),
                          file_names: frozenset[str] = frozenset(), dependency_only: bool = False,
                          dependency_files: frozenset[str] = frozenset(),
                          excluded_directories: frozenset[str] = frozenset(),
                          limits: SnapshotLimits = SnapshotLimits(),
                          cancelled: Callable[[], bool] | None = None) -> tuple[int, bool]:
    """Copy source data through anchored no-follow handles into an owned empty directory.

    The caller creates the destination outside the project and owns its access controls.
    Read errors, links, entry/type changes and limits produce explicit partial coverage.
    Root failures, cancellation and deadline exhaustion raise; none becomes a clean scan.
    """
    api = _Api()
    if not isinstance(deadline, (int, float)) or not math.isfinite(deadline):
        raise ValueError("Invalid snapshot deadline")
    selected = PureWindowsPath(str(project_root))
    if not selected.is_absolute() or not re.fullmatch("[A-Za-z]:", selected.drive):
        raise ValueError("Source snapshot requires an absolute local drive path")
    if not destination.is_dir() or destination.is_symlink() or any(destination.iterdir()):
        raise ValueError("Snapshot destination must be an owned empty directory")
    target = destination.resolve()
    lexical_source = Path(os.path.abspath(project_root))
    if target == lexical_source or lexical_source in target.parents:
        raise ValueError("Snapshot destination must be outside the project")
    count = total = visited = 0
    incomplete = False
    excluded = {value.casefold() for value in excluded_directories}
    names = {value.casefold() for value in file_names}
    dependencies = {value.casefold() for value in dependency_files}

    def check() -> None:
        if cancelled is not None and cancelled():
            raise InterruptedError("Source snapshot was cancelled")
        if time.monotonic() >= deadline:
            raise TimeoutError("Integration deadline exceeded")

    def walk(directory: int, parts: tuple[str, ...]) -> None:
        nonlocal count, total, visited, incomplete
        for name, attributes in api.entries(directory):
            check()
            visited += 1
            if visited > limits.max_entries:
                incomplete = True
                return
            if name.casefold() in excluded or name.casefold() == "psscriptanalyzersettings.psd1":
                continue
            if attributes & 0x400:
                incomplete = True
                continue
            child_parts = (*parts, name)
            is_directory = bool(attributes & 0x10)
            if is_directory and len(child_parts) > limits.max_depth:
                incomplete = True
                continue
            included = name.casefold() in dependencies if dependency_only else Path(name).suffix.lower() in extensions or name.casefold() in names
            if not is_directory and not included:
                continue
            if not is_directory and (count >= limits.max_files or total >= limits.max_bytes):
                incomplete = True
                continue
            child = None
            try:
                child = api.child(directory, name, is_directory)
                if is_directory:
                    walk(child, child_parts)
                else:
                    data = api.read(child, min(limits.max_file_bytes, limits.max_bytes - total), check)
                    output = target.joinpath(*child_parts)
                    output.parent.mkdir(parents=True, exist_ok=True)
                    with output.open("xb") as stream:
                        stream.write(data)
                    count += 1
                    total += len(data)
            except (TimeoutError, InterruptedError):
                raise
            except (OSError, ValueError):
                incomplete = True
            finally:
                if child is not None:
                    api.close(child)

    handles = []
    try:
        check()
        anchor = api.drive(selected.drive)
        handles.append(anchor)
        for part in selected.parts[1:]:
            check()
            anchor = api.child(anchor, part, True)
            handles.append(anchor)
        walk(anchor, ())
        check()
        return count, incomplete
    finally:
        for handle in reversed(handles):
            api.close(handle)
