"""Small, validated provider findings; host policy and identity remain host-owned."""
from __future__ import annotations

import re
from urllib.parse import urlsplit, urlunsplit

ACTIONS = frozenset({"WARNING", "MAJOR_WARNING", "ALERT", "BLOCK_BUILD", "BLOCK_SYNC"})
SEVERITIES = frozenset({"INFO", "WARN", "ERROR"})
CONFIDENCES = frozenset({"LOW", "MEDIUM", "HIGH"})
CATEGORIES = frozenset({"WRAPPER", "NETWORK", "EXECUTION", "OBFUSCATION", "REPOSITORY",
                        "CREDENTIALS", "SUPPLY_CHAIN", "FILE_SYSTEM", "CONFIGURATION", "AUDIT"})
MAX_FINDINGS = 5_000
_SECRET_KEYS = (r"password|passwd|pwd|secret|token|api[_\-\s]?key|access[_\-\s]?key|"
                r"secret[_\-\s]?key|client[_\-\s]?secret|private[_\-\s]?key|"
                r"auth[_\-\s]?token|credential")
_QUOTED = re.compile(r"(\b(?:" + _SECRET_KEYS + r")\b\s*[=:]\s*)([\"'])(.*?)(\2)", re.I)
_BARE = re.compile(r"(\b(?:" + _SECRET_KEYS + r")\b\s*[=:]\s*)([^\"'\s,;}&|]+)", re.I)
_AUTH = re.compile(r"(\bauthorization\b\s*[=:]\s*[\"']?\s*(?:(?:bearer|basic|token)\s+)?)([^\"'\s,;]+)", re.I)
_BLOCK = re.compile(r"\bcredentials\b\s*\{", re.I)
_BLOCK_ASSIGN = re.compile(r"(\b[a-zA-Z_][\w.]*\b\s*[=:]\s*)([\"'])(.*?)(\2)", re.I)
_URL = re.compile(r"https?://[^\s<>\"']+", re.I)
_PRIVATE_KEY = re.compile(r"-----BEGIN (?:[A-Z]+ )?PRIVATE KEY-----.*?-----END (?:[A-Z]+ )?PRIVATE KEY-----", re.S)
_QUOTED_LITERAL = re.compile(r"([\"'])(?:\\.|(?!\1)[^\r\n])*?\1")


def _mask_assignment(match: re.Match[str]) -> str:
    return match[1] + match[2] + "[redacted]" + match[2]


def redact_text(value: str) -> str:
    """Mask common credential forms, including URL userinfo and query contents.

    Tool output remains untrusted. This is deterministic output minimization, not a
    claim that arbitrary embedded secrets can be recognized from natural language.
    """
    if not isinstance(value, str):
        raise ValueError("Expected text")
    out = _PRIVATE_KEY.sub("[redacted private key]", value)
    out = _QUOTED.sub(_mask_assignment, out)
    out = _BARE.sub(lambda m: m[1] + "[redacted]", out)
    out = _AUTH.sub(lambda m: m[1] + "[redacted]", out)

    def mask_url(match: re.Match[str]) -> str:
        try:
            parsed = urlsplit(match[0])
            netloc = parsed.netloc.rsplit("@", 1)[-1]
            if "@" in parsed.netloc:
                netloc = "[redacted]@" + netloc
            return urlunsplit((parsed.scheme, netloc, parsed.path,
                               "[redacted]" if parsed.query else "", ""))
        except ValueError:
            return "[redacted URL]"

    out = _URL.sub(mask_url, out)
    start_at = 0
    while match := _BLOCK.search(out, start_at):
        opening = out.index("{", match.start())
        depth, closing = 0, None
        for i in range(opening, len(out)):
            if out[i] == "{":
                depth += 1
            elif out[i] == "}":
                depth -= 1
                if depth == 0:
                    closing = i
                    break
        if closing is None:
            break
        masked = _BLOCK_ASSIGN.sub(_mask_assignment, out[opening + 1:closing])
        out = out[:opening + 1] + masked + out[closing:]
        start_at = opening + len(masked) + 2
    return out


def _text(value: object, limit: int, *, blank: bool = False) -> str:
    if not isinstance(value, str) or len(value) > limit or (not blank and not value.strip()):
        raise ValueError("Invalid finding text")
    if any((ord(c) < 32 and c not in "\n\r\t") or ord(c) == 127 for c in value):
        raise ValueError("Invalid finding text")
    value.encode("utf-8", "strict")
    masked = redact_text(value)
    if len(masked) > limit:
        raise ValueError("Invalid finding text")
    return masked


def validate_actions(actions: object) -> list[str]:
    if not isinstance(actions, list) or len(actions) > len(ACTIONS):
        raise ValueError("Invalid requested actions")
    if any(not isinstance(action, str) or action not in ACTIONS for action in actions):
        raise ValueError("Invalid requested actions")
    if len(set(actions)) != len(actions):
        raise ValueError("Invalid requested actions")
    return list(actions)


def make_finding(rule_id: str, title: str, message: str, severity: str, confidence: str,
                 category: str, path: str, line: int | None = None, column: int | None = None,
                 evidence: str | dict | None = None, actions: list[str] | None = None,
                 remediation: str | None = None) -> dict:
    """Return only API 1 provider-owned fields, never approvals or host decisions."""
    if not isinstance(rule_id, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.:/-]{0,119}", rule_id):
        raise ValueError("Invalid rule ID")
    if (not isinstance(severity, str) or severity not in SEVERITIES
            or not isinstance(confidence, str) or confidence not in CONFIDENCES
            or not isinstance(category, str) or category not in CATEGORIES):
        raise ValueError("Invalid finding classification")
    safe_path = _text(path, 4096).replace("\\", "/")
    if safe_path.startswith("/") or ":" in safe_path or any(part == ".." for part in safe_path.split("/")):
        raise ValueError("Invalid project location")
    location: dict = {"path": safe_path}
    for key, value in (("line", line), ("column", column)):
        if value is not None:
            if type(value) is not int or not 1 <= value <= 2_147_483_647:
                raise ValueError("Invalid source position")
            location[key] = value
    result = {"ruleId": rule_id, "title": _text(title, 512), "message": _text(message, 4096),
              "severity": severity, "confidence": confidence, "category": category,
              "location": location, "actions": validate_actions(["WARNING"] if actions is None else actions)}
    if evidence is not None:
        original = evidence if isinstance(evidence, str) else evidence.get("text") if isinstance(evidence, dict) else None
        text = _text(original, 8192, blank=True)
        # Raw quoted source literals are unnecessary for host decisions. Keep source
        # structure and use the editor location for authorized inspection of originals.
        text = _QUOTED_LITERAL.sub(lambda m: m[1] + "[redacted]" + m[1], text)
        if len(text) > 8192:
            raise ValueError("Invalid finding evidence")
        result["evidence"] = {"text": text, "redacted": text != original or
                              (isinstance(evidence, dict) and evidence.get("redacted") is True)}
    if remediation is not None:
        result["remediation"] = _text(remediation, 4096)
    return result
