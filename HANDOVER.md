# WrapperGuard release handover

**Merge/release checkpoint: 2026-10-20.** After accepting the partial roadmap, the user explicitly resumed work only to merge all PRs into master and create a release. No broader implementation, research or platform-fix work is implied. Resume deferred roadmap items only when requested.

## Master merge and RC2

[PR #4](https://github.com/Sal7one/NewWrapperGaurd/pull/4) merged normally into master at **980561993ca284e3e2fff9ef43cf0d2831a278a9** on **2026-10-20T20:26:22Z**. [PR #3](https://github.com/Sal7one/NewWrapperGaurd/pull/3), with the identical head, was recorded as merged into the same commit at 20:26:24Z. No open PRs remained at this checkpoint. All source changes from both branches are integrated.

Candidate **v0.7.0-rc.2** contains app **0.7.0**, SDK **0.4.0**, provider **API 1 / schema 1**. The [RC2 release](https://github.com/Sal7one/NewWrapperGaurd/releases/tag/v0.7.0-rc.2) and its `RELEASE-MANIFEST.json` identify uploaded packages, hashes, build source and documentation/tag source. Main master CI [39491977070](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39491977070) passed, including **234 JVM cases with zero failures/errors/skips**, Python 3.10/3.12/3.14, Node, parity, conformance, runtime audit and the delivered-file checksum roundtrip. The RC2 bundle is assembled with its own RC2 label; the workflow's internal RC1-labeled acceptance bundle is retained only as build evidence.

The merged tree equals the frozen RC1 tag's tree. Later RC2 checkpoint commits only update five Markdown guides. The manifest distinguishes **binary build source 9805619** from the **documentation/bundle source**, with a complete changed-path proof. Preserve all earlier releases.

Six real VS Code jobs passed **48 cases** on minimum/current editors across Linux/macOS/Windows. Its new VSIX changes only five README source links; all runtime entries and the SDK wheel match the accepted package. The new native ZIP, CLI ZIP and SDK wheel are byte-identical to the earlier actual-host-tested artifacts. Existing Studio 261 startup/Tools/PAUSED evidence keeps its original ce1 identity and limited scope; it is not relabeled as a new launch.

Native host workflow [39491977067](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39491977067) passed **six descriptor/component cases on each of IDEA 2025.2, 2026.2.2 and 2026.2.3**, with zero failures/errors/skips and exact target-matched frameworks in both configurations. All three binary verifiers are compatible, with zero internal API usages. Minimum platform reports 4 deprecated/25 experimental usages; both 262 targets report 84 deprecated/12 experimental usages. Raw reports and exact case names are preserved. This closes the application-only fixture follow-up, while full newer-IDE project lifecycle acceptance remains separate.

Fresh master Android [39491977008](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39491977008) passed on attempt 1: directly inspected unobscured owned UI, eight owned nodes, secure endpoint zero findings and weak endpoint three expected findings. Its APK/PNG hashes and unmodified evidence are in the RC2 acceptance archive. Windows scanners, Docker isolation, source rules, corpus and resource workflows also passed on the merged source. This remains emulator/agent evidence, not a physical-phone or human accessibility review.

## Historical RC1 build and provenance

The accepted installer set was built by [CI 39485233803](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39485233803), branch head **ce1a2c48294cc9bc2771f8d74ac6096867bf8dd8**, tested checkout **1c69fa84019eae80d6c1d3ad05c73e796d73721c**. Later source changes are documentation and an application-only test fixture; they change no production source. Release metadata keeps the installer build commit separate from the final documentation/tag commit. The refreshed bundle uses the audited CI installer bytes and current guides; it does not claim a new CI build of the documentation commit.

## Recorded acceptance baseline

- Main CI passed: minimum-platform build, 234 JVM tests, Python SDK matrix, Node checks, conformance/parity and runtime audit. Local suites also passed 158 Node and 53 script cases.
- The delivered ce1 bundle passed 348 checksum records, hidden-workflow delivery, offline Markdown closure and exact CLI ZIP byte/mode checks. All host SDK wheels are identical; the backend is pinned to setuptools 84.2.1. Actual packaged CLI gate checks passed on macOS/JDK 21 as well as Linux CI.
- Minimum IDEA 2025.2 passed six real descriptor/component cases with no failures/errors/skips. All three binary verifiers were compatible; minimum internal usages fell from six to zero after the Java factory boundary.
- The exact ce1 native ZIP passed real Android Studio 2026.1.1 / AI-261 startup, Tools rendering and persisted selections with a new session PAUSED. No Start/Scan/server was activated in this final smoke. Ordinary reopen sync is not a repeat of all build/sync guard tests. Earlier real guard/lifecycle evidence retains its own source identity.
- Six ce1 VS Code host jobs passed all 48 cases on 1.95.0/1.142.0 across Linux/macOS/Windows. Its runtime payload matches the actual installed eight-case acceptance checkpoint; only documentation links and reviewed SDK generator metadata differ from earlier packages. The test-only d611 follow-up also passed all six VS Code jobs.
- Android 16/API 36 emulator acceptance passed with an unobscured actual PNG: secure endpoint zero findings, weak endpoint three expected findings. The first System UI ANR capture remains rejected. The fixture APK is a controlled HTTP target, not the IDE plugin.
- Windows scanners/process boundaries, Linux Docker isolation, static corpus, source rules and resource checks passed. A Windows pip download truncated before tests; its single job retry passed. Failed attempts are preserved, not counted as successes.

## Deferred work and known limits

The ce1 newer-IDE fixture failures remain historical: an unrelated Ultimate project post-startup extension prevented two test bodies. The application-only `ApplicationRule` follow-up preserves every assertion and all heavy project/build/sync tests; the merged-source six-case matrix now passes on all three target IDEs. These component tests do not establish full newer-IDE startup/build/sync acceptance. That broader lifecycle work remains deferred; no further fixes are authorized by this handover.

Human screen-reader/independent usability review, physical-phone acceptance, publisher signing, Marketplace/PyPI channels, broader detection evaluation and reliable AI explanation quality remain deferred. Research retains known misses, limited labels and incorrect/injected model prose. No complete-security, paper-parity or stable-1.0 claim is made. [Roadmap](docs/roadmap.md) records the remaining work.

<a id="resume-in-this-order"></a>

## Resume only when requested

1. Read this file, the release manifest and [verification](docs/verification.md). Check actual tag/assets before assuming publication state; PRs #3 and #4 were merged at the recorded master checkpoint. Do not publish a duplicate release.
2. Inspect the working tree and preserve local changes. Read the manifest's source identities and historical failed reports before reopening a specific deferred item.
3. Choose a narrow scope with the user. Keep optional tools independently replaceable and retain the trust/authority invariants below.

All owned Studio processes were stopped; original profile properties were restored. The temporary RAM-backed index volume was detached after evidence was copied off it. No background agent or task-owned server should remain. Project-generated node_modules were removed to reclaim disk; run npm ci before future local Node checks. Source, accepted evidence, user SDKs and prior releases were preserved.

## Workspace and tools

The persistent repository is `/Users/salehalanazi/Repos/NewWrapperGaurd`, branch `master`, remote `Sal7one/NewWrapperGaurd`. Release work used `/private/tmp/wrapperguard-optional-methods`; use explicit workdirs because the original Documents workspace encountered macOS privacy restrictions. Root owns Git changes and publication; coordinate shared Gradle runs.

| Tool or evidence | Location / rule |
| --- | --- |
| JDK 21 | `/Users/salehalanazi/Library/Java/JavaVirtualMachines/jbr-21.0.8/Contents/Home` |
| Python | Python 3.10+ with pip; `/opt/homebrew/bin/python3.12`; SDK test environment `/private/tmp/wrapperguard-runtime-venv/bin/python` |
| Node | Node 22+; `npm --prefix vscode-extension ci` before local checks |
| Actual Studio | `/Applications/Android Studio.app`; disposable profile `/private/tmp/wrapperguard-studio-acceptance-736707cf/` |
| Persistent releases | `/Users/salehalanazi/Repos/NewWrapperGaurd/build/releases/`; preserve RC1 and RC2 bundles, flat publication assets and publication records |
| Merged-source evidence | `/private/tmp/wrapperguard-ci-review-master-9805619/` and `/private/tmp/wrapperguard-final-vscode-matrix-master-9805619/`; final release evidence archive preserves the accepted reports |
| Script log | `/private/tmp/wrapperguard-scripts-final-53.log` |
| Research | Tracked `research/results/2026-10-20/`; acquired inert sources stay outside Git at `/private/tmp/wrapperguard-research-final` |

Disk is constrained. Prefer hosted CI for large IDE/emulator/Docker checks; do not remove user SDKs, unrelated caches/daemons, personal IDE profiles or model services. Stop only owned disposable processes. No physical phone is attached. Temporary paths help resumption; tracked evidence and source-matched GitHub checks are authoritative.

[Verification](docs/verification.md) separates standalone bundle checks from full-checkout build/driver commands and gives manual installation and gate tests. Release compilation defaults to **2025.2**; a cached `-Pwrapperguard.platformVersion=2025.3` or local IDE override is development evidence only. Newer Compose/Jewel inline APIs can break the advertised minimum.

## Product contract

WrapperGuard hosts interchangeable analyzers in Android Studio, IntelliJ, VS Code and CLI. Providers own detection; the host owns attribution, action grants, policy, approvals, deadlines, content seals and aggregate reports. Original static vetting remains usable. No vendor/language/SDK is mandatory. Arbitrary SDKs/custom commands retain their own categories/tags and independent removal.

The shared catalog has 19 methods in Built-in/SAST/Supply chain/DAST/Research/AI. Six built-in groups default on; external/research/AI methods default off. Flags are user-owned, not repository grants. Existing API 1 and Kotlin descriptor ABI remain compatible. Zero detectors or AI/research alone cannot establish complete analysis. Deliberately disabled checks produce reduced-scope notices; malformed/partial/expired/unreadable/capped coverage remains incomplete.

## GUI and execution

Tools owns category filters, connections, flags, presets and automation. Research/AI outputs remain advisory. Native Tools includes custom SDK/storage; VS Code puts these in Providers and exposes explicit multi-root selection. History is opt-in, bounded and stores titles/relative paths/provider/scope metadata, never source/messages/evidence. Unknown producer/SDK/model scopes disable comparison. Baseline approval never looks like remediation.

Connections use literal argv, optional declared scope and project bindings. New custom scope is undeclared/unknown until explicitly supplied. Generated profiles live outside projects and default to warning-only SCAN authority. Native/custom setup saves disabled; the reviewed VS Code Connect-and-run action explicitly enables and scans. Canceling setup/review saves nothing and runs nothing. Native connection save/enable does not scan; VS Code's explicit final Connect-and-run action does. Missing-tool repair/edit/remove paths and dialog focus recovery are supported.

Automation saves reviewed per-provider network/cost declarations but remains session-paused until explicit Start. Start makes no immediate/catch-up run. Future change/interval events trigger nonoverlapping SCAN; Pause cancels current automatic work. Root/trust/provider/settings changes pause the session. Declarations are not an OS sandbox. Managed SCAN-only HTTP/research connections are skipped before BUILD/SYNC launch; opaque custom adapters retain their legacy operation contract.

Organization presets contain issuer/version, flags and canonical SHA-256 only. Import/export review cannot install tools, change saved provider permissions, grant actions or start scans. Setting a catalog flag true can restore eligibility for a previously enabled/granted connection; review those effective flags before applying. Digests do not authenticate issuers.

## SDK and trust boundaries

SDK 0.4 has zero runtime dependencies, eight passive report imports and four controlled/advisory outputs, five fixed scanner runners and five selectable Semgrep packs. The original six-rule pack remains default. Profiles are outside-project and use trusted absolute Python with `-I -m wrapperguard_sdk`. Python 3.10+ is required. Both GUI packages bundle the same normalized wheel bytes.

Direct runners support POSIX and tested Windows handles/job/pipes; actual five-scanner Windows CI and Python 3.10/3.12/3.14 boundary jobs pass. Snapshotting/host action grants do not sandbox installed OS-user code. Trivy may download databases; OSV sends dependency metadata. No runner builds/resolves/installs project dependencies.

Report provenance optionally records exact report/input hashes, producer/version, timestamp/source/database/scope and maximum age. Missing identity stays unverified; no signature/full-analysis claim follows. Dependency coverage exposes resolved/unknown versions and unresolved transitive limits without executing Gradle/Maven.

ZAP remains scoped passive import. Separate optional HTTP audit uses exact numeric-IP/path ownership, bounded GET headers/cookies, no redirects/DNS/target discovery/startup, and authentication variable names only. It is SCAN-only and limited DAST, not crawling/exploit coverage.

Optional local Docker selection supports Bandit/Semgrep and selected-entry Python research. It uses a preinstalled immutable image, copied private staging, no original-project bind, no network, read-only rootfs and explicit cgroup/PID/CPU limits. A trusted root timer/bootstrap retains only KILL/SETUID/SETGID; workers drop to UID/GID 65532 and no capabilities before data access. Cleanup verifies container absence, including SDK kill. Kernel/daemon/bootstrap remain trusted; this is not VM isolation. Python observations are bypassable INFO/AUDIT_EVIDENCE with no actions and no host fallback.

Static semantics never executes decoded/project payloads. Local AI sends minimized finding metadata only to an explicit numeric-loopback server, excluding source/evidence/project paths. Host locations and INFO/advisory/empty-actions are invariant. Real small-model evaluations still show serious incorrect/injected prose; no model quality or paper-parity claim is justified.

## Important engineering invariants

- Keep Kotlin/TS scanner/coordinator and fingerprints aligned. Stable rule IDs are not renumbered. First-party actual reads and declared/config/report inputs participate in before/after content seals. Unknown scope cannot become verified coverage.
- Source/depth/file/output caps, unsupported inputs and cancellations remain visibly incomplete. Repository suppression markers cannot create host approvals.
- Requested actions intersect user grants and operation policy. INFO/audit output and bundled advisory research/AI methods cannot block or downgrade deterministic findings. A custom provider category is metadata; its explicit grants control authority. Legacy `MlScorer` is inactive.
- Keep subprocesses in their provider-owned process trees/groups so cancellation can contain descendants. SDK fixed runners use Windows job objects; the generic Windows command bridge uses best-effort taskkill /T cleanup. Never detach tools to evade cancellation. Error messages/redaction/output are bounded.
- Core/rules/CLI are IDE-neutral. Sorting/filtering cannot change reports or authority. Native before-run extension registration and UI-thread blocker dialogs have real IDE regressions.
- Version identity comes from build-expanded core `AppVersion.current`; TS reads packaged package.json. CLI/provider/SARIF must agree with package versions. Provider-smoke catches mismatches.
- Cleanup removes only explicit owned unused generated artifacts; active/referenced/user/legacy files survive. SDK repair is offline; analyzer update/download remains explicit.

## Build and scope limits

The SDK builder stages clean sources, verifies metadata/runtime bytes and normalizes wheel archive metadata. Its backend is pinned to setuptools 84.2.1. Both IDE packages and the standalone SDK must contain identical wheel bytes. README or source changes can alter package bytes; regenerate all affected artifacts and rerun the audit.

The distribution audit covers actual shipped runtime closure, licenses, source/wheel identity and optional live OSV dependency metadata. It does not prove analyzer accuracy or inventory separately installed tools/platforms. The packager requires clean committed source and captured artifact bytes matching that audit. [Verification](docs/verification.md#build-and-audit-the-final-source) owns the commands.

[Research](research/README.md) retains the 77-source corpus evaluation, known misses, small-model failures and resource measurements. No malicious corpus payloads or model weights are redistributed. Human screen-reader/usability review, physical hardware where required, public-channel credentials/signing and stronger research claims remain explicit [roadmap](docs/roadmap.md) work.
