# Roadmap and release gates

**Master/RC2 checkpoint: 2026-10-20.** The user resumed work to merge all PRs and create a release. PRs #3 and #4 are merged into **9805619**; no open PRs remained at that checkpoint. The broader partial roadmap stays paused. App **0.7.0**, SDK **0.4.0**, provider **API 1 / schema 1**. [RC2 release metadata](https://github.com/Sal7one/NewWrapperGaurd/releases/tag/v0.7.0-rc.2) records actual package/build/doc identities and test results. [Verification](verification.md) gives tests; [handover](../HANDOVER.md) gives restart context.

## Implemented and tested

| Area | Delivered behavior | Evidence |
|---|---|---|
| Pluggable host | Original scanner plus arbitrary SDK/command providers; literal argv, categories/tags, project/declared scope, independent warn/alert/build/sync grants | Shared JVM/TS conformance adapter, attribution/action clipping, malformed/partial/timeout/cancel tests |
| Optional methods | 19 catalog entries, six built-in groups, SAST/SCA/DAST/research/AI off-by-default external methods; five flag-only presets | Catalog/schema checks and both actual IDE setup flows |
| Freshness | Bounded actual/declaration input seals, producer/input provenance, report age, visibly unknown scope and resolved-dependency inventory | Mutation/limits/provenance/coverage regressions; no implicit Gradle/Maven resolution |
| Maintenance | Offline SDK install/repair, selected tool update, cache and unused generated environment removal | Actual SDK 0.4 install/repair in VS Code and Android Studio; active/user/legacy preservation tests |
| Automation | Reviewed per-provider network/cost, debounced/change/interval SCAN, explicit session Start/Pause, cancellation and no save/start catch-up execution | Actual Studio: zero requests through save/start, later change: two owned GETs; Pause held at two; host regressions |
| Organization presets | Issuer/version, canonical SHA-256, reviewed import/export and flags only | Cross-language fixture and actual VS Code file dialogs; digest is not issuer authentication |
| Windows | Five direct scanner integrations, handle snapshots, bounded pipes and descendant job cleanup | Actual Windows scanners plus Python 3.10/3.12/3.14 CI |
| Isolation | Optional local immutable-image container backend for Bandit/Semgrep; selected-entry Python research, no host fallback | Real Linux Docker adversarial timer/worker/network/resource/privilege/cleanup CI |
| HTTP audit | Explicit owned numeric-IP/path GET header/cookie checks, request/time limits and auth-variable names | Actual both-IDE setup/cancel/no-request tests and Android 16 emulator secure/weak endpoints |
| Integrations | Shared provider conformance and reviewed Python/PowerShell/Gradle/Maven/GitHub CLI recipes | Installed JVM and real VS Code host acceptance; recipes never grant execution authority |
| Research | Pinned 77-source static corpus, limitations/misses, two real local models and reproducible metadata-only results | [Research record](../research/README.md); weak signal coverage and unreliable model prose explicitly retained |
| Resources | Real-project source subsets, generated 4096 files, byte/file/depth caps and cancellation driver | Repeatable p50/p95/RSS JSON and resource CI; incomplete results never counted clean |
| Distribution preparation | Runtime SBOM/license/known-advisory checks, vulnerability reporting, reproducible wheel byte identity and bundle tooling | ce1 main CI, pinned wheel, hidden upload/download roundtrip, 348 checksum records and standalone macOS/Linux gate checks passed |

## Release scope and merged source

RC2 uses the merged master build and current documentation with explicit source-equivalence/build provenance; RC1 retains its original audited ce1 CI installer bytes. Minimum IDEA 2025.2 runtime, actual Studio 261 startup/Tools/paused automation, six VS Code hosts and the owned Android emulator fixture are accepted within their recorded scope. No failed test is waived or relabeled.

The newer IDEA 262 runtime follow-up is deferred unless its in-flight result is already recorded in release metadata. At ce1 the binary verifiers passed, while unrelated Ultimate project post-startup initialization prevented two test bodies. The d611 application-only fixture preserves those assertions and all heavy guard tests; local six-case preflight passed. Full newer-host lifecycle acceptance is not claimed. PRs #3 and #4 are merged; publication and actual supported-host evidence remain separate records.

## Deferred roadmap — resume only when requested

| Area | Current status | Next evidence / work |
|---|---|---|
| Accessibility and usability | Actual keyboard, AX tree, four themes, narrow layout, focus/cancel/error checks passed | Human screen-reader session and independent usability review; fix issues they reveal |
| Physical device | Emulator result recorded; no physical-device acceptance recorded | When hardware is available, run the same owned-fixture driver against an explicitly selected authorized device and retain separate evidence |
| Public channels and signing | GitHub/manual installer path supported; Marketplace/PyPI credentials and publisher signing not configured | Choose channels, configure publisher identity/credentials/signing where supported, and validate real install/update behavior |
| Detection quality | Targeted deterministic rules and integration checks; known missed behavior and limited source scope recorded | More independent, family-aware and prospective datasets; contextual review; expand rules only with positive/negative evidence |
| AI explanation quality | Two small local models produced structured output but unreliable/injection-contaminated prose | Human-reviewed evaluation and stronger quality controls before any production-quality explanation claim; retain advisory/off-by-default authority |
| Stable 1.0 | No stable 1.0 claim | Close the chosen release scope and independent acceptance above, document supported hosts and known limits, and verify release/update channels |

No implementation feature depends on a particular scanner, model, vendor, language or organization. Adapters can be enabled, disabled, replaced or removed independently. Network/cost declarations, producer manifests and preset hashes do not substitute for OS isolation or authenticated provenance.
