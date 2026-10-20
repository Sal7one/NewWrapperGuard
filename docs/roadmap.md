# Remaining work and roadmap

Checkpoint: **2026-10-20**, development **0.6.0**, SDK **0.3.0**, provider **API 1**.
Milestones express engineering order, not promised dates. [Verification](verification.md)
records actual test evidence; [handover](../HANDOVER.md) records operating contracts.

## Delivered in this iteration

- Generic SDK/command setup in both hosts: literal argv, extensible categories, tags,
  declared inputs, edit/remove and independent permissions. Existing API 1 and compiled
  Kotlin descriptor ABI remain compatible.
- Project-bound commands/reports/model inputs and explicit VS Code multi-root selection.
- Bounded before/after content seals for declared/configuration inputs and actual built-in
  file reads, including unusual recursively reached files; visibly unknown custom scope.
- Seventeen catalog methods, five reviewed flag-only presets, eight passive import formats
  plus two advisory outputs. New CycloneDX and Sonar adapters preserve host approval authority.
- Five independently selected Semgrep packs: original Android/Ktor, manifest, WebView,
  JVM crypto and Ktor runtime; real positive/negative source fixtures.
- Opt-in project/operation/scope history, conservative deltas, retention and owned-data cleanup.
- Passive capability discovery and CLI provider configuration/conformance tools.
- Real PowerShell/PSScriptAnalyzer and OSV smoke checks, alongside Bandit/Semgrep.
  Real Trivy and experimental Windows handle/pipe CI also pass.
  Real local-model schema interoperability; these are integration tests, not accuracy scores.
- Product/OSS decisions and source links in [product-inspiration.md](product-inspiration.md).

The previous 0.5 RC1's complete GitHub CI run is green:
[39416921098](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39416921098).
That result does not substitute for CI on the new source. Existing RC1 artifacts remain
available and are not relabeled as the newer version.

## Next release gates

| Priority | Task | Acceptance evidence |
|---|---|---|
| P0 | Pass CI on the new candidate commit | JVM/VS Code/Python matrices, real upstream tools and experimental Windows helper jobs green; archive run URL |
| P0 | Finish Android Studio and installer/upgrade acceptance | IDEA2025.3 actual clean/blocked BUILD and SYNC, and VS Code wizard/execution/multi-root/history passed; exercise Android Studio and fresh install/upgrade/removal paths |
| P0 | Review/merge release source and publish stable assets | Approved commit, installer hashes, upgrade notes and stable release metadata agree |
| P1 | Broaden supported-host matrix | Minimum/current IDEA/Android Studio and VS Code; macOS/Linux/Windows import and integration workflows |
| P1 | Finish accessibility acceptance | Keyboard and screen reader, high contrast/light/dark/narrow UI, focus recovery and all setup/error states |
| P1 | Run realistic latency/memory budgets | Large Android multi-module/Ktor/mixed-language repositories; cancellation/caps remain visibly incomplete |

## Reliability and coverage — 0.6.x

| Priority | Task | Done when |
|---|---|---|
| P1 | Report provenance/freshness envelope | Producer version/database age, generation time/source identity/scope shown; missing identity remains unverified. A project binding or history comparison alone must not claim current coverage |
| P1 | Dependency coverage detail | Tested resolved Gradle/Maven and lockfile inputs, unresolved transitives and database age visible. No build/resolution execution without its own explicit option |
| P1 | Rule-pack review and false-positive evaluation | Licensed realistic Android/Ktor projects, positive/negative cases per rule, release-manifest context and reviewed precision/coverage limits |
| P2 | Connection upgrade/repair workflow | Reproducible tool checks/upgrades, SDK repair/removal and offline-cache recovery; preserve referenced/user-owned files |
| P2 | Enable safe Windows direct runners | Handle/pipe CI now passes Windows Python 3.10/3.12/3.14. Complete host job/process-tree cancellation, sanitized runtime environment and end-to-end scanner acceptance before wiring. Report import stays available |
| P2 | Broader binary compatibility | Minimum/current verifier matrix and migration of deprecated/experimental IDE APIs where supported |

## Interoperability and automation — 0.7

| Priority | Task | Done when |
|---|---|---|
| P1 | Expand third-party conformance suite | Installable samples exercise attribution, malformed/partial responses, deadlines, cancellation and per-operation action clipping across both hosts |
| P2 | Opt-in debounced/scheduled scans | Explicit per-provider cost/network settings, cancellation and stale handling; enablement/save never silently launches tools |
| P2 | Optional CI/build recipes | Reviewed GitHub/Gradle/Maven/PowerShell/Python integrations use the same CLI/API and documented exit codes; no repository-owned execution grants |
| P2 | Optional active DAST | Reviewed target ownership/environment, explicit authentication/secrets, exact origin/path scope, redirect containment, request/time budgets and stop/cancel acceptance. No target guessing or backend startup; retain passive import separately |
| P2 | Stronger process isolation | OS-specific optional sandbox adapters tested independently of host warning/build/sync permissions; scanner provenance and limitations visible |
| P2 | Organization preset distribution | Versioned signed/reviewable configurations with independent removal/override; a shared preset cannot grant authority or silently execute |

## Research and AI — 0.8

| Priority | Task | Done when |
|---|---|---|
| P1 | Realistic evaluation corpus | Licensed real benign/malicious projects, reproducible acquisition, temporal splits, contamination/imbalance and limitations documented; synthetic fixtures remain regressions |
| P2 | Measure script semantics | Precision/recall and false-positive/negative analysis for aliases/scopes/obfuscation/dynamic and cross-file construction before expanding authority |
| P2 | Optional algorithm/model providers | Separate stable IDs/config/provenance/removal; heuristic scores cannot downgrade deterministic blockers |
| P2 | Evaluate local AI utility/abuse resistance | Real models measured for explanation quality, hallucination, prompt injection, latency and minimization. Retain host locations and advisory ceiling |
| P2 | Isolated dynamic deobfuscation service | Explicit containment/resource/network controls and evaluation; no untrusted payload runs inside the IDE or ordinary scanner process |

## 1.0 distribution gates

Supported host/OS workflows run continuously. Enforcement/freshness/cancellation/failure
boundaries have adversarial tests and clear scope. Realistic coverage measurements replace
universal security claims. Install/upgrade/uninstall, signing where supported, reproducible
checksums, dependency/license review and vulnerability reporting support the chosen channels.
Marketplace and PyPI publication remain separate, uncompleted distribution work.
