# Validation Notes — v0.3.0

Validation history. The latest entry records checks on the unreleased working tree;
the older sections record release-era checks.

## Unreleased IntelliJ UI rebuild — 2026-09-08
- `./gradlew build :wrapperguard-intellij:verifyPlugin`: passed on the final implementation.
  Plugin Verifier: Compatible with IU-253.28294.334 (4 deprecated / 21 experimental API usages).
- Sandbox launched through `runIde` with the static preview fixture; its log confirms
  `Loaded custom plugins: WrapperGuard (0.3.0)`. The IDE also logged a platform
  LoadingState/custom-certificate initialization warning; no WrapperGuard stack trace
  appeared in the inspected startup log. This establishes plugin loading, not visual QA.
- IntelliJ suite: 13/13 (3 guard, 4 rescan/lifecycle, 6 presentation).
  New coverage: asynchronous busy/completion notifications, stale verdicts, active-only
  scopes, numeric line sorting, priority ordering, multi-term search, and rootless cycles.
- `npm test`: 19/19; `npm run hardening`: 5/5.
- `npm run parity`: zero differences for `old_project/test-samples` and the repository root.
  `/tmp/wg-demo` was missing and skipped; three-fixture parity was not reverified.
- Live visual smoke pass completed in Android Studio Quail 1 | 2026.1.1 Patch 2 after
  computer-use permissions were granted. Checked wide split view and narrow list/detail
  layouts, built-in Light and the owner's [Legacy] Gerry Violet theme, Summary-to-Findings
  navigation, search, sorting with selection retained, graph selection and finding links,
  Policy, and opening the real settings dialog. Open in editor landed at the finding's
  correct file and line (post-install.sh, 3:1).
- Fixed unreadable secondary text under the custom dark theme and faint normal graph
  arrows in Light; rebuilt, verifier-checked, reinstalled and visually checked the fixes.
  Restored the original legacy theme and 1183×768 window size afterward.
- The fixture was opened in Safe Mode without trusting or executing its scripts. The IDE's
  safe-mode configuration warning is a platform notice. This is a focused smoke pass,
  not an exhaustive accessibility audit or visual coverage of every scan/error state.
- Static UI preview fixture: `/var/folders/l4/mpjkp4sj575bvf9qp_lfwgrm0000gn/T/wg-ui-preview-pww143bc`.
  Its scripts are scanner inputs only and must not be executed.
- The final rebuilt 0.3.0 zip is installed in AndroidStudio2026.1.1/plugins/wrapperguard.
  Previous plugin backup: `/var/folders/l4/mpjkp4sj575bvf9qp_lfwgrm0000gn/T/wg-plugin-backup-cd11h8vk/wrapperguard`.

## Build
- [x] \`rm -rf build dirs\` + full \`./gradlew clean build\` — **green** (core, rules, cli, intellij).
- [x] \`:wrapperguard-intellij:buildPlugin\` — produces
      \`wrapperguard-intellij/build/distributions/wrapperguard-intellij-0.2.0-SNAPSHOT.zip\` (~2.7 MB).
- [x] VS Code extension: \`npm ci && npm run compile\` — **green** (tsc, 0 errors).
- [x] **Node test suite**: 19/19 passing (\`npm test\`, node:test — redaction, fingerprints,
      policy, scanner integration).
- [x] **Scanner parity** (TS in-process port vs Kotlin CLI) on \`/tmp/wg-demo\`,
      \`old_project/test-samples\`, and the repo root: identical verdicts, finding counts,
      blockers, edges, per-rule counts, and **zero fingerprint differences** both ways
      (\`npm run parity\`).
- [x] **Hardening checks**: 5/5 (\`npm run hardening\`).
- [x] **Artifacts**: \`wrapperguard-intellij-0.3.0.zip\` + \`wrapperguard-0.3.0.vsix\`.

## Tests
- [x] \`:wrapperguard-core:test\` — \`CredentialRedactionTest\` (9 tests) green.
- [x] \`:wrapperguard-rules:test\` — \`RepositoryScannerTest\` regression suite green.
- [x] \`:wrapperguard-intellij:test\` — \`GuardSmokeTest\` (3) + \`FileChangeRescanTest\` (2) green:
  - malicious fixture → BLOCK findings, run refused, execution-blocked dialog requested, report published;
  - clean fixture → run allowed, no dialog;
  - back-fill adds an enabled guard task to pre-existing run configurations;
  - watched-file change → immediate STALE, debounced rescan publishes a fresh report;
  - unchanged content (VFS noise) → freshness restored **without** a rescan (content-hash gating).

## CLI end-to-end
- [x] Scanned \`old_project/test-samples\` (49 findings; enforce → EXECUTION_BLOCKED, exit 2).
- [x] Scanned this repository root (52 findings; REVIEW_REQUIRED, exit 0).
- [x] Clean fixture → CLEAN, exit 0. Malicious fixture (pipe-to-shell + hardcoded credential) →
      EXECUTION_BLOCKED, exit 2.
- [x] **All five JSON outputs validate against \`schema/wrapperguard-report.schema.json\`**
      (python jsonschema, draft 2020-12).
- [x] Redaction end-to-end: no raw secret (\`hunter2\`) in any JSON output; evidence carries
      \`[redacted]\` and \`"redacted": true\` for all credential findings (3/3 in samples).

## IDE
- [x] Sandbox IDE (IntelliJ 2025.3 IU) launched with the plugin; log confirms
      \`Loaded custom plugins: WrapperGuard (0.2.0-SNAPSHOT)\` and the demo project opened.
- [x] Rebuilt IntelliJ UI visual smoke pass — see the unreleased entry above for scope.
- [x] Plugin Verifier vs IntelliJ IDEA 2025.3 (IU-253.28294.334) — **Compatible**; 4 deprecated
      + 7 experimental API usages, no incompatibilities. (Android Studio is not verifier-checked:
      IPGP 2.11 mangles the AS installer URL for 2025.3.x.)
- [x] Real Android Studio install test — zip installed into
      \`~/Library/Application Support/Google/AndroidStudio2026.1.1/plugins/wrapperguard/\`
      (AS 2026.1.1 Patch 2, build 261 ≥ sinceBuild 252). Launched and exercised the rebuilt UI.

## Release state
- Local commit \`7b07538\` + tag \`v0.2.0\`. Per owner decision **no GitHub push, release,
  or Marketplace submission yet** — repo stays private/local until the v0.3 guard items land.
- License: Apache-2.0. \`old_project/\` is git-ignored (kept locally as the migration reference).

## v0.3 items completed (this revision)
- Gradle-sync gate: sync launches are guarded via ExternalSystemRunConfiguration; post-import ENFORCE re-validation.
- TOCTOU execution seal: save-all-documents + before/after content-hash seals with retry; WG-SCAN-TOCTOU blocker.
- Trusted/untrusted suppression: repo-shipped wrapperguard:ignore is inert; only external user-approved baselines suppress; suppress auto-baselines.
- Wrapper-JAR escalation: WG-WRAPPER-007 ERROR when a trusted checksum list is configured.
- Hostile-input hardening: UTF-16/BOM, null bytes, line caps, depth/file caps, symlink safety (5+5 tests).
- VS Code: graph canvas, settings-driven policy (allowedHosts/blockedCommands/checksums), SARIF export; CLI --sarif.
- IntelliJ settings UI (Settings → Tools → WrapperGuard): hosts, checksum requirement, scan toggles.

## Known gaps (deliberate, tracked in BACKLOG.md)
- IntelliJ Trusted-Project trust-state input, parser-aware PowerShell/Bash AST adapters,
  IntelliJ baseline-approval UI, rule-pack SDK.
