"""Bounded, data-only research signals. This is neither a trained model nor a sandbox.

The implementation extracts a small set of literal-decoding and def/use signals;
it does not reproduce PowerPeeler, DONAPI or PyGuard's research pipelines.
"""
from __future__ import annotations

import ast
import base64
import binascii
from dataclasses import dataclass
from pathlib import Path
import re
import time

from .models import MAX_FINDINGS, make_finding

MAX_LITERAL_BYTES = 16_384
MAX_AST_NODES = 20_000
_ENCODED_COMMAND = re.compile(
    r"\b(?:powershell|pwsh)(?:\.exe)?\b[^\r\n]{0,256}?\s-(?:enc|encodedcommand)\s+[\"']?([A-Za-z0-9+/]{8,}={0,2})", re.I)
_DECODE_LITERAL = re.compile(r"FromBase64String\s*\(\s*['\"]([A-Za-z0-9+/]{8,}={0,2})['\"]\s*\)", re.I)
_PS_DOWNLOAD = re.compile(r"\b(?:DownloadString|DownloadFile|Invoke-WebRequest|Invoke-RestMethod)\b", re.I)
_PS_EXECUTE = re.compile(r"\b(?:Invoke-Expression|iex|Start-Process)\b", re.I)


@dataclass(frozen=True)
class _Value:
    literal: str | bytes | None = None
    tags: frozenset[str] = frozenset()


def _decode(value: str | bytes) -> bytes | None:
    try:
        if len(value) > MAX_LITERAL_BYTES * 2:
            return None
        decoded = base64.b64decode(value, validate=True)
        return decoded if len(decoded) <= MAX_LITERAL_BYTES else None
    except (ValueError, binascii.Error):
        return None


def _decoded_text(value: bytes) -> str:
    try:
        return value.decode("utf-16-le" if b"\x00" in value[:80] else "utf-8", errors="strict")
    except UnicodeError:
        return ""


class _PythonSignals:
    def __init__(self, path: str, deadline: float) -> None:
        self.path, self.deadline = path, deadline
        self.aliases: dict[str, str] = {}
        self.findings: list[dict] = []
        self.seen: set[tuple[str, int]] = set()

    def name(self, node: ast.AST, env: dict[str, _Value]) -> str:
        if isinstance(node, ast.Name):
            return "" if node.id in env else self.aliases.get(node.id, node.id)
        if isinstance(node, ast.Attribute):
            parent = self.name(node.value, env)
            return parent + "." + node.attr if parent else ""
        return ""

    def value(self, node: ast.AST, env: dict[str, _Value], depth: int = 0) -> _Value:
        if depth > 12:
            return _Value()
        if isinstance(node, ast.Constant) and isinstance(node.value, (str, bytes)):
            return _Value(node.value if len(node.value) <= MAX_LITERAL_BYTES else None)
        if isinstance(node, ast.Name):
            return env.get(node.id, _Value())
        if isinstance(node, ast.Attribute):
            if self.name(node, env) == "os.environ":
                return _Value(tags=frozenset({"environment"}))
            return self.value(node.value, env, depth + 1)
        if isinstance(node, ast.Subscript):
            return self.value(node.value, env, depth + 1)
        if isinstance(node, ast.BinOp) and isinstance(node.op, ast.Add):
            left, right = self.value(node.left, env, depth + 1), self.value(node.right, env, depth + 1)
            literal = None
            if type(left.literal) is type(right.literal) and isinstance(left.literal, (str, bytes)):
                if len(left.literal) + len(right.literal) <= MAX_LITERAL_BYTES:
                    literal = left.literal + right.literal
            return _Value(literal, left.tags | right.tags)
        if isinstance(node, ast.Call):
            name = self.name(node.func, env)
            args = [self.value(n, env, depth + 1) for n in node.args]
            args += [self.value(k.value, env, depth + 1) for k in node.keywords]
            tags = frozenset().union(*(v.tags for v in args))
            if name in {"os.getenv", "os.environ.get", "os.environ.copy"}:
                return _Value(tags=tags | {"environment"})
            if name in {"requests.get", "httpx.get", "urllib.request.urlopen"}:
                return _Value(tags=tags | {"download"})
            if name in {"base64.b64decode", "base64.standard_b64decode", "binascii.a2b_base64"} and args:
                decoded = _decode(args[0].literal) if isinstance(args[0].literal, (str, bytes)) else None
                return _Value(decoded, tags | {"encoded"})
            if isinstance(node.func, ast.Attribute) and node.func.attr == "decode":
                original = self.value(node.func.value, env, depth + 1)
                literal = None
                if isinstance(original.literal, bytes):
                    encoding = args[0].literal if args else "utf-8"
                    if encoding in {"utf-8", "utf8", "utf-16-le", "ascii"}:
                        try:
                            literal = original.literal.decode(encoding)
                        except UnicodeError:
                            pass
                return _Value(literal, original.tags | tags)
            if isinstance(node.func, ast.Attribute) and node.func.attr in {"read", "json"}:
                return _Value(tags=self.value(node.func.value, env, depth + 1).tags | tags)
            return _Value(tags=tags)
        if isinstance(node, (ast.List, ast.Tuple, ast.Set)):
            return _Value(tags=frozenset().union(*(self.value(n, env, depth + 1).tags for n in node.elts)))
        if isinstance(node, ast.Dict):
            return _Value(tags=frozenset().union(*(self.value(n, env, depth + 1).tags for n in node.values)))
        return _Value()

    def add(self, node: ast.AST, code: str, title: str, message: str) -> None:
        line = getattr(node, "lineno", 1)
        if (code, line) in self.seen or len(self.findings) >= MAX_FINDINGS:
            return
        self.seen.add((code, line))
        finding = make_finding(code, title, message, "WARN", "MEDIUM", "AUDIT", self.path, line,
                               actions=["WARNING"], remediation="Review the referenced source and its intended data flow. This advisory signal is not a malware verdict.")
        finding["kind"] = "AUDIT_EVIDENCE"
        self.findings.append(finding)

    def expressions(self, node: ast.AST, env: dict[str, _Value]) -> None:
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef, ast.Lambda)):
            return
        if isinstance(node, ast.Call):
            name = self.name(node.func, env)
            values = [self.value(n, env) for n in node.args] + [self.value(k.value, env) for k in node.keywords]
            tags = frozenset().union(*(v.tags for v in values))
            if name in {"exec", "eval", "builtins.exec", "builtins.eval"}:
                if "encoded" in tags:
                    self.add(node, "WG-RESEARCH-DECODE-EXEC", "Decoded data reaches dynamic execution",
                             "A statically traceable Base64-decoding value reaches exec/eval. No decoded payload was executed or included in this report.")
                if "download" in tags:
                    self.add(node, "WG-RESEARCH-DOWNLOAD-EXEC", "Downloaded data reaches dynamic execution",
                             "A statically traceable HTTP response value reaches exec/eval. Review the source and trust boundary before running it.")
            if name in {"requests.post", "requests.put", "requests.request", "httpx.post", "httpx.put"} and "environment" in tags:
                self.add(node, "WG-RESEARCH-ENV-NETWORK", "Environment data reaches an HTTP call",
                         "A statically traceable environment-derived value is supplied to an HTTP sending API. Verify that secrets cannot cross this boundary.")
        for child in ast.iter_child_nodes(node):
            self.expressions(child, env)

    def block(self, statements: list[ast.stmt], env: dict[str, _Value]) -> None:
        for node in statements:
            if time.monotonic() >= self.deadline:
                raise TimeoutError("Semantic analysis deadline exceeded")
            if isinstance(node, ast.Import):
                for alias in node.names:
                    self.aliases[alias.asname or alias.name.split(".")[0]] = alias.name if alias.asname else alias.name.split(".")[0]
                continue
            if isinstance(node, ast.ImportFrom):
                for alias in node.names:
                    self.aliases[alias.asname or alias.name] = (node.module or "") + "." + alias.name
                continue
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
                parameters = {a.arg: _Value() for a in node.args.posonlyargs + node.args.args + node.args.kwonlyargs}
                for parameter in (node.args.vararg, node.args.kwarg):
                    if parameter is not None:
                        parameters[parameter.arg] = _Value()
                parameters[node.name] = _Value()
                aliases = dict(self.aliases)
                self.block(node.body, parameters)
                self.aliases = aliases
                env[node.name] = _Value()
                continue
            if isinstance(node, ast.ClassDef):
                aliases = dict(self.aliases)
                self.block(node.body, {})
                self.aliases = aliases
                env[node.name] = _Value()
                continue
            if isinstance(node, (ast.Assign, ast.AnnAssign, ast.NamedExpr)):
                if node.value is not None:
                    self.expressions(node.value, env)
                    value = self.value(node.value, env)
                    targets = node.targets if isinstance(node, ast.Assign) else [node.target]
                    for target in targets:
                        if isinstance(target, ast.Name):
                            env[target.id] = value
                        else:
                            for nested in ast.walk(target):
                                if isinstance(nested, ast.Name):
                                    env[nested.id] = _Value()
                continue
            if isinstance(node, (ast.If, ast.For, ast.While, ast.Try, ast.With, ast.AsyncWith, ast.AsyncFor)):
                # Preserve ordering inside each branch, but do not manufacture cross-branch flows.
                for field in ("test", "iter", "items"):
                    value = getattr(node, field, None)
                    if isinstance(value, ast.AST):
                        self.expressions(value, env)
                for field in ("body", "orelse", "finalbody"):
                    self.block(getattr(node, field, []), dict(env))
                for handler in getattr(node, "handlers", []):
                    self.block(handler.body, dict(env))
                # Unknown reassignment after a branch invalidates prior simple def/use assumptions.
                for nested in ast.walk(node):
                    if isinstance(nested, ast.Name) and isinstance(nested.ctx, ast.Store):
                        env[nested.id] = _Value()
                continue
            self.expressions(node, env)


def analyze_snapshot(snapshot: Path, *, deadline: float) -> dict:
    findings: list[dict] = []
    notices: list[dict] = [{"code": "WG-RESEARCH-SCOPE", "message":
        "Experimental advisory checks: bounded literal decoding and simple Python def/use signals only. No project code is executed; no model accuracy or malware verdict is claimed."}]
    count, complete = 0, True
    for file in sorted(snapshot.rglob("*")):
        if not file.is_file() or file.suffix.lower() not in {".py", ".ps1", ".psm1"}:
            continue
        if time.monotonic() >= deadline:
            raise TimeoutError("Semantic analysis deadline exceeded")
        count += 1
        relative = file.relative_to(snapshot).as_posix()
        try:
            raw = file.read_bytes()
            source = raw.decode("utf-16" if raw.startswith((b"\xff\xfe", b"\xfe\xff")) else "utf-8-sig")
            if "\x00" in source:
                raise ValueError("Binary source")
            if file.suffix.lower() == ".py":
                tree = ast.parse(source)
                if sum(1 for _ in ast.walk(tree)) > MAX_AST_NODES:
                    raise ValueError("AST budget exceeded")
                analyzer = _PythonSignals(relative, deadline)
                analyzer.block(tree.body, {})
                findings.extend(analyzer.findings)
            else:
                for line_number, line in enumerate(source.splitlines(), 1):
                    if line.lstrip().startswith("#"):
                        continue
                    for match in list(_ENCODED_COMMAND.finditer(line)) + list(_DECODE_LITERAL.finditer(line)):
                        decoded = _decode(match[1])
                        if decoded is None:
                            complete = False
                            continue
                        decoded_text = _decoded_text(decoded)
                        behavior = bool(_PS_DOWNLOAD.search(decoded_text) and _PS_EXECUTE.search(decoded_text))
                        finding = make_finding("WG-RESEARCH-PS-DECODE", "Encoded PowerShell command requires review",
                            "Literal Base64 data contains download and execution tokens; this is a lexical signal, not proof of execution."
                            if behavior else "Literal Base64 data was decoded for inspection only. Encoding alone is not evidence of malicious behavior.",
                            "WARN" if behavior else "INFO", "MEDIUM", "AUDIT", relative, line_number,
                            actions=["WARNING"] if behavior else [])
                        finding["kind"] = "AUDIT_EVIDENCE"
                        findings.append(finding)
            if len(findings) > MAX_FINDINGS:
                findings = findings[:MAX_FINDINGS]
                complete = False
                break
        except (SyntaxError, UnicodeError, ValueError, RecursionError):
            complete = False
    if not complete:
        notices.append({"code": "WG-RESEARCH-PARTIAL", "message": "Some source data exceeded parsing/decoding budgets or could not be inspected."})
    return {"findings": findings, "scannedFiles": count, "complete": complete, "notices": notices}
