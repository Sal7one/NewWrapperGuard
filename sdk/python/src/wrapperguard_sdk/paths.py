"""Contain untrusted report locations in the explicitly selected project."""
from __future__ import annotations

import os
from pathlib import Path
import re
from urllib.parse import unquote, urlsplit


def normalize_location(project_root: Path, raw_path: str) -> str | None:
    """Canonical project-relative path, or None for unsupported/escaping locations."""
    try:
        if not isinstance(raw_path, str) or not raw_path or len(raw_path) > 4096:
            return None
        if any(ord(c) < 32 or ord(c) == 127 for c in raw_path):
            return None
        raw_path.encode("utf-8", "strict")
        root = Path(project_root).resolve(strict=True)
        if not root.is_dir():
            return None
        if re.match(r"^[A-Za-z]:[\\/]", raw_path):
            if os.name != "nt":
                return None
            decoded = raw_path
        else:
            parsed = urlsplit(raw_path)
            if parsed.scheme:
                if parsed.scheme.lower() != "file" or parsed.netloc.lower() not in ("", "localhost"):
                    return None
                if parsed.query or parsed.fragment:
                    return None
                decoded = unquote(parsed.path, encoding="utf-8", errors="strict")
                if os.name == "nt" and re.match(r"^/[A-Za-z]:/", decoded):
                    decoded = decoded[1:]
            else:
                if parsed.netloc or parsed.query or parsed.fragment:
                    return None
                decoded = unquote(raw_path, encoding="utf-8", errors="strict")
        if re.search(r"%[0-9a-fA-F]{2}", decoded) or any(ord(c) < 32 for c in decoded):
            return None
        decoded = decoded.replace("\\", "/")
        if decoded.startswith("//"):
            return None
        candidate = Path(decoded)
        if not candidate.is_absolute():
            candidate = root / candidate
        return candidate.resolve(strict=False).relative_to(root).as_posix()
    except (ValueError, OSError, RuntimeError, UnicodeError):
        return None
