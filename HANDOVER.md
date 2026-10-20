# WrapperGuard engineering handover

Updated **2026-10-20**. App **0.7.0**, SDK **0.4.0**, provider **API 1**, report **schemaVersion 1**. Active branch `codex/flexible-provider-platform`. Candidate/release status and exact commit/checksums belong in [verification](docs/verification.md) and GitHub releases; a local package, branch push, merge and release are separate events.

## Workspace and ownership

Active checkout: `/private/tmp/wrapperguard-optional-methods`. Original repository: `/Users/salehalanazi/Repos/NewWrapperGaurd`, remote `Sal7one/NewWrapperGaurd`. The Documents workspace path is blocked by macOS privacy controls. Use explicit workdirs. The published0.6.0 RC1 bundle remains in the original repository's `build/releases/0.6.0-rc.1/`; preserve its tag/assets. Temporary evaluation directories/services are not a deployment.

The user authorized implementation, optional tools/GUI, research, tests, documentation cleanup, repo pushes, releases and deployment testing. No Marketplace/PyPI/signing credentials are configured. Do not claim those publications, physical-device acceptance or formal human accessibility review occurred.

## Product contract

WrapperGuard hosts interchangeable analyzers in Android Studio, IntelliJ, VS Code and CLI. Providers own detection; the host owns attribution, action grants, policy, approvals, deadlines, content seals and aggregate reports. Original static vetting remains usable. No vendor/language/SDK is mandatory. Arbitrary SDKs/custom commands retain their own categories/tags and independent removal.

The shared catalog has19 methods in Built-in/SAST/Supply chain/DAST/Research/AI. Six built-in groups default on; external/research/AI methods default off. Flags are user-owned, not repository grants. Existing API 1 and Kotlin descriptor ABI remain compatible. Zero detectors or AI/research alone cannot establish complete analysis. Deliberately disabled checks produce reduced-scope notices; malformed/partial/expired/unreadable/capped coverage remains incomplete.

## GUI and execution

Tools owns category filters, connections, flags, presets and automation. Research/AI outputs remain advisory. Native Tools includes custom SDK/storage; VS Code puts these in Providers and exposes explicit multi-root selection. History is opt-in, bounded and stores titles/relative paths/provider/scope metadata, never source/messages/evidence. Unknown producer/SDK/model scopes disable comparison. Baseline approval never looks like remediation.

Connections use literal argv, optional declared scope and project bindings. New custom scope is undeclared/unknown until explicitly supplied. Generated profiles live outside projects, save disabled and default warning-only SCAN authority. Canceling setup/review saves nothing and runs nothing. Native connection save/enable does not scan; VS Code's explicit final Connect-and-run action does. Missing-tool repair/edit/remove paths and dialog focus recovery are supported.

Automation saves reviewed per-provider network/cost declarations but remains session-paused until explicit Start. Start makes no immediate/catch-up run. Future change/interval events trigger nonoverlapping SCAN; Pause cancels current automatic work. Root/trust/provider/settings changes pause the session. Declarations are not an OS sandbox. Managed SCAN-only HTTP/research connections are skipped before BUILD/SYNC launch; opaque custom adapters retain their legacy operation contract.

Organization presets contain issuer/version, flags and canonical SHA-256 only. Import/export review cannot install tools, enable commands, grant actions or start scans. Digests do not authenticate issuers.

## SDK and trust boundaries

SDK 0.4 has zero runtime dependencies, eight passive report imports and four controlled/advisory outputs, five fixed scanner runners and five selectable Semgrep packs. The original six-rule pack remains default. Profiles are outside-project and use trusted absolute Python with `-I -m wrapperguard_sdk`. Python 3.10+ is required. Both GUI packages bundle the same normalized wheel bytes.

Direct runners support POSIX and tested Windows handles/job/pipes; actual five-scanner Windows CI and Python 3.10/3.12/3.14 boundary jobs pass. Snapshotting/host action grants do not sandbox installed OS-user code. Trivy may download databases; OSV sends dependency metadata. No runner builds/resolves/installs project dependencies.

Report provenance optionally records exact report/input hashes, producer/version, timestamp/source/database/scope and maximum age. Missing identity stays unverified; no signature/full-analysis claim follows. Dependency coverage exposes resolved/unknown versions and unresolved transitive limits without executing Gradle/Maven.

ZAP remains scoped passive import. Separate optional HTTP audit uses exact numeric-IP/path ownership, bounded GET headers/cookies, no redirects/DNS/target discovery/startup, and authentication variable names only. It is SCAN-only and limited DAST, not crawling/exploit coverage.

Optional local Docker selection supports Bandit/Semgrep and selected-entry Python research. It uses a preinstalled immutable image, copied private staging, no original-project bind, no network, read-only rootfs and explicit cgroup/PID/CPU limits. A trusted root timer/bootstrap retains onlyKILL/SETUID/SETGID; workers drop toUID/GID 65532 and no capabilities before data access. Cleanup verifies container absence, includingSDKkill. Kernel/daemon/bootstrap remain trusted; this is not VM isolation. Python observations are bypassable INFO/AUDIT_EVIDENCE with no actions and no host fallback.

Static semantics never executes decoded/project payloads. Local AI sends minimized finding metadata only to an explicit numeric-loopback server, excluding source/evidence/project paths. Host locations and INFO/advisory/empty-actions are invariant. Real small-model evaluations still show serious incorrect/injected prose; no model quality or paper-parity claim is justified.

## Important engineering invariants

- Keep Kotlin/TS scanner/coordinator and fingerprints aligned. Stable rule IDs are not renumbered. First-party actual reads and declared/config/report inputs participate in before/after content seals. Unknown scope cannot become verified coverage.
- Source/depth/file/output caps, unsupported inputs and cancellations remain visibly incomplete. Repository suppression markers cannot create host approvals.
- Requested actions intersect user grants and operation policy. INFO/audit/research cannot block or downgrade deterministic findings. Legacy `MlScorer` is inactive.
- Child analyzers inherit host process groups; Windows job objects contain descendants. Never detach tools to evade cancellation. Error messages/redaction/output are bounded.
- Core/rules/CLI are IDE-neutral. Sorting/filtering cannot change reports or authority. Native before-run extension registration and UI-thread blocker dialogs have real IDE regressions.
- Version identity comes from build-expanded core `AppVersion.current`; TS reads packaged package.json. CLI/provider/SARIF must agree with package versions. Provider-smoke catches mismatches.
- Cleanup removes only explicit owned unused generated artifacts; active/referenced/user/legacy files survive. SDK repair is offline; analyzer update/download remains explicit.

## Build, evidence and research

Use JDK 21, Node 22+ and modern Python 3.10+ with pip. [Verification](docs/verification.md) owns reproducible commands/manual tests and exact evidence. Build IDE defaults to the minimum 2025.2; verifier defaults to 2025.3; minimum platform 252; minimum/current verifier properties and actual Android Studio tests are separate. Close disposable IDEs before replacing their packages; save settings before forced process cleanup.

`buildIntegrationSdk`/`scripts/build-sdk.py` stage clean package sources, validate name/version/dependencies/module bytes and normalize archive metadata. Current artifact: `wrapperguard_sdk-0.4.0-py3-none-any.whl`. README/source changes require a rebuild. `scripts/distribution-audit.py` validates actual runtime closure, wheel/license identity and optional live OSV metadata; `scripts/package-test-bundle.py` requires clean committed source.

Actual IDEA/Studio gates, VS Code setup/automation/preset/AX/theme flows and Android 16 emulator results are recorded in galleries. Synthetic demo screenshots stay separately labeled. [Research](research/README.md) records77-source inert acquisition, known misses, two real models and resource measurements; no malware samples/model weights are redistributed. Task-owned model server/weights were removed after evidence collection. Do not touch the user's model services, SDKs, unrelated Gradle daemons or personal IDE profiles.

## Remaining work

[Roadmap](docs/roadmap.md) distinguishes completed engineering work from final exact-commit release checks, human accessibility/usability, physical hardware where required, signed/chosen public channels and stronger research-quality claims. Preserve those limits rather than checking boxes without evidence. Obsolete duplicated release-readiness documentation was removed; the current six focused guides and historical release evidence remain useful.
