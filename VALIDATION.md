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
- [ ] Visual pass on the latest UI batch — **human step, in progress** (tabs, chips, banners,
      Policy redesign; run \`./gradlew :wrapperguard-intellij:runIde\`).
- [ ] Plugin Verifier vs IntelliJ IDEA 2025.3 + Android Studio 2025.3.4.7 — running.
- [ ] Real Android Studio install test (install the zip from the release page into AS).

## Known gaps (deliberate, tracked in BACKLOG.md)
- Gradle-sync gate, TOCTOU execution seal, trusted/untrusted suppression model,
  wrapper-JAR checksum escalation, VS Code extension feature completion → v0.3.
