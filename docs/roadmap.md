# Remaining work and roadmap

Checkpoint: **2026-10-20**, application **0.5.0**, SDK **0.2.0**.
This is the known engineering backlog from the implementation audit. Order is by release
risk and user value; milestone names are planning targets, not promised dates.
[Verification](verification.md) owns measured results; [handover](../HANDOVER.md) owns
architecture and operating instructions.

## Current delivery

The optional-method platform is implemented: 15 independently controlled methods, six UI
tabs, guided installation/connections/permissions, concurrent providers, targeted Android/
Ktor source SAST, dependency scanners, scoped report imports, advisory research and local
AI. Source changes invalidate reports; native bounded seals and VS Code observed-change
checks protect their supported gates. The original scanner remains available. ZIP, VSIX, CLI and offline SDK packages are
built; the manual fixture produces real Semgrep and Bandit findings. These are scoped
security checks, not a guarantee that an application is secure.

## Release checklist — 0.5.0 stable

| Priority | Remaining task | Acceptance evidence |
|---|---|---|
| P0 | Pass the clean GitHub CI run on the release commit | JVM/VS Code jobs and Python 3.10/3.12/3.14 SDK matrix all green; archive the run URL |
| P0 | Install the candidate in normal Android Studio/IDEA and VS Code profiles | Plugin loads after restart; SDK install, connect, enable, scan, navigation, permissions, disable and disconnect pass the [manual procedure](verification.md#install-and-test-the-build) |
| P0 | Exercise actual native BUILD and Gradle SYNC vetoes | A disposable trusted Gradle project stops independently for each granted action; warning-only and revoked grants do not block; edits make reports stale |
| P0 | Review and merge the release PR, then publish stable artifacts | Approved source commit matches installer checksums; stable changelog/date, release assets and upgrade notes are recorded |
| P1 | Test minimum/current host versions rather than only one verifier target | IDEA platform 252 and current supported IDEA/Android Studio; VS Code minimum/current; macOS/Linux/Windows host smoke matrix |
| P1 | Run the real remaining upstream analyzers | Installed PSScriptAnalyzer, Trivy and OSV smoke fixtures on supported platforms; record exact versions, database/network prerequisites and expected findings |
| P1 | Exercise a real local model through both setup GUIs | Explicit endpoint/model, successful advisory output, unavailable model, cancellation and malformed response states; no source/evidence sent |
| P1 | Complete accessibility and error-state acceptance | Keyboard-only setup/navigation, screen-reader labels/focus, high contrast, light/dark and narrow layout; missing Python/tool/report, failed download and permissions recovery |

Local automated tests, installed Bandit/Semgrep, provider protocol checks, native binary
verification and the 21 labeled demo screenshots are already recorded. Real-tool protocol
fakes do not replace the outstanding installed-tool or host acceptance checks. Marketplace
and PyPI publication are separate distribution decisions; they have not happened.

## Reliability and coverage — 0.5.x / 0.6

| Priority | Task | Done when |
|---|---|---|
| P1 | Define provider-declared input scope and unified freshness | Source, reports, external rule files/modules and provider settings identify their dependencies; an input change invalidates the relevant result in both hosts |
| P1 | Strengthen execution seals beyond the current bounded native source/build input set | External rules, tool identities/versions and explicitly declared custom-provider inputs are sealed or visibly outside coverage; race/cap tests fail closed |
| P1 | Add a source snapshot seal to cooperative VS Code gates | Compare before/after content identity for declared inputs, closing unobserved-event races beyond the implemented observed-change invalidation; arbitrary terminal commands remain outside cooperative enforcement |
| P1 | Establish scan budgets on realistic repositories | Large Android multi-module/Ktor and mixed-language repositories measured for latency/memory/cancellation; caps remain visible and incomplete, never silently clean |
| P1 | Improve Android/Ktor rule coverage using reviewed packs | Add independently optional packs for relevant configuration, crypto/TLS, WebView/IPC and backend trust boundaries, with positive/negative regression projects and false-positive review |
| P1 | Make dependency coverage explicit | Tested Gradle/Maven lock/resolution inputs, unresolved/transitive dependency limitations, database age and scanner version shown; no dependency resolution or project execution without a separate explicit option |
| P2 | Support multiple VS Code workspace roots | Connections, scans, finding locations and grants use the intended root; no accidental first-folder scope |
| P1 | Add managed-data cleanup and retention | Users can remove unused SDK environments, old managed profiles and AI-input exports; cleanup never deletes original user configuration or reports |
| P2 | Improve connection maintenance | Version/readiness checks, reproducible tool upgrades, SDK repair/removal, cached/offline database guidance and actionable recovery; preserve original user files |
| P2 | Reduce native API compatibility exposure | Migrate the six deprecated API usages where supported and watch the 32 experimental usages with a broader verifier matrix |
| P2 | Add safe native Windows source-runner support | Implement equivalent no-follow/snapshot/process cancellation guarantees and real tests before enabling direct runners; report import remains available meanwhile |

## Interoperability and automation — 0.7

| Priority | Task | Done when |
|---|---|---|
| P1 | Publish provider conformance tools and sample adapters | Third-party SDKs can prove schema validity, attribution, limits, cancellation, operation-grant clipping and incomplete-state behavior without host changes |
| P2 | Add opt-in scan scheduling | Debounced or scheduled scans respect provider cost/network settings, cancellation and stale state; saving a file does not silently trigger newly enabled tools |
| P2 | Add opt-in CI and editor integration recipes | Copyable GitHub/Gradle/Maven/PowerShell/Python examples use the same CLI/provider API and explicit user configuration; documented exit-code behavior |
| P2 | Track report provenance and freshness | Imported reports expose origin, timestamp, source identity and selected scope; refresh/bind saved AI input snapshots to their source report/project; stale or unverifiable imports cannot imply current coverage |
| P2 | Add reusable policy and provider presets | Organization configurations remain reviewable and removable; repository content cannot silently grant execution or blocking authority |
| P2 | Design an optional active DAST runner | Explicit target ownership/scope, authenticated test environment, network/request budgets and stop/cancel controls; no backend startup or target guessing; keep scoped ZAP import independent |
| P2 | Evaluate stronger process isolation | Document and test OS-specific sandbox options for trusted scanner programs separately from warning/build/sync action grants |

## Research and AI — 0.8 and later

| Priority | Task | Done when |
|---|---|---|
| P1 | Build a realistic evaluation corpus | Reproducible licensed real-world benign/malicious projects and temporal splits; contamination, imbalance and limitations documented; synthetic fixtures remain regression tests |
| P2 | Evaluate script semantics before expanding authority | Precision/recall and false-positive/negative analysis for Python/PowerShell data flow; obfuscation, aliases, scopes, dynamic construction and cross-file limits measured |
| P2 | Add algorithm/model adapters as optional providers | Models, rules and algorithms have separate IDs/configuration/provenance and can be removed; no heuristic score downgrades deterministic blockers |
| P2 | Evaluate local AI utility and abuse resistance | Real models measured for explanation quality, hallucination, prompt injection, latency and data minimization; host-owned locations and advisory action ceiling retained |
| P2 | Consider dynamic deobfuscation only as an isolated service | No untrusted payload runs in the IDE process or ordinary scanner runner; explicit containment, resource/network controls and evaluation before any rollout |

## 1.0 release criteria

- The supported host/OS matrix and core manual workflows are continuously exercised.
- Enforcement, source freshness, cancellation, incomplete coverage and provider-grant
  boundaries have adversarial regressions and clear scope documentation.
- Rule/database/tool provenance and realistic coverage measurements replace any claim
  of universal protection. No benchmark number is inferred from demo fixtures.
- Install/upgrade/uninstall, signed distribution where supported, reproducible checksums,
  dependency/license review and a vulnerability-reporting route are ready for the chosen
  distribution channels.
- A new SDK or method can be connected, disabled and removed through the stable provider
  contract without rewriting the host UI or granting unrelated operations.

New evidence may add work. This records known gaps; passing unit tests does not complete
every platform or security assessment.
