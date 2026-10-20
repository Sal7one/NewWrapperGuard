# Roadmap and release gates

Checkpoint: **2026-10-20**, runtime source **`76312ac`**, app **0.7.0**, SDK **0.4.0**, provider **API 1 / schema 1**. Implementation and acceptance are tracked separately. A completed configured scan is never a promise of complete application security. [Verification](verification.md) records evidence; [handover](../HANDOVER.md) describes operating contracts.

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
| Distribution preparation | Runtime SBOM/license/known-advisory checks, vulnerability reporting, reproducible wheel byte identity and bundle tooling | Audit/packaging regressions pass; published 0.6 bundle exists. Final 0.7 artifacts, audit and bundle remain pending |

## Finish the 0.7 release candidate

The feature work is delivered; newer native-runtime failures and the final installer/publication gates below remain. [Handover](../HANDOVER.md#resume-in-this-order) gives the ordered continuation procedure and local paths. Do not treat this table as a live CI dashboard; query the linked runs before resuming.

| Gate | Status at handover | Completion criterion |
|---|---|---|
| Exact-source CI | `76312ac`: Windows/scanners, Docker, corpus, rule review, resources and Android passed; main [CI](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39459625691) passed | Full final-source JVM/Python/Node/conformance/parity/audit jobs pass; preserve exact source identities |
| Supported hosts | All six VS Code jobs / 48 cases and minimum native runtime passed. All three binary verifiers passed, but actual runtime tasks for 2026.2.2 and 2026.2.3 failed during JUnit/ServiceLoader startup before cases ran in the [host matrix](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39459625955) | Compatible minimum-built binary and six real Jewel factory runtime tests each on 2025.2, 2026.2.2 and 2026.2.3; retain usage warnings |
| Device acceptance | Android 16/API 36 emulator passed in [39459625818](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39459625818); actual clean screenshot inspected | Done for this emulator/owned HTTP fixture. Keep APK/source/image hashes and limits; this is not a mobile IDE plugin or physical-phone test |
| Final install / audit / bundle | Earlier actual Studio and VS Code acceptance passed; final native ZIP and refreshed README-bearing VSIX still need final install checks and matching audit | Use the CI minimum-built ZIP, rebuild final packages, rerun closure/license/SBOM audit, and package clean committed source into a new checksummed bundle |
| Merge / candidate publication | PR #2 merged; [PR #3](https://github.com/Sal7one/NewWrapperGaurd/pull/3) open against master. No 0.7 release exists | Consolidate handover docs, pass exact final checks, merge through normal GitHub flow, then publish and verify `v0.7.0-rc.1` assets with test instructions |

## Further roadmap and external acceptance

| Area | Current status | Next evidence / work |
|---|---|---|
| Accessibility and usability | Actual keyboard, AX tree, four themes, narrow layout, focus/cancel/error checks passed | Human screen-reader session and independent usability review; fix issues they reveal |
| Physical device | No phone attached; emulator result is recorded | When hardware is available, run the same owned-fixture driver against an explicitly selected authorized device and retain separate evidence |
| Public channels and signing | GitHub/manual installer path supported; Marketplace/PyPI credentials and publisher signing not configured | Choose channels, configure publisher identity/credentials/signing where supported, and validate real install/update behavior |
| Detection quality | Targeted deterministic rules and integration checks; known missed behavior and limited source scope recorded | More independent, family-aware and prospective datasets; contextual review; expand rules only with positive/negative evidence |
| AI explanation quality | Two small local models produced structured output but unreliable/injection-contaminated prose | Human-reviewed evaluation and stronger quality controls before any production-quality explanation claim; retain advisory/off-by-default authority |
| Stable 1.0 | No stable 1.0 claim | Close the chosen release scope and independent acceptance above, document supported hosts and known limits, and verify release/update channels |

No implementation feature depends on a particular scanner, model, vendor, language or organization. Adapters can be enabled, disabled, replaced or removed independently. Network/cost declarations, producer manifests and preset hashes do not substitute for OS isolation or authenticated provenance.
