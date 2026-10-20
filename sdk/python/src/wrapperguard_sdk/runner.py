"""Fixed scanner runners over bounded input snapshots; never a shell-command template."""
from __future__ import annotations

import os
from pathlib import Path
import re
import stat
import tempfile
import time

from .profile import IntegrationProfile, load_json_document
from .rule_packs import RULE_PACKS
from .portable_process import bounded_process as _bounded_process

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
              extensions: frozenset[str] = frozenset({".py"}), dependency_only: bool = False,
              file_names: frozenset[str] = frozenset()) -> tuple[int, bool]:
    """Copy source data via anchored no-follow descriptors, never project programs."""
    if os.name == "nt":
        from .windows_snapshot import copy_windows_snapshot, SnapshotLimits
        return copy_windows_snapshot(project_root, destination, deadline, extensions=extensions,
            dependency_only=dependency_only, file_names=file_names, dependency_files=DEPENDENCY_FILES,
            excluded_directories=EXCLUDED_DIRECTORIES,
            limits=SnapshotLimits(MAX_SOURCE_FILES, MAX_SOURCE_FILE_BYTES, MAX_SOURCE_BYTES, MAX_SOURCE_DEPTH))
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
                        entry.name in DEPENDENCY_FILES if dependency_only else
                        Path(entry.name).suffix.lower() in extensions or entry.name in file_names
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


def runner_supported(tool: str) -> bool:
    return tool in {"local-ai", "http-audit"} or os.name == "nt" or (os.name == "posix" and hasattr(os, "O_NOFOLLOW"))


def run_bandit(profile: IntegrationProfile, project_root: Path) -> tuple[dict, list[dict]]:
    """Analyze static .py data; no project imports, dependency install or active network scan."""
    if not runner_supported("bandit") or profile.runner is None:
        raise ValueError("The active Bandit runner requires supported no-follow file access")
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
        environment = _environment(owned, home=home)
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
    # Go scanners such as OSV emit slash-separated Windows drive paths while
    # Path uses backslashes. Match only the exact owned root + separator, using
    # Windows case semantics, never an arbitrary absolute source path.
    windows_root = (str(snapshot).replace("\\", "/").rstrip("/") + "/"
                    if re.match(r"^[A-Za-z]:[\\/]", str(snapshot)) else None)
    def visit(value: object, depth: int) -> object:
        if depth > 128:
            raise ValueError("Report nesting exceeds limit")
        if isinstance(value, str):
            if windows_root is not None:
                portable = value.replace("\\", "/")
                if portable.casefold().startswith(windows_root.casefold()):
                    return portable[len(windows_root):] or "./"
            for prefix in prefixes:
                if value.startswith(prefix) or windows_root is not None and value.casefold().startswith(prefix.casefold()):
                    return value[len(prefix):].replace(os.sep, "/") or "./"
            return value
        if isinstance(value, list):
            return [visit(v, depth + 1) for v in value]
        if isinstance(value, dict):
            return {visit(k, depth + 1): visit(v, depth + 1) for k, v in value.items()}
        return value
    return visit(document, 0)


def _environment(owned: Path, *, home: Path | None = None) -> dict[str, str]:
    if home is None:
        home = owned / "home"
        home.mkdir(mode=0o700)
    environment = {"PATH": os.defpath, "HOME": str(home), "TMPDIR": str(owned), "TEMP": str(owned), "TMP": str(owned),
            "PYTHONNOUSERSITE": "1", "PYTHONDONTWRITEBYTECODE": "1", "PYTHONSAFEPATH": "1", "PYTHONUTF8": "1",
            "POWERSHELL_TELEMETRY_OPTOUT": "1", "DOTNET_CLI_TELEMETRY_OPTOUT": "1"}
    if os.name == "nt":
        import ctypes
        api = ctypes.WinDLL("kernel32", use_last_error=True)
        api.GetWindowsDirectoryW.argtypes = [ctypes.c_wchar_p, ctypes.c_uint32]
        api.GetWindowsDirectoryW.restype = ctypes.c_uint32
        buffer = ctypes.create_unicode_buffer(32768)
        length = api.GetWindowsDirectoryW(buffer, len(buffer))
        if not 0 < length < len(buffer):
            raise OSError("The Windows system directory could not be located")
        windows = Path(buffer.value)
        environment.update({"SystemRoot": str(windows), "WINDIR": str(windows),
            "PATH": str(windows / "System32"), "PATHEXT": ".EXE", "USERPROFILE": str(home),
            "APPDATA": str(home / "AppData" / "Roaming"), "LOCALAPPDATA": str(home / "AppData" / "Local")})
        for path in (environment["APPDATA"], environment["LOCALAPPDATA"]):
            Path(path).mkdir(parents=True)
    return environment


_PS_ANALYZE = r'''param([string]$ModulePath, [string]$SourceRoot)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
# Import only the runtime's own modules before disabling all implicit discovery.
# PSScriptAnalyzer uses Join-Path; serialization uses Select-Object/ConvertTo-Json.
Import-Module -Name ([IO.Path]::Combine($PSHOME, 'Modules', 'Microsoft.PowerShell.Management', 'Microsoft.PowerShell.Management.psd1')) -ErrorAction Stop
Import-Module -Name ([IO.Path]::Combine($PSHOME, 'Modules', 'Microsoft.PowerShell.Utility', 'Microsoft.PowerShell.Utility.psd1')) -ErrorAction Stop
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
    if not runner_supported(config.tool):
        raise ValueError("Snapshot runners require supported no-follow file access")
    deadline = time.monotonic() + config.timeout_ms / 1000
    with tempfile.TemporaryDirectory(prefix="wrapperguard-" + config.tool + "-") as temporary:
        owned = Path(temporary).resolve()
        snapshot = owned / "source"
        snapshot.mkdir(mode=0o700)
        dependencies = config.tool in {"trivy", "osv"}
        extensions = {"script-semantics": frozenset({".py", ".ps1", ".psm1"}),
                      "semgrep": frozenset({".kt", ".kts", ".java"})}.get(config.tool, frozenset({".ps1", ".psm1", ".psd1"}))
        file_names = frozenset()
        selected_packs = config.rule_packs or ("legacy-android-ktor",)
        if config.tool == "semgrep" and config.rules_path is None:
            extensions = frozenset(extension for pack in selected_packs for extension in RULE_PACKS[pack]['extensions'])
            file_names = frozenset(name for pack in selected_packs for name in RULE_PACKS[pack]['fileNames'])
        count, incomplete = _snapshot(project_root, snapshot, deadline, extensions, dependencies, file_names)
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
            rules = [config.rules_path] if config.rules_path else [Path(__file__).parent / 'rules' / RULE_PACKS[pack]['file'] for pack in selected_packs]
            argv = [str(config.executable), "scan", *[argument for rule in rules for argument in ('--config', str(rule))], "--sarif", "--metrics", "off",
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
        "http-audit": ([], "Bounded response headers/cookie attributes at explicitly owned numeric-address target URLs; response bodies are discarded.", "GET to exact reviewed origins and paths only. No DNS, proxy environment, redirects, crawler, exploit payloads or target startup."),
        "semgrep": (["scan", "--config", "<reviewed-local-or-bundled-rules>", "--sarif", "--metrics", "off", "--disable-version-check", "--no-git-ignore", "--disable-nosem", "--no-rewrite-rule-ids", "--max-target-bytes", str(MAX_SOURCE_FILE_BYTES), "--timeout", "10", "--jobs", "1", "--quiet", "."], "Regular Kotlin/Java source and exact AndroidManifest.xml files selected by local rule packs; or an explicitly selected external rule file.", "Local rules only; metrics/version checks disabled. No network access requested."),
    }
    if tool not in plans:
        raise ValueError("Unsupported runner")
    arguments, access, network = plans[tool]
    return {"fixedArguments": arguments, "inputAccess": access, "network": network}
