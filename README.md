# WrapperGuard

A security vetting workspace for **Android Studio, IntelliJ IDEA, VS Code and CI**.
Review application source, build inputs and dependency findings together. Choose the
analyzers you need and control whether each can warn, alert, stop a build or stop Gradle sync.

**Current development version: 0.7.0 · Python integration SDK: 0.4.0 · Provider API: 1.**
The 0.7.0 candidate is undergoing final release acceptance; published 0.6.0 RC1 remains available. See [verification](docs/verification.md) for test builds, completed checks
and outstanding release work, and [the changelog](CHANGELOG.md) for published history.
Runtime source is `76312ac`; the [handover](HANDOVER.md) records the exact branches, passing
checks and remaining gates. Main CI, Android 16 emulator acceptance and all six VS Code
host jobs passed. Two newer native test runtimes failed during harness startup and must be fixed and
rerun before 0.7 publication.

## Start in the IDE

1. Open WrapperGuard's **Tools** tab. The original static checks are included; the
   **Built-in** category lets you turn each method on or off.
2. Select a connection under **SAST**, **Supply chain** or **DAST** and choose **Connect / Set up**.
   Browse to an installed analyzer or an existing report. **Install SDK…** prepares the
   bundled SDK in a dedicated environment; Bandit and Semgrep downloads are separate opt-ins.
3. Review the connection and its **Permissions**, then enable and scan. Optional methods
   start off; reviewed setup defaults to warning-only grants and SCAN-only scope.
4. Use **Research** (**Research & AI** in VS Code) for script behavior signals and local AI explanations.
   Both remain advisory and cannot stop a build or synchronization.

The [setup guide](docs/integrations.md) explains each connection, Windows direct runners and report imports,
local model setup and the difference between source analysis and dependency checks.
The [demo](demo/README.md) and [screenshot gallery](demo/index.html) use clearly labeled,
fictional data for interface review.

## Choose the methods

The shared [method catalog](catalog/security-methods.json) contains **19 independently controlled methods**:

| Category | Included connections |
|---|---|
| Built-in | Wrapper integrity, script rules, Gradle rules, version catalogs, script graph, Maven POM rules |
| SAST | Semgrep for Kotlin/Java, Bandit for Python, PSScriptAnalyzer for PowerShell, SARIF and Sonar reports |
| Supply chain | Trivy, OSV-Scanner and CycloneDX vulnerability findings |
| DAST | Scoped ZAP reports and explicit owned-target HTTP header/cookie audits |
| Research | Static Python/PowerShell signals and optional isolated Python execution |
| AI | Advisory explanations from a user-configured local model server |

Only the six built-in static groups start enabled. Optional tools, models and external
SDKs require user setup. Every connection can be disabled or replaced independently;
[organization-specific providers](docs/providers.md) use the same interface and host grants.
Application analysis is limited to the selected tools, rules and inputs: five selectable local
Semgrep packs provide targeted review checks; the original six-rule pack remains the default.

## What the host controls

- Concurrent providers, timeouts, attribution and visible failures.
- Warning, major warning, alert, build blocking and sync blocking permissions.
- Enforcement policy, approved baselines and combined JSON/SARIF reports.
- Source navigation, execution paths, method settings and connection management.
- Generic SDK/command setup, declared inputs, multi-root selection and reviewed presets.
- Opt-in scoped scan history, bounded retention and owned-data cleanup.

Provider requests are capped by user grants and policy. User configuration lives outside
scanned projects. Imported reports do not prove freshness; optional failures remain visible.
Disabling a check changes the declared scope. Disabling every detector cannot produce a
clean report, while an enabled external analyzer can replace all built-in checks.

IntelliJ/Android Studio guard supported IDE launches and Gradle synchronization. VS Code
provides cooperative build/sync gates for integrations. Arbitrary terminals and unsupported
launch paths remain outside those gates. Installed scanners are trusted programs running
with OS user privileges; action grants do not create a sandbox. See [coverage and limits](docs/integrations.md#coverage-and-trust).

## Build from source

Use **JDK 21, Node 22+, and Python 3.10+ with pip**. From the repository root:

```sh
./gradlew build :wrapperguard-cli:installDist :wrapperguard-intellij:buildPlugin
./gradlew :wrapperguard-intellij:verifyPlugin
npm --prefix vscode-extension ci
npm --prefix vscode-extension test
npm --prefix vscode-extension run parity
npm --prefix vscode-extension run hardening
npm --prefix vscode-extension run package
```

An explicitly installed development IDE can be selected with
`-Pwrapperguard.localIdePath=/path/to/IDE/Contents`. Release compilation defaults to
IntelliJ IDEA **2025.2** (platform 252); the default verifier targets 2025.3. The host
matrix separately checks 2025.2, 2026.2.2 and 2026.2.3, including actual Jewel factory calls.
[Verification instructions](docs/verification.md) cover SDK packaging, installed-host
checks, real analyzer smoke tests and artifact inspection.

For CI or a terminal:

```sh
wrapperguard-cli /absolute/project --json --enforce
wrapperguard-cli /absolute/project --providers /absolute/user/providers.json --operation build --enforce --json
wrapperguard-cli /absolute/project --methods /absolute/user/methods.json --json
```

Exit codes: **0** complete without blockers, **2** blocked, **1** invalid or incomplete
without blockers. Provider, method and baseline configuration must be explicitly selected
user files outside the project.

## Repository guide

| Location | Responsibility |
|---|---|
| `catalog/` | Shared method identities and descriptive metadata |
| `wrapperguard-core/` | Models, policy, provider coordinator, grants and exports |
| `wrapperguard-rules/` | Static rules and bounded script traversal |
| `wrapperguard-cli/` | Terminal/CI host |
| `wrapperguard-intellij/` | Native IDE workspace, guided setup and execution gates |
| `vscode-extension/` | TypeScript host, sidebar and integration API |
| `sdk/python/` | Controlled scanner runners, report adapters, research signals and local AI review |
| `schema/` | Report, provider and integration profile contracts |
| `test-fixtures/` | Tracked inert regression inputs |

[Product and OSS references](docs/product-inspiration.md) · [Provider API](docs/providers.md) · [Research rationale](docs/research.md) ·
[Engineering handover](HANDOVER.md) · [Roadmap and remaining work](docs/roadmap.md) · [Apache-2.0 license](LICENSE)

[0.7 real-host and Android evidence](demo/screenshots/platform-0.7/README.md) ·
[Earlier 0.6 platform screenshots](demo/screenshots/platform-0.6/README.md)
