# WrapperGuard

A local vetting workspace for IntelliJ IDEA, Android Studio, VS Code and CI. Bring
your own analysis SDKs and approved scripts, run several providers concurrently, and
use one interface for findings, warnings, alerts, build blocking and Gradle sync blocking.
The original static WrapperGuard scanner remains included and enabled by default.

**Version: 0.4.0.** [Download packages and checksums](https://github.com/Sal7one/NewWrapperGaurd/releases/tag/v0.4.0).
The release build passed **159 tests** (107 JVM, 52 Node), plus scanner parity,
hostile-input and installed CLI checks. See [validation and release limits](docs/release-readiness.md).

Explore the [local UI demo](demo/README.md) and its [screenshot gallery](demo/index.html)
using a fictional project, simulated providers, and reproducible sample reports.

## What the host owns

- A small provider API shared across the Kotlin and TypeScript hosts.
- Explicit per-provider grants: Warning, Major warning, Alert, Block build, Block sync.
- Bounded concurrent execution, timeouts, isolated failures and visible provider run status.
- Host policy and trusted approvals; providers request actions but cannot grant permissions.
- One combined report, provider attribution, source navigation, execution graph and SARIF.

Third-party providers start disabled with no actions granted. Configuration lives in user
settings or an explicitly selected file outside the scanned repository. A required provider
that cannot finish can block the relevant operation when that authority was granted.
Optional failures remain visible as incomplete coverage.

```text
Built-in scanner     Organization SDK     Approved command/script adapter
       \                    |                         /
        +---------- Provider coordinator ------------+
                    grants + policy + lifecycle
                              |
          +-------------------+-------------------+
          |                   |                   |
   IntelliJ / Android      VS Code               CLI / CI
   native workspace       sidebar + SDK API      JSON / SARIF
   build + sync gates     cooperative gates      exit 0 / 1 / 2
```

The built-in detector uses static analysis of Gradle wrappers, build files and bootstrap
scripts and never executes analyzed project code. It runs in Kotlin for IntelliJ/CLI and
in TypeScript for VS Code. External SDKs and command adapters run only after user opt-in;
they are trusted programs with the user's privileges, not sandboxed code.

## Integrate a provider

Read the [provider SDK and command protocol guide](docs/providers.md). It covers the
action contract, user grants, failures, Kotlin/IntelliJ extensions, VS Code registration
and the any-language JSON subprocess adapter. A working Python adapter and user
configuration are in [examples/providers](examples/providers).

The [controlled integration SDK](docs/integrations.md) adds report adapters for SARIF,
Bandit, PSScriptAnalyzer, Trivy, OSV-Scanner and ZAP. User-owned profiles select inputs,
operations and requested actions; host grants remain authoritative. A scan plan previews
the work, and the supported Bandit runner analyzes an isolated source snapshot. ZAP
reports require explicit target scope. See [the Python SDK](sdk/python/README.md) for
installation and the distinction between report imports and tool execution.

In IntelliJ/Android Studio use **Settings → Tools → WrapperGuard**. In VS Code use the
**Policy** tab or WrapperGuard user settings. Existing detector rules and fingerprints
remain available; disabling the built-in provider does not require replacing the UI.

IntelliJ intercepts supported IDE Gradle/shell launches and Gradle resolution before
synchronization starts. VS Code integrations call the exported build/sync gate; the
extension does not claim a universal veto over terminals or arbitrary tasks. External
terminals are outside IDE interception. See the guide for cancellation and seal limits.

## Build and verify

Use JDK 21 and Node 22 or newer.

```sh
./gradlew build :wrapperguard-cli:installDist :wrapperguard-intellij:buildPlugin
./gradlew :wrapperguard-intellij:verifyPlugin

cd vscode-extension
npm ci
npm test
npm run parity
npm run hardening
npm run package
```

On a machine with an installed compatible IDE, an explicit local SDK avoids downloading
another IDE: `./gradlew '-Pwrapperguard.localIdePath=/path/to/IDE/Contents' build`.
This does not replace the separate supported-version plugin verification gate.
The default SDK/verifier target remains IntelliJ IDEA 2025.3, with minimum platform build252.

```sh
wrapperguard-cli /path/to/project --json --enforce
wrapperguard-cli /path/to/project --providers /absolute/user/providers.json --operation build --enforce --json
```

Exit0 means a complete scan without active blockers; exit2 means findings block the
operation; exit1 means invalid input or incomplete coverage without blockers. Configuration
and trusted baseline files inside the scanned repository are rejected. No SDKs or scripts
are discovered and executed from repository configuration.

## Repository

| Location | Responsibility |
|---|---|
| `wrapperguard-core/` | Report models, provider SDK/coordinator, command adapter, policy, approvals and export |
| `wrapperguard-rules/` | Original static scanner and stable detector IDs |
| `wrapperguard-cli/` | Provider-aware CI/terminal application |
| `wrapperguard-intellij/` | Native workspace, provider settings, build guard and pre-sync hook |
| `vscode-extension/` | TypeScript host/scanner, extension SDK and webview |
| `schema/` | Backward-compatible report and provider configuration contracts |
| `test-fixtures/` | Tracked static inputs for regression and parity checks |

The project is Apache-2.0. Engineering history and constraints are recorded in
[HANDOVER.md](HANDOVER.md) and [CHANGELOG.md](CHANGELOG.md).
