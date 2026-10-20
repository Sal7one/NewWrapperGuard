# Roadmap and release gates

Checkpoint: **2026-10-20**, app **0.7.0**, SDK **0.4.0**, provider **API 1 / schema 1**. Implementation and acceptance are tracked separately. A completed configured scan is never a promise of complete application security. [Verification](verification.md) records evidence; [handover](../HANDOVER.md) describes operating contracts.

## Implemented and tested

| Area | Delivered behavior | Evidence |
|---|---|---|
| Pluggable host | Original scanner plus arbitrary SDK/command providers; literal argv, categories/tags, project/declared scope, independent warn/alert/build/sync grants | Shared JVM/TS conformance adapter, attribution/action clipping, malformed/partial/timeout/cancel tests |
| Optional methods |19 catalog entries, six built-in groups, SAST/SCA/DAST/research/AI off-by-default external methods; five flag-only presets | Catalog/schema checks and both actual IDE setup flows |
| Freshness | Bounded actual/declaration input seals, producer/input provenance, report age, visibly unknown scope and resolved-dependency inventory | Mutation/limits/provenance/coverage regressions; no implicit Gradle/Maven resolution |
| Maintenance | Offline SDK install/repair, selected tool update, cache and unused generated environment removal | Actual SDK 0.4 install/repair in VS Code and Android Studio; active/user/legacy preservation tests |
| Automation | Reviewed per-provider network/cost, debounced/change/interval SCAN, explicit session Start/Pause, cancellation and no save/start catch-up execution | Actual Studio0 requests through save/start, later change2 owned GETs, Pause held2; host regressions |
| Organization presets | Issuer/version, canonical SHA-256, reviewed import/export and flags only | Cross-language fixture and actual VS Code file dialogs; digest is not issuer authentication |
| Windows | Five direct scanner integrations, handle snapshots, bounded pipes and descendant job cleanup | Actual Windows scanners plus Python 3.10/3.12/3.14 CI |
| Isolation | Optional local immutable-image container backend for Bandit/Semgrep; selected-entry Python research, no host fallback | Real Linux Docker adversarial timer/worker/network/resource/privilege/cleanup CI |
| HTTP audit | Explicit owned numeric-IP/path GET header/cookie checks, request/time limits and auth-variable names | Actual both-IDE setup/cancel/no-request tests and Android 16 emulator secure/weak endpoints |
| Integrations | Shared provider conformance and reviewed Python/PowerShell/Gradle/Maven/GitHub CLI recipes | Installed JVM and real VS Code host acceptance; recipes never grant execution authority |
| Research | Pinned77-source static corpus, limitations/misses, two real local models and reproducible metadata-only results | [Research record](../research/README.md); weak signal coverage and unreliable model prose explicitly retained |
| Resources | Real-project source subsets, generated4096 files, byte/file/depth caps and cancellation driver | Repeatable p50/p95/RSS JSON and resource CI; incomplete results never counted clean |
| Distribution preparation | Runtime SBOM/license/known-advisory checks, vulnerability reporting, reproducible wheel byte identity and checksummed installer bundle | Distribution audit and packaging validation |

## Acceptance still required before a stable1.0 claim

| Gate | Current status | Completion criterion |
|---|---|---|
| Final candidate source | Final installer/source freeze and full exact-commit CI underway | JVM, Python, Node, real tools, host matrix, resource, corpus, rule review, device and package checks all pass on recorded source |
| Minimum/current IntelliJ | Matrix configured; initial hosted runner disk exhaustion fixed | Actual verifier reports for 2025.2 and 2026.2.3 pass; retain deprecated/experimental API findings |
| Device scope | Android 16/API 36 emulator passed; no physical phone attached | Emulator evidence is recorded; physical acceptance requires a connected authorized device and the same driver |
| Accessibility | Real keyboard, AX tree, four themes, narrow layout, focus/cancel/error checks passed | A human screen-reader session and independent usability review remain unperformed |
| Public channels/signing | Reviewable local/GitHub artifacts supported; Marketplace/PyPI credentials and publisher signing not configured | Account/channel setup, publisher identity, signing where supported and real install/update validation for chosen channels |
| Stronger research claims | Diagnostic evaluation complete; production-quality AI explanation and broad detection claims not met | Independently sourced/family-aware data, contextual review, calibrated prospective results and robust quality before stronger authority/marketing claims |

No implementation feature depends on a particular scanner, model, vendor, language or organization. Adapters can be enabled, disabled, replaced or removed independently. Network/cost declarations, producer manifests and preset hashes do not substitute for OS isolation or authenticated provenance.
