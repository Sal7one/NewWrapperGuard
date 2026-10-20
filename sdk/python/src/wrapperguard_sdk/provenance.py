"""Optional report/declared-input integrity and freshness; never producer authenticity.

An upstream job can call create_manifest after producing its report. The manifest
binds exact report bytes and listed source bytes. It does not prove that the listed
files are the whole project or that a claimed producer, revision or database is real.
"""
from __future__ import annotations

from datetime import datetime, timezone
import hashlib
import os
from pathlib import Path
import re
import stat
import time

from .models import _text
from .profile import bounded_read, load_json_document

MAX_INPUTS = 5000
MAX_INPUT_BYTES = 1024 * 1024
MAX_TOTAL_BYTES = 64 * 1024 * 1024
MAX_MANIFEST_BYTES = 2 * 1024 * 1024


def _path(value: object) -> tuple[str, ...]:
    if (not isinstance(value, str) or not value or len(value) > 4096 or
            any(ord(c) < 32 or c in "\\:" for c in value) or
            any(part in ("", ".", "..") for part in value.split("/"))):
        raise ValueError("Invalid declared provenance input")
    return tuple(value.split("/"))


def _timestamp(value: object) -> datetime:
    if not isinstance(value, str) or not re.fullmatch(r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,6})?Z", value):
        raise ValueError("Invalid provenance timestamp")
    return datetime.fromisoformat(value[:-1] + "+00:00")


def _read_input(root: Path, name: str, deadline: float) -> bytes:
    parts = _path(name)
    def check():
        if time.monotonic() >= deadline:
            raise TimeoutError("Provenance deadline exceeded")
    check()
    if os.name == "nt":
        from pathlib import PureWindowsPath
        from .windows_snapshot import _Api
        api = _Api(); selected = PureWindowsPath(str(root)); handles = []
        try:
            anchor = api.drive(selected.drive); handles.append(anchor)
            for part in (*selected.parts[1:], *parts[:-1]):
                check(); anchor = api.child(anchor, part, True); handles.append(anchor)
            source = api.child(anchor, parts[-1], False); handles.append(source)
            return api.read(source, MAX_INPUT_BYTES, check)
        finally:
            for handle in reversed(handles): api.close(handle)
    if os.name != "posix" or not hasattr(os, "O_NOFOLLOW"):
        raise ValueError("Provenance requires supported no-follow input access")
    handles = []
    try:
        anchor = os.open(root, os.O_RDONLY | os.O_NOFOLLOW | os.O_DIRECTORY); handles.append(anchor)
        for part in parts[:-1]:
            check(); anchor = os.open(part, os.O_RDONLY | os.O_NOFOLLOW | os.O_DIRECTORY, dir_fd=anchor); handles.append(anchor)
        source = os.open(parts[-1], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=anchor)
        with os.fdopen(source, "rb") as stream:
            before = os.fstat(stream.fileno())
            if not stat.S_ISREG(before.st_mode): raise ValueError("Regular provenance input required")
            data = stream.read(MAX_INPUT_BYTES + 1)
            after = os.fstat(stream.fileno())
        if (len(data) > MAX_INPUT_BYTES or (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns, before.st_ctime_ns) !=
                (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns, after.st_ctime_ns)):
            raise ValueError("Provenance input changed or exceeds limits")
        check(); return data
    finally:
        for handle in reversed(handles): os.close(handle)


def _metadata(document: dict) -> str:
    fields = []
    for key, allowed in (("producer", {"name", "version"}), ("database", {"version", "updatedAt"}),
                         ("sourceIdentity", {"revision"})):
        value = document.get(key)
        if value is None:
            fields.append(key + ": unknown"); continue
        if not isinstance(value, dict) or set(value) - allowed or not value:
            raise ValueError("Invalid provenance metadata")
        selected = []
        for name, text in value.items():
            if name == "updatedAt": _timestamp(text)
            selected.append(name + "=" + _text(text, 160))
        fields.append(key + ": " + ", ".join(selected))
    scope = document.get("scope")
    fields.append("declared scope: " + (_text(scope, 512) if scope is not None else "unknown"))
    return "; ".join(fields)


def create_manifest(report_bytes: bytes, root: Path, inputs: list[str], **metadata) -> dict:
    """Generate a manifest in the upstream job; callers persist it outside the project."""
    if not 1 <= len(inputs) <= MAX_INPUTS or len(set(inputs)) != len(inputs):
        raise ValueError("Declare unique provenance inputs")
    if set(metadata) - {"producer", "database", "sourceIdentity", "scope"}:
        raise ValueError("Invalid provenance metadata")
    _metadata(metadata)
    root = root.resolve(strict=True); deadline = time.monotonic() + 30; total = 0; records = []
    for name in inputs:
        data = _read_input(root, name, deadline); total += len(data)
        if total > MAX_TOTAL_BYTES: raise ValueError("Provenance inputs exceed total limit")
        records.append({"path": name, "sha256": hashlib.sha256(data).hexdigest()})
    return {"schemaVersion": 1, "reportSha256": hashlib.sha256(report_bytes).hexdigest(),
            "generatedAt": datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
            "inputs": records, **metadata}


def verify_manifest(configuration, report_bytes: bytes, root: Path) -> list[dict]:
    actual = configuration.manifest_path.resolve(strict=True)
    root = root.resolve(strict=True)
    if actual.is_relative_to(root) or not actual.is_file():
        raise ValueError("Provenance manifest must remain outside the project")
    raw = bounded_read(actual, MAX_MANIFEST_BYTES)
    document = load_json_document(raw)
    allowed = {"schemaVersion", "reportSha256", "generatedAt", "inputs", "producer", "database", "sourceIdentity", "scope"}
    if (not isinstance(document, dict) or set(document) - allowed or
            type(document.get("schemaVersion")) is not int or document["schemaVersion"] != 1 or
            document.get("reportSha256") != hashlib.sha256(report_bytes).hexdigest()):
        raise ValueError("Report provenance digest or envelope does not match")
    now = datetime.now(timezone.utc)
    age = (now - _timestamp(document.get("generatedAt"))).total_seconds()
    if age < -300 or age > configuration.max_age_seconds:
        raise ValueError("Report provenance is stale or future-dated")
    inputs = document.get("inputs")
    if not isinstance(inputs, list) or not 1 <= len(inputs) <= MAX_INPUTS:
        raise ValueError("Provenance requires bounded declared inputs")
    deadline = time.monotonic() + 30; total = 0; seen = set()
    for entry in inputs:
        if (not isinstance(entry, dict) or set(entry) != {"path", "sha256"} or
                not isinstance(entry["sha256"], str) or not re.fullmatch(r"[0-9a-f]{64}", entry["sha256"])):
            raise ValueError("Invalid declared provenance input")
        name = entry["path"]; _path(name)
        if name in seen: raise ValueError("Duplicate provenance input")
        seen.add(name)
        data = _read_input(root, name, deadline); total += len(data)
        if total > MAX_TOTAL_BYTES or hashlib.sha256(data).hexdigest() != entry["sha256"]:
            raise ValueError("Current declared input does not match report provenance")
    if bounded_read(actual, MAX_MANIFEST_BYTES) != raw:
        raise ValueError("Provenance manifest changed during verification")
    notices = [{"code": "WG-REPORT-PROVENANCE", "message":
        f"Verified exact report bytes, age and {len(seen)} listed input digests. " + _metadata(document) +
        ". Metadata is a producer declaration; integrity does not prove authenticity or whole-project coverage."}]
    updated = (document.get("database") or {}).get("updatedAt")
    if updated is not None and (now - _timestamp(updated)).total_seconds() > configuration.max_age_seconds:
        notices.append({"code": "WG-REPORT-DATABASE-AGE", "message": "The declared vulnerability database is older than the report freshness budget."})
    return notices
