"""Fixed scanner runners over bounded input snapshots; never a shell-command template."""
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


DEPENDENCY_FILES = frozenset({"pom.xml", "gradle.lockfile", "verification-metadata.xml", "libs.versions.toml",
    "requirements.txt", "requirements-dev.txt", "poetry.lock", "Pipfile.lock", "pdm.lock", "uv.lock", "pyproject.toml",
    "package-lock.json", "npm-shrinkwrap.json", "pnpm-lock.yaml", "yarn.lock", "bun.lock", "go.mod", "go.sum",
    "Cargo.lock", "composer.lock", "Gemfile.lock", "packages.lock.json"})


def _snapshot(project_root: Path, destination: Path, deadline: float,
              extensions: frozenset[str] = frozenset({".py"}), dependency_only: bool = False) -> tuple[int, bool]:
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
                if entry.name.lower() == "psscriptanalyzersettings.psd1":
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
                    elif entry.is_file(follow_symlinks=False) and (
                        entry.name in DEPENDENCY_FILES if dependency_only else Path(entry.name).suffix.lower() in extensions
                    ):
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
                     deadline: float, maximum: int, accepted_codes: tuple[int, ...] = (0,)) -> bytes:
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
        if process.returncode not in accepted_codes:
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
        owned = Path(temporary).resolve()
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


def _rebase(document: object, snapshot: Path) -> object:
    """Map only actual snapshot-prefixed source paths back to project-relative locations."""
    prefixes = (str(snapshot) + os.sep, snapshot.as_uri() + "/")
    def visit(value: object, depth: int) -> object:
        if depth > 128:
            raise ValueError("Report nesting exceeds limit")
        if isinstance(value, str):
            for prefix in prefixes:
                if value.startswith(prefix):
                    return value[len(prefix):].replace(os.sep, "/") or "./"
            return value
        if isinstance(value, list):
            return [visit(v, depth + 1) for v in value]
        if isinstance(value, dict):
            return {visit(k, depth + 1): visit(v, depth + 1) for k, v in value.items()}
        return value
    return visit(document, 0)


def _environment(owned: Path) -> dict[str, str]:
    home = owned / "home"
    home.mkdir(mode=0o700)
    return {"PATH": os.defpath, "HOME": str(home), "TMPDIR": str(owned), "TEMP": str(owned), "TMP": str(owned),
            "PYTHONNOUSERSITE": "1", "PYTHONDONTWRITEBYTECODE": "1", "PYTHONSAFEPATH": "1", "PYTHONUTF8": "1",
            "POWERSHELL_TELEMETRY_OPTOUT": "1", "DOTNET_CLI_TELEMETRY_OPTOUT": "1"}


_PS_ANALYZE = r'''param([string]$ModulePath, [string]$SourceRoot)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$PSModuleAutoLoadingPreference = 'None'
Import-Module -Name $ModulePath -Force -WarningAction SilentlyContinue
$diagnostics = @(Invoke-ScriptAnalyzer -Path $SourceRoot -Recurse -IncludeDefaultRules -IncludeSuppressed -Settings @{})
$selected = @($diagnostics | Select-Object RuleName, Message, ScriptPath, IsSuppressed,
    @{Name='Severity';Expression={$_.Severity.ToString()}},
    @{Name='Extent';Expression={@{File=$_.Extent.File;StartLineNumber=$_.Extent.StartLineNumber;StartColumnNumber=$_.Extent.StartColumnNumber}}})
[Console]::Out.WriteLine((ConvertTo-Json -InputObject $selected -Depth 8 -Compress))
'''


def run_tool(profile: IntegrationProfile, project_root: Path) -> tuple[dict | list, list[dict]]:
    config = profile.runner
    if config is None:
        raise ValueError("Runner is not configured")
    if config.tool == "bandit":
        return run_bandit(profile, project_root)
    if os.name != "posix" or not hasattr(os, "O_NOFOLLOW"):
        raise ValueError("Snapshot runners require POSIX no-follow file access")
    deadline = time.monotonic() + config.timeout_ms / 1000
    with tempfile.TemporaryDirectory(prefix="wrapperguard-" + config.tool + "-") as temporary:
        owned = Path(temporary).resolve()
        snapshot = owned / "source"
        snapshot.mkdir(mode=0o700)
        dependencies = config.tool in {"trivy", "osv"}
        extensions = {"script-semantics": frozenset({".py", ".ps1", ".psm1"}),
                      "semgrep": frozenset({".kt", ".kts", ".java"})}.get(config.tool, frozenset({".ps1", ".psm1", ".psd1"}))
        count, incomplete = _snapshot(project_root, snapshot, deadline, extensions, dependencies)
        notices = []
        if incomplete:
            notices.append({"code": "WG-SDK-PARTIAL-SNAPSHOT", "message": "Some inputs were skipped because of symlinks, read failures or snapshot limits."})
        if config.tool == "script-semantics":
            from .semantics import analyze_snapshot
            result = analyze_snapshot(snapshot, deadline=deadline)
            if count == 0:
                result["complete"] = False
                notices.append({"code": "WG-SDK-NO-INPUTS", "message": "No supported regular source inputs were available."})
            return result, notices
        if count == 0:
            raise ValueError("No supported scanner inputs were available")
        environment = _environment(owned)
        accepted_codes = (0,)
        if config.tool == "psscriptanalyzer":
            script = owned / "analyze.ps1"
            script.write_text(_PS_ANALYZE, encoding="utf-8")
            argv = [str(config.executable), "-NoProfile", "-NonInteractive", "-File", str(script),
                    str(config.module_path), str(snapshot)]
        elif config.tool == "trivy":
            configuration, ignore = owned / "trivy.yaml", owned / "empty.ignore"
            configuration.write_text("{}\n", encoding="utf-8")
            ignore.write_text("", encoding="utf-8")
            cache = config.cache_path or owned / "cache"
            if config.cache_path:
                cache = cache.resolve()
                if cache.is_relative_to(project_root.resolve()):
                    raise ValueError("Trivy cache must remain outside the project")
            cache.mkdir(parents=True, exist_ok=True)
            argv = [str(config.executable), "fs", "--config", str(configuration), "--ignorefile", str(ignore),
                    "--cache-dir", str(cache), "--module-dir", str(owned / "modules"),
                    "--scanners", "vuln", "--pkg-types", "library", "--offline-scan", "--disable-telemetry",
                    "--skip-version-check", "--format", "json", "--exit-code", "0", "--quiet", "."]
        elif config.tool == "osv":
            configuration = owned / "osv-scanner.toml"
            configuration.write_text("", encoding="utf-8")
            argv = [str(config.executable), "scan", "source", "--recursive", "--no-resolve", "--config", str(configuration),
                    "--format", "json", "--all-packages", "--verbosity", "error", "."]
            accepted_codes = (0, 1)
        elif config.tool == "semgrep":
            rules = config.rules_path or Path(__file__).parent / "rules" / "android-ktor.yml"
            argv = [str(config.executable), "scan", "--config", str(rules), "--sarif", "--metrics", "off",
                    "--disable-version-check", "--no-git-ignore", "--disable-nosem", "--no-rewrite-rule-ids",
                    "--max-target-bytes", str(MAX_SOURCE_FILE_BYTES), "--timeout", "10", "--jobs", "1", "--quiet", "."]
        else:
            raise ValueError("Unsupported fixed runner")
        raw = _bounded_process(argv, snapshot, environment, deadline, config.max_output_bytes, accepted_codes)
        document = _rebase(load_json_document(raw), snapshot)
        if config.tool == "semgrep" and isinstance(document, dict):
            for run in document.get("runs", []):
                if not isinstance(run, dict):
                    continue
                # Semgrep uses %SRCROOT% for our explicit cwd, even when it omits
                # originalUriBaseIds. This fixed runner knows that root; a generic
                # imported SARIF report still must declare its own URI bases.
                bases = run.setdefault("originalUriBaseIds", {})
                if isinstance(bases, dict):
                    bases.setdefault("%SRCROOT%", {"uri": "./"})
                for record in run.get("results", []):
                    if not isinstance(record, dict) or not isinstance(record.get("suppressions"), list):
                        continue
                    # --disable-nosem emits these findings as active, while retaining
                    # inSource markers. Keep the findings active, never approve them.
                    record["suppressions"] = [s for s in record["suppressions"]
                                              if not isinstance(s, dict) or s.get("kind") != "inSource"]
        return document, notices


def runner_plan(tool: str) -> dict:
    plans = {
        "bandit": (["--ini", "<owned-empty-ini>", "--configfile", "<owned-empty-yaml>", "--ignore-nosec", "--exit-zero", "-q", "-f", "json", "-r", "."], "Regular Python source files; project analyzer configuration is excluded.", "No network access requested."),
        "psscriptanalyzer": (["-NoProfile", "-NonInteractive", "-File", "<owned-fixed-analyzer-script>", "<trusted-module-path>", "<snapshot>"], "Regular PowerShell source data, default analyzer rules and reviewed module only.", "No network access requested."),
        "trivy": (["fs", "--config", "<owned-empty-config>", "--ignorefile", "<owned-empty-ignore>", "--cache-dir", "<reviewed-cache-or-owned-temporary-cache>", "--module-dir", "<owned-empty-modules>", "--scanners", "vuln", "--pkg-types", "library", "--offline-scan", "--disable-telemetry", "--skip-version-check", "--format", "json", "--exit-code", "0", "--quiet", "."], "Allowlisted dependency manifest/lock files only; no build or dependency installation.", "Trivy may download vulnerability/index databases. Dependency resolution API lookups are disabled."),
        "osv": (["scan", "source", "--recursive", "--no-resolve", "--config", "<owned-empty-config>", "--format", "json", "--all-packages", "--verbosity", "error", "."], "Allowlisted dependency manifest/lock files only; unresolved transitives can remain uncovered.", "Dependency names and versions are sent to the configured tool's OSV vulnerability service."),
        "script-semantics": ([], "Regular Python and PowerShell source data; bounded literal decoding and simple data-flow signals only.", "No network or tool process."),
        "local-ai": ([], "Minimized metadata from up to five selected findings; source/evidence files are never sent.", "Explicit numeric loopback model server only; advisory review, no cloud endpoint."),
        "semgrep": (["scan", "--config", "<reviewed-local-or-bundled-rules>", "--sarif", "--metrics", "off", "--disable-version-check", "--no-git-ignore", "--disable-nosem", "--no-rewrite-rule-ids", "--max-target-bytes", str(MAX_SOURCE_FILE_BYTES), "--timeout", "10", "--jobs", "1", "--quiet", "."], "Regular Kotlin/Java source data; bundled Android/Ktor review rules or an explicitly selected external rule file.", "Local rules only; metrics/version checks disabled. No network access requested."),
    }
    if tool not in plans:
        raise ValueError("Unsupported runner")
    arguments, access, network = plans[tool]
    return {"fixedArguments": arguments, "inputAccess": access, "network": network}
