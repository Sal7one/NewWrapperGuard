# WrapperGuard

**Static security analysis for Gradle wrappers, build files, and bootstrap scripts.**
WrapperGuard detects malicious wrapper scripts, supply-chain attacks, hardcoded credentials,
dangerous commands, and unsafe repository/network configuration — **without ever executing
project code**.

- IntelliJ IDEA / Android Studio plugin — scans on project open, re-scans on file changes, and
  **hard-blocks** Gradle/shell run configurations when the policy says BLOCK.
- CLI — the same scanner for terminals, CI, and pre-commit hooks; emits a canonical,
  schema-validated JSON report.
- VS Code extension — invokes the CLI and shows findings in a tree (in progress).

> **Privacy & safety:** all analysis is local static text analysis. The scanner reads files
> only — it never spawns the project's scripts, resolves remote resources at scan time, or
> sends source code anywhere. Credential evidence is redacted at creation time and again on
> export, so secrets never leave the machine in reports or logs. There is no telemetry.

## What it protects (current scope — stated honestly)

| Covered | How |
|---|---|
| IDE Gradle/shell run configurations | A before-run task re-scans and blocks launch on BLOCK-policy findings (with a Run Once escape hatch) |
| New run configurations | Guard task auto-attached via \`createTask\` |
| Existing run configurations | Back-filled on project open |
| Wrapper/build/script file edits | VFS watcher marks the report STALE immediately; content-hash-gated debounced rescan |
| Terminal / CI usage | The CLI with \`--enforce\` exits non-zero on BLOCK findings |

**Not covered (planned):** Gradle sync/import runs (sync gate, v0.3), TOCTOU execution seal
(v0.3), trusted-vs-untrusted suppression model (v0.3). Until those land, do not treat a
never-scanned project as protected — the UI says so.

## Repository layout

\`\`\`text
wrapperguard-core/      IDE-neutral models, policy, scanner SPI, utilities
wrapperguard-rules/     deterministic rules + recursive call-chain scanner (44 stable rule IDs)
wrapperguard-cli/       CLI entry point — canonical JSON, validated against schema/
wrapperguard-intellij/  IntelliJ/Android Studio plugin — Compose tool window + run guard
vscode-extension/       VS Code extension (invokes the CLI, no scanner logic in TS)
schema/                 JSON Schema for the canonical report + sample
design_reference/       design mockups for the UI surfaces
docs/                   component and research-integration notes
\`\`\`

Architecture: one shared Kotlin scanner → canonical JSON → IntelliJ adapter, CLI, VS Code, CI.
Core code never imports IntelliJ classes; the VS Code extension never re-implements detection.

## Screenshots

*(To be added from a verified sandbox run: Overview / Findings / Execution Graph / Policy
tabs and the Execution Blocked dialog.)*

## Build

Requirements: JDK 21.

\`\`\`bash
./gradlew build                 # compile + all tests (core, rules, intellij platform tests)
./gradlew :wrapperguard-intellij:runIde      # sandbox IDE with the plugin loaded
./gradlew :wrapperguard-intellij:buildPlugin # distributable zip under build/distributions/
./gradlew :wrapperguard-intellij:verifyPlugin # binary-compat checks vs IDEA + Android Studio

./gradlew :wrapperguard-cli:installDist
wrapperguard-cli/build/install/wrapperguard-cli/bin/wrapperguard-cli --json --enforce <project>
\`\`\`

CLI exit codes: \`0\` = no BLOCK findings, \`2\` = BLOCK findings present (with \`--enforce\`).
Reports validate against \`schema/wrapperguard-report.schema.json\`.

## Verification status

See \`VALIDATION.md\` for the exact checks performed against this revision. Headless
end-to-end guard tests (malicious fixture → run blocked + dialog requested; clean fixture →
allowed; back-fill; stale→rescan) live in \`wrapperguard-intellij/src/test\`.

## Roadmap

- **v0.3** — Gradle-sync gate, TOCTOU execution seal, trusted/untrusted suppression model,
  wrapper-JAR checksum escalation, VS Code extension completion, hostile-input hardening.
- **Later** — SARIF/CI adapter, rule packs, optional research-backed PowerShell detector
  (shadow mode, never blocks by itself — see \`docs/MPSAUTODETECT.md\`).

## License

Apache-2.0 — see `LICENSE`.
