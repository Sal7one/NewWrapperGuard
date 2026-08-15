# WrapperGuard Backlog

The ordering is **security-first**. Until automatic scanning and enforcement work, WrapperGuard
is only a findings viewer — not a guard. UI polish and downstream integrations come after the
core protection promise holds.

## Core security principles (non-negotiable)
These rules govern every item below and must not be violated by any feature.

- **Never execute analyzed scripts.** The scanner reads files only; it never spawns processes,
  resolves network resources, or follows unsafe paths.
- **Repository content may tighten policy but may never silently weaken the user's trusted
  local or organization policy.** Inline `wrapperguard:ignore`, `.wrapperguard-baseline.json`,
  permissive project policies, allowed-host lists, and disabled rules that ship *inside* a
  repository are **untrusted until explicitly approved by the user**. A malicious repo must not
  be able to suppress its own payload.
- **Never silently follow execution paths outside the project root.** `apply(from="../x")`,
  `includeBuild`, `--init-script`, `~/.gradle/init.gradle`, symlinks and Windows junctions can
  all escape `basePath`. External paths must be resolved to canonical real paths, marked
  EXTERNAL, and either require explicit permission to scan or block execution as unresolved.
  An unscanned external file is never safe.
- **A clean scan can go stale between scan and run (TOCTOU).** Enforcement must revalidate an
  execution seal immediately before launch, not trust a cached report.
- **INFO/audit evidence never blocks.** ML output never blocks. Every finding has a stable rule ID.

## Done
- [x] Pure Kotlin core models, policy, scanner SPI (`wrapperguard-core`).
- [x] Deterministic rules + recursive call-chain scanner (`wrapperguard-rules`) — 44 stable rule IDs, DFS with cycle detection, `wrapperguard:ignore` suppression, regression tests against `old_project/test-samples`.
- [x] CLI emits canonical JSON and validates against `schema/wrapperguard-report.schema.json`.
- [x] IntelliJ plugin launches in IDEA 2025.3 via Jewel/Compose (`composeUI()` + `com.intellij.modules.compose`).
- [x] Four-tab Compose UI (Overview / Findings / Execution Graph / Policy) with theme-adaptive colors and per-tab scroll.
- [x] VS Code extension compiles; status bar + findings tree skeleton consumes CLI JSON.
- [x] Run guard: before-run task blocks BLOCK-policy findings, auto-attaches to new configs, back-fills existing ones, blocked dialog with Run Once.
- [x] File-change rescan: VFS watcher marks STALE immediately; content-hash-gated 3 s debounced rescan; single-flight with pending-rescan.
- [x] Credential redaction at rule creation + export sanitization pass (evidence.redacted flag; tests in core).
- [x] Headless end-to-end guard smoke tests + rescan tests (`wrapperguard-intellij/src/test`).
- [x] CLI e2e validated against `schema/wrapperguard-report.schema.json`; exit codes 0/2 verified.

## Now — make it a guard (not a viewer)

### 1. Trusted-project & startup scan
- [ ] Make the scanner `DumbAware` — it reads files, not PSI, so it does **not** wait for indexing. Scan immediately on startup; never gate on `DumbService` smart mode.
- [ ] **Scan on project open** — `ProjectActivity` (post-startup) runs a scan as early as possible, before the user can trigger a build/sync.
- [ ] **Use IntelliJ Trusted Projects as one protection input.** On an *untrusted* project, scan before asking the user to trust/import it (Gradle import is disabled in safe mode for untrusted projects, which gives a safer pre-import scan window). Subscribe to trust-state changes; re-scan when a project becomes trusted.
- [ ] **Be honest about limits.** Installing WrapperGuard into an already-open project cannot retroactively undo a Gradle sync that already ran. Surface "previously-imported, scan now" explicitly.
- [ ] **Scan on plugin install** into an already-open project.

### 2. Gradle-sync gate
- [ ] **Scan before Gradle sync/import.** Subscribe to the external-system import lifecycle; run a fresh scan *before* Gradle downloads/applies any wrapper, init script, or remote `apply from:`. This is the highest-risk moment (sync executes `init.gradle`, applies remote scripts, runs the wrapper).
- [ ] Block sync (when policy says BLOCK) via the sync gate, with the execution-blocked dialog.

### 3. Run guard
- [x] **`BeforeRunTaskProvider`** that runs a fresh scan before every applicable run configuration and returns `false` to hard-block when policy says BLOCK. Port `old_project/execution/ExecutionGuard.kt`.
- [x] Block only on **policy-selected actionable findings** (`policyAction == BLOCK`).
- [x] Auto-attach the task to **new** run configs (`createTask`) and **back-fill** existing configs on project open (`RunManagerImpl`).
- [x] **State the protection scope honestly in the UI:** Protected = IDE Gradle run configurations, supported IDE script configurations, Gradle sync when the sync gate is active. Not protected = external terminals, CLI invocations, processes launched by other plugins, scripts run outside the IDE. (CLI/CI covers the second category later.)
- [x] Execution-blocked `DialogWrapper` matching `design_reference/execution_blocked_dialog` — intercepted command, policy violations, "No project code was executed.", Run Once / Review Blockers.

### 4. TOCTOU & freshness (execution seal)
- [ ] **Execution seal**: before launch, compute a seal over file hashes + policy hash + baseline/suppression hash + scanner version + rule version. Revalidate immediately before process launch; abort if any protected input changed.
- [ ] **Save unsaved editor documents** (or explicitly account for them) before scanning, so the seal reflects what will actually run.
- [x] **Mark STALE immediately** when a relevant VFS event arrives; debounce only the *rescan*, not the stale state.
- [x] **Single-flight scanning**: only one authoritative scan generation exists. Cancel or supersede older scans; only the latest generation may publish a report.

### 5. Trusted policy / baseline model
- [ ] **Distinguish trusted vs untrusted suppression sources.** User/org-approved baselines and inline ignores are trusted; ones found *inside* a freshly-opened repo are untrusted until approved. Untrusted suppressions do not affect verdicts until the user accepts them.
- [ ] Fingerprint-based baseline (`.wrapperguard-baseline.json`) — moved up from "Later" because enforcement depends on a *trusted* baseline. Baselines shipped in-repo are untrusted on first open.
- [ ] Settings UI for approving baselines / suppressions and managing the trusted policy.

### 6. Wrapper verification (not just display)
- [ ] Approved wrapper JAR checksum verification (against a known-good list).
- [ ] Distribution checksum enforcement (`distributionSha256Sum`).
- [ ] **Unknown/changed wrapper JAR verdict** — an unknown wrapper about to execute must not be a harmless INFO row; escalate per policy.
- [ ] Policy for internally-approved custom wrappers.

### 7. File-change rescans
- [x] **Rescan on relevant file changes** — `BulkFileListener` watching `gradlew`, `gradlew.bat`, `gradle-wrapper.properties`, `*.gradle`, `*.gradle.kts`, `*.sh`, `*.bat`, `*.cmd`, `*.ps1`, `libs.versions.toml`. Debounce (~3 s) to absorb sync write bursts. Debounce only the rescan — mark STALE immediately.
- [x] **File-hash change detection** — only re-emit findings when content actually changed (not on VFS timestamp refreshes).

### 8. Project scoping (single project, never the world)
- [ ] **Scan exactly one project per launch** — the open IntelliJ project's `basePath`. Never walk up into a shared parent folder that holds many projects; never scan sibling projects.
- [ ] **Exclude invalid/irrelevant paths** — `.git`, `.gradle`, `.idea`, `build`, `out`, `.intellijPlatform`, `.kotlin`, `node_modules`, `target/`, `dist/`, `.cache/`, and anything outside `basePath` unless explicitly followed as an EXTERNAL edge (see principles).
- [ ] **Decide and document** whether user-level Gradle init scripts (`~/.gradle/init.gradle`, `~/.gradle/init.d/*.gradle`) are scanned or explicitly out of scope.
- [ ] **Multi-module projects** — scan all modules under the single project root; never cross into another project's root.

### 9. Hostile-input hardening
- [ ] Regex denial-of-service budgets (timeouts / step limits on the rule patterns).
- [ ] Huge-line and huge-file-count limits; deep-directory-tree and symlink-loop guards.
- [ ] Encoding handling: UTF-16 PowerShell, BOMs, malformed encodings, null bytes.
- [ ] Cancellation, memory/time budgets; parser exceptions must produce a finding, not crash the scan.
- [ ] Tests for each of the above against hostile fixtures.

### 10. Secret redaction everywhere
- [x] **Redact credential values by default in UI, logs, JSON, SARIF, Markdown export.** Store only a safe preview + fingerprint.
- [x] Never log complete environment variables, tokens, or authorization headers.

## Next — UI polish
- [ ] Fix any remaining overflow/clipping in the four tabs (now wrapped in scroll containers).
- [ ] Verify metric tiles, verdict banner, findings detail, and tab bar wrap correctly at narrow tool-window widths.
- [ ] "Protected / Not protected" scope indicator in the UI.
- [ ] Stale/incomplete/failed verdict banners and retry actions.

## Later
- [ ] Parser-aware PowerShell/Bash adapters (replace regex with AST where practical).
- [ ] Better execution-graph canvas (graphical nodes/edges) after the tree ships.
- [ ] SARIF/CI adapter (feeds the "Not protected" coverage gap).
- [ ] Rule packs and community rule SDK.
- [ ] Local multi-project console (separate dashboard, not a plugin feature).

### Research-backed PowerShell detection — BLOCKED
**Status:** Waiting for researcher.
**Dependency:** MPSAutodetect follow-up paper, model artifacts, preprocessing spec, dataset guidance, licensing confirmation, and direct integration support.
Keep the `MlScorer`/`NoopMlScorer` extension interfaces only. Do not implement the model or make architectural guesses before the dependency is resolved. ML output must never affect verdicts, policy, severity, or execution blocking.
