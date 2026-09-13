# WrapperGuard handover

Updated 2026-09-13. Start here for engineering work. This is the single source for
architecture, project constraints, validation evidence, and remaining work.
[README.md](README.md) covers usage; [CHANGELOG.md](CHANGELOG.md) records release history;
[vscode-extension/README.md](vscode-extension/README.md) is the extension's packaged description.

## Current state

- Implementation baseline: `24695d0` (`phase 1`), which commits the IntelliJ UI rebuild.
  The working tree was clean before this documentation cleanup.
- Package version remains **0.3.0**. Tags `v0.2.0` and `v0.3.0` exist; the rebuilt UI is
  subsequent unreleased work, not a newly tagged release.
- The owner authorized replacing the UI from scratch. IntelliJ/Android Studio now has
  the rebuilt native Jewel/Compose workspace. VS Code's separate webview is unchanged.
- There are **46 stable rule IDs** in Kotlin `RuleIds.kt`, mirrored by the TypeScript port.
- Last implementation validation: **2026-09-08**, recorded below. This cleanup changes
  documentation and source comments only; it does not constitute a new build or UI pass.
- Local artifacts exist at `wrapperguard-intellij/build/distributions/wrapperguard-intellij-0.3.0.zip`
  and `vscode-extension/wrapperguard-0.3.0.vsix` (checked 2026-09-13).
- Apache-2.0. Keep the repository local/private until the owner requests publication.
  Do not push, create releases, or submit to a Marketplace without that request.
- `old_project/` is git-ignored, outside the build, and **read-only**. Keep its regression
  fixtures. Do not clean up that reference directory or commit generated zip/VSIX artifacts.

## Product and architecture

WrapperGuard statically reads Gradle wrappers, build scripts, shell/BAT/PowerShell scripts,
version catalogs, and discovered script calls. It never executes analyzed code or uploads
source. IntelliJ and the CLI share the Kotlin scanner. VS Code runs a complete TypeScript
port in process, with no CLI or JVM dependency. That port is an explicit owner decision;
**do not replace it with a CLI adapter or re-ask the architecture decision**.

| Location | Responsibility |
|---|---|
| `wrapperguard-core/` | Pure Kotlin report models, policy/settings, fingerprints, redaction, URL allowlists, SARIF, scanner SPI and inactive ML seam |
| `wrapperguard-rules/` | Deterministic rules, recursive call resolution, safe paths, project structure, trusted baseline application and hostile-input limits |
| `wrapperguard-cli/` | Canonical JSON/SARIF and explicit baseline input |
| `wrapperguard-intellij/` | Project scan service, VFS rescan, run/sync integration, execution seal, settings and native UI |
| `vscode-extension/src/scanner/` | TypeScript scanner port; keep equivalent to Kotlin |
| `vscode-extension/src/extension.ts` | Scan lifecycle, status bar, settings, suppression/baselines and exports |
| `vscode-extension/src/webviewUi.ts` | VS Code interface and embedded client script |
| `schema/` | Report schema and sample; preserve compatibility for consumers |

The rules module contains `ScriptRules`, `BuildScriptRules`, `WrapperPropertiesRules`,
`VersionCatalogRules`, `ProjectStructureAnalyzer`, `CalledScriptsResolver`, `SafePathResolver`,
and `RepositoryScanner`. The scanner uses deterministic traversal with cycle detection,
trusted baseline application, UTF-16/BOM handling, and file/line/depth limits. Its current
excluded directory names are `.git`, `.gradle`, `.idea`, `build`, `out`, `.intellijPlatform`,
`.kotlin`, and `node_modules`; do not describe proposed exclusions as implemented.

## IntelliJ service, enforcement and UI

`WrapperGuardProjectService` scans `project.basePath`, coordinates single-flight background
work and pending rescans, publishes report/freshness/busy/error state, and hashes watched
files. `WrapperGuardVfsListener` marks reports stale immediately; rescans are debounced
about three seconds and gated by content changes. Watched inputs include wrapper scripts,
wrapper properties/JAR, version catalogs, Gradle files, and shell/BAT/CMD/PowerShell scripts.

`WrapperGuardStartupActivity` scans without waiting for indexing, registers VFS/trust-state
listeners, back-fills before-run tasks, and revalidates after Gradle import. The before-run
provider covers supported run configurations including external-system configurations;
post-import revalidation alone cannot prevent code that already ran. External terminals
and unsupported launch paths are outside the IDE guard's interception scope.

`WrapperGuardBeforeRunTaskProvider` saves documents, compares watched-file hashes before
and after an Enforce scan, retries once on changes, and emits `WG-SCAN-TOCTOU` if inputs keep
changing. This is a **file-hash seal**, not a complete policy/baseline/version seal. Active
BLOCK findings trigger `ExecutionBlockedDialog` with Cancel, Run Once, and Review Blockers.
Scanner exceptions currently fail open; this is an existing deliberate behavior.

Settings → Tools → WrapperGuard persists allowed hosts, distribution checksum requirement,
and scan toggles. `buildScanSettings()` merges saved settings with defaults. IntelliJ
background scans use Warn; the pre-run guard uses Enforce. VS Code scans use Enforce.

The rebuilt tool window has Summary, Findings, Execution, and Policy tabs:

- `WrapperGuardApp.kt`: compact scan/export/settings toolbar, navigation, service-driven
  scan status, saved settings and persistent findings state. Report observations enter
  Compose on the EDT.
- `OverviewTab.kt`: actual counts linking to the matching findings scope, metadata,
  notices and protection scope. No arbitrary risk score or fabricated coverage percentage.
- `FindingsTab.kt`: virtualized list, expandable search/filter/order controls, selectable
  evidence and editor navigation. At **760 dp** or wider, the inspector sits beside the
  list; below that it is a detail route with Back. Query, scope, order and selection survive
  tab navigation. `FindingsPresentation.kt` holds pure presentation logic.
- `ExecutionGraphTab.kt`: bounded graph with scrollbars, file-list alternative, incoming/
  outgoing call inspector and finding links. `ExecutionPresentation.kt` preserves cycles
  and disconnected components. More than **200 linked files** uses the virtualized list.
- `PolicyTab.kt`: actual saved settings and real settings action; explains Warn vs Enforce.
- `components/WorkspaceComponents.kt`: page/section/divider/chip primitives and scrollbars.
  `SharedComponents.kt` retains StatusBadge, LabeledValue and EmptyHint. Old MetricTile,
  Card, ScanStateBanner and WgLayout were removed.
- `WgTheme.kt`: theme-derived semantic colors; secondary text uses normal text with reduced
  opacity because some custom themes make disabled text unreadable. Graph arrows have
  stronger contrast. `EditorNavigation.kt` and `ReportExport.kt` own platform actions.

## Contracts and resolved decisions

1. Never execute analyzed scripts, fetch their remote resources, or upload source during
   scanning. Unscanned/out-of-root targets must not silently become trusted inputs.
2. Scanner changes belong in **both Kotlin and TypeScript**, with parity preserved for
   verdicts, findings, active blockers and fingerprints. Keep rule behavior aligned too.
3. Rule IDs are immutable keys: add new ones, never renumber. Fingerprints use the first
   24 hex characters of SHA-256 over `ruleId + NUL + slash-normalized path + NUL + trimmed
   evidence`. Changing normalization invalidates baselines. Preserve ignore-marker stripping.
4. Repository-shipped `wrapperguard:ignore` markers and baselines are untrusted and must
   not automatically suppress findings. Approved fingerprints live outside the repository
   (VS Code global storage or an explicitly supplied CLI baseline). VS Code Suppress adds
   the inert marker and trusted fingerprint; Restore removes the trusted baseline entry.
5. CLI exit **2 means a successful scan with blockers**, not a scanner failure. Consumers
   must parse stdout on exit 2. Default CLI mode is Warn; `--enforce` enables blocking.
6. Enforcement is based on ACTIVE findings with policy action BLOCK. Preserve the intended
   INFO/audit-never-blocks invariant and never allow ML to influence enforcement.
7. Credential evidence is redacted in both scanners and sanitized again on export. Do not
   log raw credentials, tokens, or complete environment variables.
8. Core, rules and CLI must remain free of `com.intellij` imports. UI ordering/filtering
   must not change report contents or fingerprints.
9. Wrapper-JAR escalation uses a configured non-empty trusted checksum list. Without one,
   the hash observation remains informational. IntelliJ baseline approval UI is future work.
10. Preserve local-only publication, the in-process TypeScript port, and `old_project/` as
    read-only. These decisions do not need another approval round.

## Build and verification

Run from the repository root:

```sh
./gradlew build :wrapperguard-intellij:verifyPlugin
./gradlew :wrapperguard-cli:installDist
cd vscode-extension
npm ci
npm test
npm run parity
npm run hardening
```

These are required gates for implementation work. Parity requires a built CLI distribution
and compiled TypeScript. The default fixtures are `/tmp/wg-demo`, `old_project/test-samples`,
and the repository root. **Missing fixtures are skipped**, so report the actual coverage;
a zero exit code does not prove all fixtures ran. `/tmp/wg-demo` is currently missing.
`old_project/test-samples` exists locally but is git-ignored: regression tests depend on it;
do not assume an ordinary fresh checkout or CI has that fixture directory.

Useful commands from the repository root:

```sh
./gradlew :wrapperguard-intellij:runIde
./gradlew :wrapperguard-intellij:buildPlugin
wrapperguard-cli/build/install/wrapperguard-cli/bin/wrapperguard-cli <project> --json --enforce
wrapperguard-cli/build/install/wrapperguard-cli/bin/wrapperguard-cli <project> --sarif --baseline <trusted-file>
```

Package VS Code from `vscode-extension/` with `npx @vscode/vsce package`.
Close the **runIde sandbox** before buildPlugin/buildSearchableOptions/verifyPlugin; those
operations can conflict over the same sandbox lock. Close Android Studio before replacing
its installed plugin. Keep a backup and verify the installed JARs match the new zip.

| Toolchain | Repository pin |
|---|---|
| Gradle wrapper | 9.1.0 |
| Kotlin / Compose compiler | 2.2.20 |
| IntelliJ Platform Gradle plugin | 2.11.0 |
| Development/verifier IDE | IntelliJ IDEA 2025.3 |
| Plugin minimum build | 252; do not confuse this with the development SDK version |
| JVM toolchain | 21 |
| kotlinx serialization JSON | 1.8.0 |
| VS Code API | ^1.95.0 |
| TypeScript / Node types | ^5.7.0 / ^22.0.0; lockfile resolves dependencies, CI uses Node 22 |

Use `composeUI()` in `intellijPlatform` dependencies and the platform Compose module;
do not replace these with independent Jewel artifacts. Pure Kotlin modules explicitly
include stdlib because `kotlin.stdlib.default.dependency=false` is set globally.
On the last validated machine the system JDK was 26 while Gradle used Android Studio's
JBR 21. That daemon choice is machine-local, not pinned in repository `gradle.properties`.
Use JDK 21 when setting up another machine.

## Last validation evidence — 2026-09-08

- `./gradlew build :wrapperguard-intellij:verifyPlugin` passed. Verifier: compatible with
  IU-253.28294.334; 4 deprecated and 21 experimental API usages. Android Studio was checked
  by live installation, not this verifier configuration.
- Kotlin tests: core **9**, rules **25**, IntelliJ **13**, no failures. IntelliJ coverage:
  guard 3, rescan/lifecycle 4, presentation 6. The rebuild added busy/completion state,
  stale status, active-only scopes, numeric line/priority ordering, multi-term search and
  rootless-cycle coverage.
- Node tests **19/19**, hardening **5/5**. Parity matched on `old_project/test-samples` and
  repository root; `/tmp/wg-demo` was missing and skipped. Do not claim three-fixture coverage.
- Live Android Studio Quail 1 | 2026.1.1 Patch 2: wide split view and narrow list/detail,
  built-in Light and [Legacy] Gerry Violet, Summary-to-Findings links, search, sorting with
  selection retained, graph/file/finding navigation, Policy and real settings opening.
  Open in editor landed at `scripts/post-install.sh`, line 3, column 1.
- Fixed secondary-text and graph-arrow contrast during that pass; rebuilt, verified,
  reinstalled and visually checked. All eight installed JARs matched the final zip.
- The static fixture was opened in Safe Mode; no scripts were executed or trusted.
  The safe-mode configuration notice came from the IDE. Original theme and 1183×768 window
  size were restored. This was a focused smoke pass, not exhaustive keyboard/accessibility
  testing, every error/cancellation state, or owner aesthetic acceptance.

Installation used:
`~/Library/Application Support/Google/AndroidStudio2026.1.1/plugins/wrapperguard`.
The paths below still existed on 2026-09-13 but are temporary and may disappear:

- Preview: `/var/folders/l4/mpjkp4sj575bvf9qp_lfwgrm0000gn/T/wg-ui-preview-pww143bc`.
- Previous plugin backup: `/var/folders/l4/mpjkp4sj575bvf9qp_lfwgrm0000gn/T/wg-plugin-backup-cd11h8vk/wrapperguard`.

The prior IDE session was left on Summary. Do not assume that window or installation is
still unchanged without inspecting it. Preview scripts are scanner inputs only.

## Implementation traps

- Keep the `JPanel(BorderLayout)` host in `WrapperGuardToolWindowFactory`; removing it
  can squeeze the Compose tool window to intrinsic width.
- Construct `JewelComposePanel(config = { }, content = { ... })` with named arguments to
  avoid the deprecated boolean overload. `JewelTheme.isDark` is a property.
- Use bounded constraints for `Modifier.weight`; scroll parents can make them unbounded.
  Resolve composable theme colors before entering a Canvas draw lambda.
- Keep platform-adaptive native controls. The removed HTML mockups are not implementation
  requirements; do not restore the previous UI or fake metrics to match them.
- `webviewUi.ts` embeds client JavaScript in a TypeScript template literal. After edits,
  evaluate the delivered template, extract the script and run `node --check` on it. TypeScript
  compilation alone cannot catch emitted-script escaping errors or leaked type annotations.
- Escape backslashes/backticks/interpolation carefully across shell, JavaScript template,
  and Kotlin raw-string layers. Read a file before editing; avoid blind string replacement.
- `RegExp.exec()` without the global flag repeats the same match in a loop. Non-global
  `String.replace()` replaces only the first match; Kotlin findAll/replace semantics differ.
- SVG presentation attributes cannot reliably use CSS `var()` values; use CSS stroke/fill
  classes and `context-stroke` for markers where appropriate.
- Node execFile throws for CLI exit 2: preserve and parse stdout for the blocker report.

## Remaining work

- Decide user-level Gradle init-script scope (`~/.gradle/init.gradle`, `~/.gradle/init.d/`).
  This is the unresolved product decision; do not infer permission to scan the user's home.
- Finish explicit trust-state/pre-import protection behavior. Trust-change rescanning is
  implemented, but it is not proof that every import path is intercepted before execution.
- Audit multi-module/root containment, external calls, and exclusions such as `target`,
  `dist`, `.cache`; root scanning and some exclusions already exist. Add focused coverage
  where needed rather than reimplementing the completed scanner.
- Expand the execution seal to policy/baseline/version inputs if required. Review configurable
  policy edge cases against the INFO-never-blocks contract; the current file seal does not
  by itself establish every intended security invariant.
- IntelliJ trusted-baseline approval/restore UI and an explicit policy for internally approved
  custom wrappers. Existing checksum support is not a full custom-wrapper management flow.
- Further accessibility/keyboard and error-state visual checks; address owner feedback on
  the rebuilt UI. The automated and visual checks above do not establish universal usability.
- Parser-aware PowerShell/Bash adapters, rule packs/community SDK, and a local multi-project
  console remain future work. Both IDE surfaces already have graph canvases.
- Before publishing, make regression fixtures available to a clean checkout/CI without
  including the read-only old project. Publishing remains the owner's decision.

### Research boundary — blocked

Do not implement, approximate, reproduce, reverse-engineer or train MPSAutodetect or its
follow-up. Wait for the researcher to supply the paper, model artifacts, preprocessing
specification, dataset/licensing guidance and direct integration instructions in the repo.
Do not invent weights, thresholds, datasets, or performance claims; do not add Python ML
runtimes, SdA/XGBoost/ONNX models or ML blocking now. Keep deterministic scanning as the only
active detector, with `MlScorer`/`NoopMlScorer` inactive. ML output must not affect verdicts,
policy, severity or execution blocking. Any later authorized detector starts as a separate,
local, optional PowerShell shadow signal with model version/confidence and can be disabled
without changing deterministic scanning.
