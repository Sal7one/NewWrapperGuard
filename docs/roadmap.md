# Roadmap and release gates

Evidence checkpoint: **a97dcd1 / 2026-10-20**, app **0.7.0**, SDK **0.4.0**, provider **API 1 / schema 1**. Candidate: **`v0.7.0-rc.1`**. [Current PR checks](https://github.com/Sal7one/NewWrapperGaurd/pull/3/checks) own live status; canonical release metadata and `SOURCE-COMMIT.txt` identify a publication. A completed configured scan does not establish complete application security. [Verification](verification.md) records tests; [handover](../HANDOVER.md) gives the continuation order.

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
| Distribution preparation | Runtime SBOM/license/known-advisory checks, vulnerability reporting, reproducible wheel byte identity and bundle tooling | a97 main CI and nested-bundle review passed; final-source backend pin and upload/download checksum gates require a fresh run |

## Finish the 0.7 release candidate

The compatibility follow-up in this source changes production entry-point code and the test harness. Source-matched green matrix results and released evidence determine closure; the table preserves archived pre-fix a97 outcomes. The [cached 253 preflight](../demo/screenshots/platform-0.7/intellij-verifier/local-253-ipgp2181/README.md) passed six cases without failures/errors/skips, but does not prove minimum 252 or 262 acceptance. No failed assertion is waived.

| Gate | Archived pre-fix a97 result | Acceptance criterion |
| --- | --- | --- |
| Native supported hosts | All binary verifiers compatible; minimum 2025.2 passed 6/6 actual cases. Both 262 targets passed 5/6, failing enabled-plugin registration; [raw reports](../demo/screenshots/platform-0.7/intellij-verifier/a97dcd1/README.md) | Validate IPGP 2.18.1 product-info/module-alias classpath fix, exact framework assertions and Java factory default inheritance. Require 6/6 cases, zero failures/errors/skips on 2025.2/2026.2.2/2026.2.3 and retain verifier reports |
| Exact final-source CI | [a97 main CI](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39480825937), scanners/Windows, Docker, corpus, source-rule review, resources and Android passed; all six VS Code jobs / 48 cases passed | Required workflows pass on the final release source; do not reuse canceled, different-source or binary-only results |
| Installer/device acceptance | [Actual Studio a97 ZIP](../demo/screenshots/platform-0.7/android-studio/minimum-a97dcd1/evidence.json) and [Android 16 emulator](../demo/screenshots/platform-0.7/android-device/a97dcd1/README.md) passed; actual PNG and APK hash reviewed | Keep exact package/source/image hashes. Reinstall/smoke changed production binaries. Emulator/owned HTTP target evidence does not establish physical-phone acceptance |
| Distribution and delivery | a97 nested bundle passed 288 checksums / 28 Markdown destinations and exact audited-CLI bytes. Expanded artifact omitted one hidden workflow; SDK generator metadata drifted | Verify the setuptools 84.2.1 pin, included hidden workflow and uploaded/downloaded manifests; match all four audited packages and standalone gates to the final clean source |
| Merge/publication | [PR #3 checks](https://github.com/Sal7one/NewWrapperGaurd/pull/3/checks) identify the current head and review state | Check the canonical 0.7 tag/release first and reuse an existing verified publication. Otherwise merge after required checks, require final merged-source CI, then publish/verify candidate assets, checksums, source and instructions. GitHub publication does not imply Marketplace/PyPI/signing |

## Further roadmap and external acceptance

| Area | Current status | Next evidence / work |
|---|---|---|
| Accessibility and usability | Actual keyboard, AX tree, four themes, narrow layout, focus/cancel/error checks passed | Human screen-reader session and independent usability review; fix issues they reveal |
| Physical device | Emulator result recorded; no physical-device acceptance recorded | When hardware is available, run the same owned-fixture driver against an explicitly selected authorized device and retain separate evidence |
| Public channels and signing | GitHub/manual installer path supported; Marketplace/PyPI credentials and publisher signing not configured | Choose channels, configure publisher identity/credentials/signing where supported, and validate real install/update behavior |
| Detection quality | Targeted deterministic rules and integration checks; known missed behavior and limited source scope recorded | More independent, family-aware and prospective datasets; contextual review; expand rules only with positive/negative evidence |
| AI explanation quality | Two small local models produced structured output but unreliable/injection-contaminated prose | Human-reviewed evaluation and stronger quality controls before any production-quality explanation claim; retain advisory/off-by-default authority |
| Stable 1.0 | No stable 1.0 claim | Close the chosen release scope and independent acceptance above, document supported hosts and known limits, and verify release/update channels |

No implementation feature depends on a particular scanner, model, vendor, language or organization. Adapters can be enabled, disabled, replaced or removed independently. Network/cost declarations, producer manifests and preset hashes do not substitute for OS isolation or authenticated provenance.
