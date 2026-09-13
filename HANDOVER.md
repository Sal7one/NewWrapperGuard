# HANDOVER — WrapperGuard Next (v0.3.0)

Complete context for any agent/developer picking up this project. Read this top to bottom
before changing anything. Companion docs: AI_START_HERE.md (product brief & hard rules),
BACKLOG.md (remaining work), VALIDATION.md (what was verified and how), README.md,
CHANGELOG.md, docs/COMPONENTS.md, design_reference/ (visual targets per screen).

---

## Unreleased UI rebuild — 2026-09-08

The owner explicitly authorized replacing the UI from scratch. The IntelliJ tool window
has now been rebuilt in native Jewel/Compose; old visual-reference layouts and the previous
column-header sorting design are superseded for this surface. The VS Code UI is unchanged.

- Compact shared toolbar with scan/export/settings; service-driven scanning, cancellation,
  failure and stale status. UI observations are marshalled to the EDT.
- Summary contains actual counts, notices and scope. The arbitrary risk score and invented
  coverage percentage are removed. Count rows navigate to the matching findings scope.
- Findings uses a virtualized list and selectable evidence inspector. At 760 dp it switches
  from a side-by-side inspector to a list/detail route. Query, scope, order and selection
  survive tab navigation. Filter/sort controls expand on demand.
- Execution uses a bounded, scrollable graph, file-list fallback, and file inspector linking
  to findings. Cycles and disconnected components remain visible. Graphs above 200 linked
  files use the virtualized file list.
- Policy reads saved scanner settings and opens the real settings dialog. It distinguishes
  background Warn scans from the pre-run Enforce guard.
- Presentation logic: `FindingsPresentation.kt`, `ExecutionPresentation.kt`; shared layout:
  `components/WorkspaceComponents.kt`. Old MetricTile/Card/ScanStateBanner/WgLayout removed.
- IntelliJ tests now 13: guard 3, rescan/lifecycle 4, presentation 6.
- Live Android Studio visual smoke pass completed in wide/narrow layouts and Light/custom
  dark themes. Fixed secondary-text and graph-arrow contrast during that pass. Search,
  sort/selection, Summary/graph navigation, source-line navigation, Policy and real settings
  opening were exercised. The final zip is installed; original IDE theme/size restored.
  See VALIDATION.md for evidence, fixture, backup location and limits of the smoke pass.

The v0.3.0 sections below are the release handover; this section supersedes their UI map,
UI test count, and UI remaining-work descriptions.

## 0. State snapshot (verified as of this handover)

- Version 0.3.0 everywhere (Gradle modules, plugin, VS Code package, SARIF driver).
- Git repo initialized: 28 commits, tags v0.2.0 and v0.3.0. NOT pushed anywhere yet
  (owner decision: local-only, private until publish).
- License: Apache-2.0. old_project/ is git-ignored (kept locally, read-only reference).
- Test totals: core 9 · rules 25 · intellij 6 · VS Code (node:test) 19 — all green.
- Verification gates that MUST stay green (see §3): gradle build, npm test, npm run parity,
  npm run hardening, verifyPlugin.
- Artifacts: wrapperguard-intellij/build/distributions/wrapperguard-intellij-0.3.0.zip and
  vscode-extension/wrapperguard-0.3.0.vsix. The zip is installed into Android Studio at
  ~/Library/Application Support/Google/AndroidStudio2026.1.1/plugins/wrapperguard.
- Demo project used for UI/manual checks: /tmp/wg-demo.

---

## 1. What this project is

WrapperGuard is a static security scanner for Gradle wrappers, build scripts, shell/batch/
PowerShell scripts, and recursively-called scripts. It NEVER executes analyzed code.
Surfaces: IntelliJ/Android Studio plugin (primary, Jewel/Compose UI), CLI (canonical JSON +
SARIF), VS Code extension (full web-app UI).

Architecture (current):

    shared Kotlin scanner + canonical JSON     (wrapperguard-core, wrapperguard-rules)
            |                                 46 stable rule IDs, fingerprints, baselines
            +------------------+-------------------------------+
            |                  |                               |
    IntelliJ/AS plugin        CLI (JVM)              TypeScript port (VS Code)
    before-run guard      --json --sarif             runs IN-PROCESS, no CLI/JVM/config
    sync gate             --baseline <file>          parity-verified: identical verdicts,
    VFS rescan, seal      exit 0/2                   blockers, and every fingerprint
            |                  |                               |
            +------------------+-------------------------------+
                      canonical JSON report
                      schema/wrapperguard-report.schema.json

IMPORTANT — the VS Code extension does NOT shell out to the CLI. The owner explicitly asked
for the whole scanner to be migrated into the extension ("migrate the whole thing"). The TS
port lives in vscode-extension/src/scanner/ and is kept byte-for-byte equivalent to Kotlin
via the parity gate (npm run parity). If you change scanner behavior, you MUST change BOTH
implementations and keep parity green. The old AI_START_HERE rule "VS Code must consume CLI
JSON, not reimplement detection" is SUPERSEDED by this owner decision.

old_project/ is the previous working IntelliJ plugin kept as read-only reference. Never
modify it. Tests read its old_project/test-samples fixtures.

---

## 2. Module map (current)

### wrapperguard-core (pure Kotlin, NO IntelliJ imports — enforced)
- model/Models.kt — Finding, ScanReport (verdict/findings/edges/notices + blockers/
  warnings/auditEvidence helpers), BaselineEntry, enums incl. all 7 verdicts.
- policy/ScanPolicy.kt — actionFor(): AUDIT→REVIEW; ENFORCE→reachable ERRORs +
  blockedRuleIds→BLOCK; INFO never blocks.
- policy/ScanSettings.kt — allowlists, blockedCommands, maxScriptDepth, maxFileBytes,
  requireDistributionSha256, knownSafeRepositoryHosts, trustedWrapperChecksums,
  maxLineLength, maxFilesPerScan, maxDirectoryDepth.
- spi/ScannerApi.kt — Rule, ScannerEngine (scan with baseline param), ScriptReference,
  MlScorer/NoopMlScorer (intentionally inactive — ML BLOCKED, see §7).
- util/Fingerprints.kt — SHA-256 24-char stable fingerprints (baselines depend on them).
- util/CredentialRedaction.kt — masks secret assignments, auth headers, URL userinfo,
  credentials{} blocks; idempotent.
- util/UrlAllowlist.kt — host + regex allowlist, subdomain-aware.
- export/SarifExporter.kt — SARIF 2.1.0 export.

### wrapperguard-rules (pure Kotlin, NO IntelliJ imports)
- RuleIds.kt — 46 stable rule IDs (WG-SCRIPT-*, WG-WRAPPER-*, WG-BUILD-*, WG-CATALOG-*,
  WG-STRUCT-*, WG-SCAN-*). Canonical keys for policy/baselines. Never renumber.
- ScriptRules.kt — shell/bat/ps1 per-line engine. SUPPRESSION MODEL: repo-shipped
  wrapperguard:ignore markers are UNTRUSTED — findings are still emitted; the marker is
  stripped from line-level evidence so fingerprints stay stable when the user later
  approves. Only a trusted external baseline suppresses.
- WrapperPropertiesRules.kt, BuildScriptRules.kt (repo decision tree, remote apply, exec,
  network, dynamic code, credentials redacted, unstable deps, init scripts),
  VersionCatalogRules.kt, ProjectStructureAnalyzer.kt (wrapper-JAR SHA-256 observation +
  WG-WRAPPER-007 escalation when a trusted checksum list is configured),
  FileTypeRouter.kt, CalledScriptsResolver.kt, SafePathResolver.kt (root-containment).
- RepositoryScanner.kt — DFS with cycle detection + memoization, deterministic sorted walk,
  trusted-baseline application, hostile-input hardening (UTF-16/BOM decode, null-byte
  rejection, oversized-line skip, depth/file caps). Excludes .git .gradle .idea build out
  .intellijPlatform .kotlin node_modules.
- Tests: RepositoryScannerTest (13), BaselineSuppressionTest (1), WrapperJarEscalationTest
  (3), HostileInputTest (5), UntrustedSuppressionTest (3).

### wrapperguard-cli
- Main.kt — args: [path] --json --enforce --baseline <file> --sarif. Default WARN (exit 0);
  --enforce exit 2 only when ACTIVE BLOCK findings remain. --sarif prints SARIF instead of
  the report. Missing baseline file = no suppressions (not an error).

### wrapperguard-intellij (Jewel/Compose; Swing only at the platform boundary)
- WrapperGuardProjectService.kt — single-flight scan/scanAsync, STALE state, content-hash
  seal (computeSeal), watched-file hashing, debounced rescan, publishExternalReport.
- WrapperGuardVfsListener.kt — BulkFileListener; watched files: gradlew, gradlew.bat,
  gradle-wrapper.properties, gradle-wrapper.jar, libs.versions.toml, *.gradle(.kts), *.sh,
  *.bat, *.cmd, *.ps1. Marks STALE immediately; rescan debounced 3 s.
- WrapperGuardStartupActivity.kt — scan on open (no smart-mode wait), VFS listener,
  before-run back-fill into EXISTING run configs, Gradle-sync gate (ProjectDataImport-
  Listener post-import ENFORCE re-validation), Trusted Projects listener (rescan on trust
  change). Note: sync launches themselves are guarded because they run through an
  ExternalSystemRunConfiguration covered by the before-run provider.
- execution/WrapperGuardBeforeRunTaskProvider.kt — run guard: saveAllDocuments, ENFORCE
  scan, TOCTOU seal (before/after watched-file hashes, one retry, synthetic WG-SCAN-TOCTOU
  blocker if still churning), blocks only on ACTIVE BLOCK findings, scanner failure fails
  OPEN, ExecutionBlockedDialog with Run Once / Cancel / Review Blockers. isApplicable is a
  companion function; the dialog seam is protected open fun askUserToRunOnce (tests override).
- settings/ — WrapperGuardSettings (persisted wrapperguard.xml: allowed hosts text, checksum
  requirement, scan toggles) + WrapperGuardConfigurable (Settings → Tools → WrapperGuard).
  buildScanSettings() merges user settings over defaults for every scan.
- ui/ — WrapperGuardApp (custom TabBar + scan-state banners), OverviewTab, FindingsTab
  (search, severity/policy segments, category chips, detail inspector), ExecutionGraphTab
  (LAYERED CALL-GRAPH CANVAS: barycenter layout, bezier edges with arrowheads, red dashed
  blocker hops with line labels, dot-grid background, clickable node cards with entry/
  category chips and suspicious badges, detached-file chips, risky-path explorer),
  PolicyTab, WgTheme, EditorNavigation, ReportExport (sanitizes evidence on export).
- plugin.xml — depends com.intellij.modules.platform, com.intellij.modules.compose (needs
  252+), com.intellij.gradle; registers projectService, applicationService, application-
  Configurable, toolWindow, postStartupActivity, beforeRunTaskProvider.
- Tests: GuardSmokeTest (3: malicious blocks + dialog requested + redacted evidence; clean
  passes; back-fill) and FileChangeRescanTest (3: stale→rescan, unchanged-content restore,
  wrapper-jar watched + TOCTOU report).

### vscode-extension (npm/tsc, NOT a Gradle module)
- src/scanner/ — the complete TypeScript port (models, policy, fingerprints, redaction,
  URL allowlist, script/build/wrapper-props/catalog/structure rules, called-scripts,
  safe-path, repository scanner, baseline, SARIF). In-process, no CLI, no cliPath setting.
- src/webviewUi.ts — the whole web app (app bar, tabs, verdict pill, summary card,
  overview tiles, searchable grouped findings + detail pane, execution graph canvas with
  zoom/fit/search/hover-tracing, policy pane, status footer). One big template literal —
  client JS must be plain ES5-style (no TS types), and there is a delivered-script syntax
  check (see §8 traps).
- src/extension.ts — state machine (not-scanned/scanning/scanned/failed), status bar with
  verdict colors, scan-on-open, suppress (marker + trusted baseline), baseline/restore,
  JSON + SARIF export, webview error logging to Output channel "WrapperGuard".
- tests/ — 19 node:test tests (redaction, fingerprints, policy, scanner integration).
- scripts/parity.js + scripts/hardening.js — cross-scanner gates (see §3).

### schema/ — wrapperguard-report.schema.json (draft 2020-12) + sample. CLI JSON validates.

---

## 3. Build, test, verify (macOS, this machine)

    cd /Users/salehalanazi/Documents/wrappppper/wrapperguard-next-starter

    ./gradlew build                                   # all modules + all Kotlin tests
    ./gradlew :wrapperguard-rules:test                # 25 tests
    ./gradlew :wrapperguard-intellij:test             # 6 tests
    ./gradlew :wrapperguard-intellij:runIde           # IDEA 2025.3 sandbox
    ./gradlew :wrapperguard-intellij:runIde --args="/tmp/wg-demo"
    ./gradlew :wrapperguard-intellij:buildPlugin      # zip under build/distributions/
    ./gradlew :wrapperguard-intellij:verifyPlugin     # binary-compat vs IU-2025.3 (~1-4 min)
    ./gradlew :wrapperguard-cli:installDist           # then run
    wrapperguard-cli/build/install/wrapperguard-cli/bin/wrapperguard-cli old_project/test-samples --json --enforce

    cd vscode-extension
    npm ci && npm test        # 19 tests (node:test, zero extra deps)
    npm run parity            # TS scanner vs Kotlin CLI — MUST show PARITY OK, zero diffs
    npm run hardening         # 5 hostile-input checks
    npx @vscode/vsce package  # .vsix artifact

JAVA_HOME: the system JDK is 26; the Gradle daemon already runs on Android Studio's JBR 21
via org.gradle.java.home, and the Kotlin toolchain (21) resolves on its own. No manual
export is needed on this machine; on a fresh machine, point JAVA_HOME at any JDK 21.

NOTE: buildPlugin/buildSearchableOptions/verifyPlugin fail with "Only one instance of IDEA
can be run at a time" while a runIde sandbox is open — close the sandbox IDE first.

Validate CLI JSON: python3 jsonschema against schema/wrapperguard-report.schema.json.

---

## 4. Contracts that MUST NOT break

1. Parity gate. Scanner changes go into BOTH wrapperguard-rules (Kotlin) and
   vscode-extension/src/scanner (TS). npm run parity must stay PARITY OK (same verdicts,
   finding counts, blockers, per-rule counts, and every fingerprint). The parity fixtures
   are /tmp/wg-demo, old_project/test-samples, and the repo root.
2. Fingerprint stability. Fingerprints are SHA-256(ruleId + NUL + path-with-slash + NUL +
   trimmed evidence).slice(24). They are the keys for baselines/suppression — changing the
   normalization invalidates users' baselines. Keep the ignore-marker stripping in
   ScriptRules (both sides) so fingerprints are stable before/after suppression.
3. Rule IDs are immutable keys. Add new ones, never renumber.
4. Trust model: repo-shipped wrapperguard:ignore and .wrapperguard-baseline.json are
   UNTRUSTED. Only baselines from outside the repo (VS Code global storage, CLI --baseline,
   user/org config) suppress. A malicious repo must not be able to suppress its own payload.
5. Exit code semantics: CLI exit 2 = blockers present = a SUCCESSFUL scan (consumers must
   parse stdout on exit 2 — see the VS Code history for the bug this caused).
6. INFO/audit evidence never blocks; ML never blocks (ML is BLOCKED — do not implement).
7. Core/rules/CLI must not import com.intellij (there is a test asserting the SPI package
   name). VS Code client JS must remain valid ES5-style (see delivered-script check).
8. old_project/ is read-only. .DS_Store and *.zip are ignored; don't commit artifacts.

---

## 5. Toolchain pins (do not drift casually)

| What | Value | Why |
|---|---|---|
| Kotlin | 2.2.20 | matches IPGP 2.11 expectations |
| org.jetbrains.intellij.platform | 2.11.0 | 2.16.0 injects a compose-compiler option Kotlin rejects |
| Platform SDK | intellijIdea("2025.3") | com.intellij.modules.compose requires 2025.3+ |
| sinceBuild | 252 | same reason |
| Compose dep | composeUI() inside dependencies{intellijPlatform{}} | official pattern; do NOT use bundledModule(intellij.platform.compose) or standalone jewel artifacts |
| Gradle | 9.1.0 wrapper | — |
| JVM toolchain | 21 | — |
| kotlinx-serialization-json | 1.8.0 | declared where used (does not leak transitively) |
| kotlin.stdlib.default.dependency=false | global | pure modules must declare implementation(kotlin("stdlib")) |
| VS Code | typescript 5.x, @types/vscode ^1.95, node >= 22, no runtime deps | node:test built-in runner |

---

## 6. Jewel/Compose gotchas (current, verified)

1. Width squeeze: mount the compose panel inside a JPanel(BorderLayout) wrapper
   (WrapperGuardToolWindowFactory) or the tool window shrinks to intrinsic width. KEEP IT.
2. JewelComposePanel in 2025.3: use NAMED arguments —
   JewelComposePanel(config = { }, content = { ... }). There is a deprecated
   focusOnClickInside boolean overload; don't call positionally with two lambdas.
3. JewelTheme.isDark is a Boolean PROPERTY, not a function.
4. Custom TabBar in WrapperGuardApp is the design-accurate approach; don't switch to
   TabStrip/TabStripState.
5. No return inside @Composable lambdas; use if/else.
6. Modifier.weight only inside Row/Column with bounded constraints (watch scroll parents).
7. Theme tokens: JewelTheme.globalColors.{text{normal,disabled,info,error,warning},
   panelBackground, toolwindowBackground, borders.normal}; severity/verdict mapping in
   WgTheme.kt.
8. Bundle jars for API inspection:
   ~/.gradle/caches/.../idea-2025.3-aarch64/lib/modules/intellij.platform.jewel.*.jar
9. SVG inside webviews: CSS var() is INVALID in SVG attributes (falls back to black). Use
   CSS classes for stroke/fill and fill="context-stroke" for markers (see the graph fix
   history). Canvas draw-scope colors must be resolved OUTSIDE the Canvas draw lambda
   (theme helpers are @Composable).

---

## 7. Behavioral decisions made (deliberate, keep)

- Stable rule IDs replace message-string identification.
- Run guard blocks ONLY on ACTIVE BLOCK findings (ENFORCE); scanner exceptions fail OPEN.
- TOCTOU seal: save all documents, hash watched files before/after the scan, one retry,
  then a synthetic WG-SCAN-TOCTOU blocker.
- Untrusted suppression (§4.4). Suppress actions (VS Code) write the inline marker AND
  add the fingerprint to the trusted baseline in one click; Restore removes the baseline
  entry (the marker stays — it is inert anyway).
- Wrapper-JAR escalation only when trustedWrapperChecksums is non-empty (unconfigured
  users keep the INFO observation).
- Credential evidence is redacted at creation (both scanners) and again on JSON export.
- VS Code scans in ENFORCE mode (matches the guard policy); settings
  wrapperGuard.{scanOnOpen,allowedHosts,blockedCommands,requireDistributionSha256}.
- Auto-scan on open + on first tool-window open; single-flight scan generation.
- old_project is not part of the build; its test-samples are parity/regression fixtures.

---

## 8. Traps that cost hours (agent-facing, read carefully)

1. Template-literal escaping when writing files: backticks terminate the literal; dollar-
   brace sequences interpolate; \1 \u0000 \n get processed. Prefer the write tool with
   String.raw (still interpolates dollar-brace — avoid it), or double every backslash.
   Kotlin raw strings need SINGLE backslashes in the file (heredocs are verbatim, run_code
   template literals are NOT).
2. The webview: the delivered inline script differs from the TS source (template escapes
   processed once). After editing webviewUi.ts, ALWAYS run the delivered-script check:
   evaluate the template, extract the script tag, node --check it. Type annotations leaked
   into the client JS and backslash escapes breaking the delivered script are the two bugs
   that shipped this way.
3. JS regex iteration: String.prototype.exec on a NON-global regex returns the same match
   forever (infinite loop); String.replace with a non-global regex replaces only the FIRST
   match. Kotlin's Regex.replace/findAll replace/find ALL — port accordingly (this caused
   the "apiKey not redacted" parity bug).
4. The edit tool requires a fresh read of the file (read → edit → write sequence) and
   aborts whole multi-edit programs on one mismatch — apply edits one at a time or use
   python splices for fragile content.
5. buildSearchableOptions/buildPlugin/verifyPlugin lock the sandbox (close runIde first).
6. Exit code 2 is success (blockers found) — node execFile throws on it; catch and parse
   e.stdout.
7. Screen capture on macOS needs Screen Recording permission; the owner runs visual passes
   themselves — do not block on screenshots.
8. Gradle configuration cache is enabled; after touching build scripts a plain
   ./gradlew ... handles invalidation, but weird staleness is usually fixed by
   ./gradlew --stop or deleting .gradle.

---

## 9. Verified vs NOT verified (be honest)

VERIFIED: full clean build green; core 9 / rules 25 / intellij 6 / node 19 tests green;
parity 3/3 fixtures zero-diff; hardening 5/5; Plugin Verifier Compatible with IU-2025.3;
CLI JSON + SARIF output validated; plugin zip + .vsix packaged; zip installed into Android
Studio 2026.1.1; sandbox IDE launched with the plugin loaded (log line "Loaded custom
plugins: WrapperGuard"); VS Code extension runs in the Extension Development Host.

NOT verified (human steps, owned by the project owner):
1. Final visual pass verdict on the IntelliJ UI batch (tabs/canvas/settings) — sandbox
   ready via runIde --args="/tmp/wg-demo".
2. Android Studio launch with the installed zip (AS 2026.1.1, build 261 >= sinceBuild 252).
3. Light/dark toggle across all tabs (semantic tokens assumed adaptive).

---

## 10. Decisions already made by the owner (do not re-ask)

- License: Apache-2.0 (done).
- old_project/: excluded from the published repo, kept locally (git-ignored).
- Repo: local-only for now — NO push/release/Marketplace yet; make public after the guard
  work lands (it has landed; publishing is the owner's call).
- VS Code: full scanner migration into TypeScript (in-process), parity-locked.
- Android Studio: zip installed into the AS plugins dir (refresh by re-running the unzip in
  §3 into ~/Library/Application Support/Google/AndroidStudio2026.1.1/plugins/wrapperguard).
- Marketplace: later, not now.

OPEN question still unanswered: whether user-level Gradle init scripts (~/.gradle/init.d)
are in scan scope (BACKLOG §8).

---

## 11. Remaining work (small — see BACKLOG.md)

- Parser-aware PowerShell/Bash AST adapters (replace regex where practical).
- IntelliJ baseline-approval UI (VS Code already has one).
- Visual acceptance of the rebuilt IntelliJ UI (see unreleased section).
- Rule packs / community rule SDK.
- Marketplace publish + repo push (owner's call).
- ML/PowerShell detector: BLOCKED until the researcher delivers the follow-up paper +
  artifacts + integration guidance. Keep only the inactive MlScorer/NoopMlScorer seam.

---

## 12. Environment quirks

- macOS aarch64. System JDK 26; Gradle daemon uses Android Studio's JBR 21.
- /tmp/wg-demo is the demo project for UI and parity; recreate with the fixture in the
  repo history if missing (gradlew -> scripts/bootstrap.sh -> scripts/post-install.sh plus a
  malicious build.gradle).
- design_reference/*/screen.png are renders of the sibling code.html — visual acceptance
  references. Dashboard page is OUT of scope.
- Duplicate IC SDK transforms in ~/.gradle/caches/9.1.0/transforms can eat ~3 GB each;
  safe to delete duplicates if disk space is needed.
- wrapperguard-next-starter.zip in the parent folder is an old snapshot — ignore.
