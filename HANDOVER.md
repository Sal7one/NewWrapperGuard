# WrapperGuard engineering handover

Updated **2026-10-20**. Current implementation: **app 0.6.0**, **Python SDK 0.3.0**,
provider **API 1**, report **schemaVersion 1**. Branch: `codex/flexible-provider-platform`,
based on `8336a61` (0.5 RC1). PR #2 remains the prerequisite release branch until merged;
the new platform change must use a stacked PR or rebase after that merge. Stable release
acceptance remains separate from candidate packaging.

## Workspace and ownership

The active checkout is `/private/tmp/wrapperguard-optional-methods`. The original repository
is `/Users/salehalanazi/Repos/NewWrapperGaurd`; the initial Documents workspace path is
blocked by macOS privacy controls. Use the real active checkout explicitly in commands.
Temporary directories and running demo processes may disappear between sessions.

Repository: `Sal7one/NewWrapperGaurd`. Keep publication status accurate: a local build,
pushed branch, merged PR and published release are separate events. The user authorized
integration, optional tools, GUI setup, documentation cleanup and removal of obsolete docs.
No public Marketplace or PyPI publication
has occurred as part of this work.

## Product and implemented behavior

WrapperGuard is a shared UI/enforcement host for interchangeable analyzers in Android
Studio, IntelliJ IDEA, VS Code and CI. Providers own analysis; the host owns grants,
policy, attribution, approvals, deadlines and aggregate reports. No particular vendor is
required. Existing custom providers remain supported and are not replaced by the catalog.

The shared `catalog/security-methods.json` contains 17 entries grouped as Built-in, SAST,
Supply chain, DAST, Research and AI. The six built-in groups default on; external/research/AI
methods default off. `ScanSettings.methodFlags` is user configuration, never repository policy.
Kotlin loads the catalog as a core resource; VS Code packages the same catalog.

Implemented surfaces:

- **Tools**: built-in switches, category filters, connection state, Connect/Disconnect,
  explicit enabling, permissions and SDK installation.
- **Research**: local script behavior signals and local AI review, with advisory status
  and visible limitations. Neither can create build/sync blockers.
- **History**: explicit opt-in summaries, same-operation/known-scope comparison, retention
  and safe clearing. Issue titles/relative paths persist; messages/evidence/source do not.
- Native **Tools** supports generic SDK/command setup, project scope, presets and owned-storage cleanup. VS Code puts **Add SDK / Storage** in **Providers** and presets in **Tools**. VS Code explicitly selects multi-root projects.
- **Summary / Findings / Execution / Policy**: existing combined reports, editor navigation,
  call graph, provider permissions, stale/incomplete state and exports.
- GUI dialogs create managed profiles/provider configuration outside all open projects;
  users browse paths rather than having to edit JSON. SDK verification does not run a scan.
- Bundled SDK installation creates a dedicated environment using trusted Python 3.10+.
  Its wheel installs offline. Bandit 1.9.4 / Semgrep 1.181.0 downloads are explicit choices.
  PowerShell/PSScriptAnalyzer, Trivy, OSV and model servers use separately installed tools.
- CLI `--methods /absolute/user/methods.json` reads known boolean method flags from an
  outside-project file. Existing `--providers`, `--baseline`, operation and JSON/SARIF
  interfaces remain available. `--check-providers` is passive; `--conformance` explicitly
  executes enabled providers for contract validation. SDK `--capabilities` is passive.

Disabling some built-in methods emits reduced-scope notices without failing a completed
configured scan. All built-ins off plus an enabled real external analysis provider disables
the built-in invocation, preventing a synthetic required-provider failure. Unknown
organization SDKs qualify as analysis providers. AI/research alone or zero detectors cannot
establish complete coverage. Actual failures, malformed input, file/depth caps and skipped
source remain incomplete; granted required-provider failures can still block.

## Integration SDK

Runtime dependency-free `sdk/python` supports ten format IDs: eight passive importers
(SARIF, Bandit, PSScriptAnalyzer, Trivy, OSV-Scanner, scoped ZAP, CycloneDX, Sonar generic issues), plus script semantics
and local AI review. Five fixed external runners execute Bandit, Semgrep, PSScriptAnalyzer,
Trivy or OSV-Scanner over bounded snapshots. The SDK never builds a project or installs
project dependencies. Direct snapshots require POSIX no-follow file access; Windows uses
report imports, plus supported local model report review.

Semgrep has five independently selected local packs: original Android/Ktor, manifest,
WebView, JVM crypto and Ktor runtime. The original six rules remain the default; an
external reviewed rule file replaces bundled selection. This supplies targeted source SAST rather than only Gradle/Maven build checks.
It is not complete app-security coverage. Trivy/OSV inspect allowlisted dependency inputs;
upstream supported/resolved versions determine coverage. Trivy may download databases;
OSV sends package metadata to its vulnerability service. SDK SCA defaults are 120 seconds,
with matching host timeout allowance in guided setup.

Script semantics uses limited Python AST def/use tracking and bounded PowerShell literal
inspection; it executes no payload and claims no model accuracy. `ai_review.py` sends
minimized metadata from at most five findings to an explicit numeric-loopback `/v1` model
server. Source/evidence/project-path fields are excluded and common credential/path forms
are redacted. Model output is untrusted plain INFO
audit evidence with no actions and original host-controlled locations. No model is bundled.

ZAP is report import only with exact origin/path scope. There is no active DAST runner,
implicit target, backend startup or runtime malware sandbox. Imported reports do not
establish freshness. See [the operational guide](docs/integrations.md),
[provider contract](docs/providers.md) and [research mapping](docs/research.md).

## Important implementation contracts

- Keep Kotlin and TypeScript scanner/coordinator behavior aligned, including fingerprints,
  method replacement, incompleteness and source traversal. Stable rule IDs are never renumbered.
- Existing deterministic fingerprints and trusted baselines remain valid. Repository ignore
  markers and vendor suppression metadata cannot create user-approved exceptions.
- Provider requests are intersected with user actions and operation policy. INFO, audit
  evidence and shadow research signals cannot block. Deterministic findings never receive
  legacy `MlScorer` annotations: that scorer is not called and cannot downgrade a blocker.
- Command providers use exact argv, bounded stdin/stdout and generic errors. They are trusted
  OS-user programs, not sandboxed by host action grants. Keep SDK/tool profiles outside projects.
- SDK child analyzers inherit the host process group. Detaching them would defeat host group
  cancellation. Local runner failures terminate/reap the known child.
- SDK snapshot paths are canonicalized before scanner invocation. Rebase absolute source
  paths and supported file URIs back to original project locations; preserve valid SARIF bases.
- Reports become stale after observed source changes. Native and VS Code gates compare
  bounded source/configuration content identities before and after provider execution.
  Declared custom inputs, managed profiles/reports/rules and actual first-party reads
  now participate in before/after content comparisons. Unknown custom scopes remain
  visibly unverified; installed program code and remote services are not fully sealed.
- Repository scanner file/depth cap notices are `WG-SCAN-LIMIT` and force incomplete coverage.
  Canonical roots and recursive discovery respect the file cap.
- No enabled detector means incomplete; an AI-only success must never become a clean scan.
- Core/rules/CLI remain IDE-neutral. UI sorting and filtering cannot modify report content.
- Native setup/state writes are user-level and must preserve existing provider configurations.
  Same-ID managed connection precedence requires an explicit choice.

## Build and validation

Use JDK 21, Node 22+, Python 3.10+ with pip. Commands and actual results live in
[docs/verification.md](docs/verification.md), the single current verification record.
Completed results include core/rules/CLI, native, Node and installed SDK regressions, real
Bandit/Semgrep, both command-provider hosts, scanner parity, hardening, actual package
contents the 21 baseline demo captures and the new platform gallery with actual IDEA and VS Code acceptance. Exact final counts, artifact checksums, binary-verifier
output and CI status are recorded in the verification record rather than duplicated here.

`buildIntegrationSdk` / `scripts/build-sdk.py` stage clean package sources and choose a
Python 3.10+ interpreter with pip. This avoids macOS system Python 3.9 producing an
`UNKNOWN-0.0.0` artifact or stale modules entering a wheel. The expected wheel is
`wrapperguard_sdk-0.3.0-py3-none-any.whl`; rebuild after any SDK source/README change.
Both GUI packages must contain the current wheel and curated rules.

Development IDE selection is explicit:
`-Pwrapperguard.localIdePath=/path/to/IDE/Contents`. Default development/verifier target
is IDEA 2025.3, minimum platform 252. Native tests use Vintage for IDE JUnit3/4 compatibility.
Close the demo IDE before packaging/verifier tasks sharing the sandbox. Preserve the native
`JPanel(BorderLayout)` / Jewel Compose host and validate emitted webview JavaScript, not
only TypeScript compilation.

## Remaining release work and roadmap

[docs/roadmap.md](docs/roadmap.md) is the prioritized backlog and milestone plan. The
current delivery includes installers, a standalone test bundle with real scanner fixtures,
manual test instructions and checksums. Local automation and binary verification are
complete when recorded in the verification checkpoint. The prior RC1 CI and new upstream/Windows-helper matrix are green. Final JVM CI must include the pipe-race correction. Actual IDEA2025.3 clean/blocked BUILD/SYNC and VS Code wizard/project/history acceptance now pass; Android Studio, installer upgrades and broader platforms remain separate. Evidence and limits belong in verification.

Do not describe a test candidate as the stable release. Report imports do not establish
freshness, source SAST is limited to selected rules, Windows direct source runners are
unsupported, and scanner action grants do not sandbox installed programs. Remaining
platform/tool matrices, accessibility,
rule/dependency coverage, provenance, active DAST and realistic research evaluation have explicit
acceptance criteria in the roadmap.

## Documentation cleanup

`docs/release-readiness.md` was removed because it described superseded 0.4.0 artifacts,
old hashes and historical validation. Git and the release preserve that history.
`docs/verification.md` now owns current results, installation/manual test steps and release gates. This handover replaces
obsolete September state, duplicate implementation narratives and superseded permission
blocks. Useful provider, SDK, fixture and demo guides remain.
