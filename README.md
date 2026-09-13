# WrapperGuard

**Static security analysis for Gradle wrappers, build files, and bootstrap scripts.**
Detects malicious wrapper scripts, supply-chain attacks, hardcoded credentials, dangerous
commands, and unsafe repository/network configuration — **without ever executing project code**.

## Surfaces

| Surface | Status | What it does |
|---|---|---|
| IntelliJ IDEA / Android Studio plugin | ✅ v0.3.0 | Scans on open, re-scans on file change, **hard-blocks** Gradle/shell run configurations *and Gradle sync* on BLOCK findings, execution seal at launch, settings UI |
| VS Code extension | ✅ v0.3.0 | Full web-app sidebar (Overview / Findings / Execution Graph / Policy) with the **entire scanner ported to TypeScript** and running in-process — no CLI, no JVM, no config |
| CLI | ✅ v0.3.0 | Same scanner for terminals and CI; canonical JSON, SARIF, trusted baselines, exit codes 0/2 |

> **Privacy & safety:** all analysis is local static text analysis. The scanner reads files
> only — it never spawns project scripts, resolves remote resources at scan time, or sends
> source anywhere. Credential evidence is redacted at creation and again on export. No
> telemetry. Apache-2.0.

## Architecture

```text
                   shared scanner (Kotlin: core + rules)
                   deterministic regex/recursive engine, 46 stable rule IDs
                                  │
          ┌───────────────────────┼───────────────────────────┐
          │                       │                           │
   IntelliJ/AS plugin        CLI (JVM)              TypeScript port (VS Code)
   before-run guard      --json --sarif           in-process, parity-verified:
   sync gate             --baseline <file>        same verdicts, blockers,
   VFS rescan, seal      exit 0/2                 and every fingerprint
                                  │
                       canonical JSON report
                       (schema/…report.schema.json)
```

## Security model

- **Never execute analyzed scripts** — analysis is pure text.
- **Repo content may tighten but never silently weaken your policy** — repo-shipped
  `wrapperguard:ignore` markers and baselines are *untrusted* until you approve the finding.
  Your approvals live **outside the repository** in VS Code global storage or an explicitly
  supplied CLI baseline. IntelliJ approval UI is planned.
- **Enforcement is policy-driven** — only actionable findings at severity ≥ ERROR block
  (ENFORCE); INFO/audit evidence never blocks. Unknown wrapper JARs escalate to blockers
  when you configure a trusted checksum list.
- **TOCTOU seal** — the pre-run scan is bracketed by content-hash seals; files changing
  mid-scan abort the launch.
- **Honest scope** — a never-scanned project is never treated as protected; the UI says so.

## Layout

```text
wrapperguard-core/      models, policy, fingerprints, redaction, SARIF, scanner SPI
wrapperguard-rules/     46 stable rule IDs + recursive call-chain scanner + baselines
wrapperguard-cli/       CLI entry point (JSON/SARIF/baseline)
wrapperguard-intellij/  IntelliJ/AS plugin — Compose tool window, run guard, sync gate, settings
vscode-extension/       VS Code extension + full TypeScript scanner port + tests
schema/                 JSON Schema + sample report
HANDOVER.md             engineering state, contracts, validation and remaining work
```

## Build & verify

```bash
./gradlew build                       # all modules + Kotlin test suites
./gradlew :wrapperguard-intellij:test # plugin guard/rescan/settings tests
./gradlew :wrapperguard-intellij:runIde
./gradlew :wrapperguard-intellij:buildPlugin    # installable zip
./gradlew :wrapperguard-intellij:verifyPlugin   # binary-compat vs IDEA 2025.3

cd vscode-extension
npm ci && npm test                    # 19 unit tests (node:test)
npm run parity                        # TS scanner vs Kotlin CLI — verdicts, counts, fingerprints
npm run hardening                     # hostile-input checks
npx @vscode/vsce package              # .vsix artifact
```

CLI: `./gradlew :wrapperguard-cli:installDist` then
`wrapperguard-cli/build/install/wrapperguard-cli/bin/wrapperguard-cli --json --enforce <project>`.

## Engineering handover

Read [HANDOVER.md](HANDOVER.md) before changing the scanner or IDE integration. It records
the rebuilt IntelliJ UI, verification evidence, security contracts, toolchain and remaining
work. The IntelliJ tool window now uses Summary / Findings / Execution / Policy tabs,
with responsive findings list/detail navigation. The VS Code interface remains separate.

## Roadmap

- Parser-aware PowerShell/Bash AST adapters, IntelliJ baseline-approval UI, rule-pack SDK.
- Optional research-backed PowerShell detector — **shadow mode only, never blocks by itself**
  (see the [research boundary](HANDOVER.md#research-boundary--blocked); deliberately
  unimplemented until the researcher provides integration guidance).

## License

Apache-2.0 — see `LICENSE`.
