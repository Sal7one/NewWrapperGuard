# Validation Notes — v0.2.0

Checks actually run against this revision (all commands on a clean checkout, JDK 21).

## Build
- [x] \`rm -rf build dirs\` + full \`./gradlew clean build\` — **green** (core, rules, cli, intellij).
- [x] \`:wrapperguard-intellij:buildPlugin\` — produces
      \`wrapperguard-intellij/build/distributions/wrapperguard-intellij-0.2.0-SNAPSHOT.zip\` (~2.7 MB).
- [x] VS Code extension: \`npm ci && npm run compile\` — **green** (tsc, 0 errors).

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
- [ ] Visual pass on the latest UI batch — **human step** (tabs, chips, banners, Policy
      redesign; run \`./gradlew :wrapperguard-intellij:runIde\` — sandbox IDE was launched
      with the demo project at /tmp/wg-demo for this pass).
- [x] Plugin Verifier vs IntelliJ IDEA 2025.3 (IU-253.28294.334) — **Compatible**; 4 deprecated
      + 7 experimental API usages, no incompatibilities. (Android Studio is not verifier-checked:
      IPGP 2.11 mangles the AS installer URL for 2025.3.x.)
- [ ] Real Android Studio install test — zip installed into
      \`~/Library/Application Support/Google/AndroidStudio2026.1.1/plugins/wrapperguard/\`
      (AS 2026.1, build 261 ≥ sinceBuild 252). **Awaiting AS launch to confirm it loads.**

## Release state
- Local commit \`7b07538\` + tag \`v0.2.0\`. Per owner decision **no GitHub push, release,
  or Marketplace submission yet** — repo stays private/local until the v0.3 guard items land.
- License: Apache-2.0. \`old_project/\` is git-ignored (kept locally as the migration reference).

## Known gaps (deliberate, tracked in BACKLOG.md)
- Gradle-sync gate, TOCTOU execution seal, trusted/untrusted suppression model,
  wrapper-JAR checksum escalation, VS Code extension feature completion → v0.3.
