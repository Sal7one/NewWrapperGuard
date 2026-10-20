# WrapperGuard release runbook

Evidence checkpoint: **2026-10-20**, branch head **`a97dcd19133e9b70b64bc88ae30835822712dbed`**, tested PR merge **`9b48d88d2bd70deb6e8f39f43e4e1dc97f2344c2`**. App **0.7.0**, SDK **0.4.0**, provider **API 1 / schema 1**. The candidate name is **`v0.7.0-rc.1`**. Canonical release metadata, checksums and `SOURCE-COMMIT.txt` identify an actual publication; a branch, test bundle or older successful run does not establish one.

First inspect the canonical 0.7 tag/release and its source/assets. If the intended candidate or a superseding verified 0.7 release already exists, use it and do not republish it. For unfinished branch work, [PR #3 checks](https://github.com/Sal7one/NewWrapperGaurd/pull/3/checks) are authoritative for the current head. Historical evidence keeps its original source and package identities.

## Archived pre-fix evidence: a97dcd1

| Archived evidence at a97dcd1 | Recorded result |
| --- | --- |
| [Main CI 39480825937](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39480825937) | Passed: JVM build, Python 3.10/3.12/3.14, conformance/parity, distribution audit and standalone bundle gate tests |
| [Host matrix 39480826114](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39480826114) | Six VS Code jobs / 48 cases and minimum native 2025.2 passed. Both 262 native targets passed five of six cases; the enabled-plugin assertion failed. All three binary verifiers were compatible |
| [Actual Android Studio](demo/screenshots/platform-0.7/android-studio/minimum-a97dcd1/evidence.json) | Exact minimum-built a97 ZIP passed real Studio 261 startup, Tools and persisted PAUSED automation review |
| [Android emulator](demo/screenshots/platform-0.7/android-device/a97dcd1/README.md) | Actual Android 16/API 36 owned activity and HTTP audit passed; unmodified rendered PNG directly inspected; APK hash independently matched |
| Regression and package review | 158 Node and 53 script cases passed, including seven offline-link/CLI-integrity cases. The a97 nested bundle passed 288 checksums and 28 Markdown destination checks |

At that checkpoint the blocker was the newer native runtime matrix. [Raw a97 reports](demo/screenshots/platform-0.7/intellij-verifier/a97dcd1/README.md) preserve both five-of-six failures. Jewel component creation passed; the test runtime could not register the Gradle/WrapperGuard dependency chain because the product module `smRunner` remained unresolved. Adding individual jars did not solve product-module registration. Earlier `76312ac` listener-startup failures are a separate historical stage.

The follow-up in this source selects **IntelliJ Platform Gradle Plugin 2.18.1**. The [upstream 2.18 fix](https://github.com/JetBrains/intellij-platform-gradle-plugin/releases/tag/2.18.0) makes `TestIdeTask` use product-info bundled-plugin classpaths and resolve module aliases. Exact target-framework build assertions remain in both custom test configurations. A small Java `ToolWindowFactory` entry point inherits the platform's internal JVM defaults directly, avoiding Kotlin-generated forwarding overrides; Kotlin retains the content/failure boundary. Source-matched green matrix results and released evidence determine closure. The [cached 253 local preflight](demo/screenshots/platform-0.7/intellij-verifier/local-253-ipgp2181/README.md) passed all six cases with zero failures/errors/skips; it is preflight evidence, not minimum 252 or 262 acceptance. Require **six actual cases per native target, zero failures/errors/skips**, plus retained binary-verifier reports; no waivers or exclusions.

This source pins **setuptools 84.2.1**, includes hidden files in the generated-artifact upload, and downloads/checks both delivered checksum manifests. The a97 expanded artifact omitted its hidden workflow file; its nested ZIP remained intact. Its SDK archive changed only WHEEL/RECORD generator metadata from 84.2.0 to 84.2.1; runtime files were identical. Source-matched final artifacts and the delivery roundtrip are release criteria.

At follow-up `ce1a2c4`, [main CI](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39485233803) passed, including the delivered bundle roundtrip; 348 checksums and the offline guide closure were independently checked. The packaged CLI passed on actual macOS as well as Linux CI. All six VS Code jobs / 48 cases passed with runtime-equivalent VSIX bytes. Android acceptance passed on a fresh runtime-job retry; its first System UI ANR capture remains rejected. A truncated pip download required one Windows job retry before tests ran. Both retries retain their failed attempt records.

The [ce1 native matrix](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39485233651) passed all three binary verifiers with zero minimum internal usages and all six minimum runtime cases. Both newer targets failed before the two project-based test bodies: an unrelated bundled Ultimate post-startup extension could not instantiate. The descriptor/component cases in this source use the official application-only `ApplicationRule`, because neither needs an open project. Every loader, enabled-plugin, factory ABI, target-version and actual-component assertion remains; heavy project/build/sync tests remain separate and unchanged. Require fresh six-case results on all targets for this fixture change. This does not claim full project-lifecycle acceptance on every newer IDE.

## Resume in this order

1. Check the canonical 0.7 tag/release first. If already published and verified against its source and asset hashes, use that release rather than publishing again. Otherwise inspect the working tree, live PR head and checks. Preserve local changes and historical reports. PR CI can test a synthetic merge commit; retain that identity separately from the branch head. Avoid canceling expensive runtime jobs with unnecessary pushes.
2. For an unclosed release gate, validate the native follow-up and require the complete matrix on **2025.2, 2026.2.2 and 2026.2.3**. Keep minimum 252 release compilation and all six assertions. Collect actual target-framework evidence and plugin-loader failures when a job fails.
3. Check the final packages on actual hosts. Use the CI minimum-built native ZIP, not a local newer-SDK build. Reinstall/smoke when production classes/resources change. The latest recorded Studio and Android results above apply to a97; they do not certify later binaries. VS Code README links intentionally include the exact packaging commit.
4. Commit source/docs/evidence, refresh the PR description and merge only after required checks pass. Require the final merged-source workflows; do not substitute a different-source PR result. GitHub/manual installer publication is separate from Marketplace/PyPI/signing.
5. Download the same-build `wrapperguard-distributions` artifact from the final successful main CI. Verify `SOURCE-COMMIT.txt`, four artifact hashes in `distribution-audit/distribution-audit.json`, `SHA256SUMS` and `BUNDLE-SHA256SUMS.txt`. Check the delivered hidden workflow and offline links. CI extracts the CLI from the audited ZIP and runs standalone `CHECK-GATES.py` before upload; the upload/download roundtrip verifies delivered bytes.
6. Publish an unpublished candidate only after these gates; do not overwrite or duplicate an existing verified release. Attach the four packages, nested test bundle, checksums and runtime SBOM/audit with exact source and limitations; verify uploaded asset hashes. An optional owned-fixture APK must be labeled as the Android HTTP acceptance target, not a mobile WrapperGuard plugin. Preserve earlier releases and rejected diagnostics.

```sh
gh release view v0.7.0-rc.1 --repo Sal7one/NewWrapperGaurd
gh release view v0.7.0 --repo Sal7one/NewWrapperGaurd
git status --short --branch
git log -5 --oneline
gh pr view 3 --repo Sal7one/NewWrapperGaurd --json state,headRefOid,baseRefName,url
gh pr checks 3 --repo Sal7one/NewWrapperGaurd
gh run list --repo Sal7one/NewWrapperGaurd --limit 10
gh release list --repo Sal7one/NewWrapperGaurd --limit 3
```

## Workspace and tools

This session uses `/private/tmp/wrapperguard-optional-methods` on `codex/flexible-provider-platform`; original repository `/Users/salehalanazi/Repos/NewWrapperGaurd`, remote `Sal7one/NewWrapperGaurd`. Use explicit workdirs; the original Documents workspace encountered macOS privacy restrictions. Root owns Git changes and publication; coordinate shared Gradle runs.

| Tool or evidence | Location / rule |
| --- | --- |
| JDK 21 | `/Users/salehalanazi/Library/Java/JavaVirtualMachines/jbr-21.0.8/Contents/Home` |
| Python | Python 3.10+ with pip; `/opt/homebrew/bin/python3.12`; SDK test environment `/private/tmp/wrapperguard-runtime-venv/bin/python` |
| Node | Node 22+; `npm --prefix vscode-extension ci` before local checks |
| Actual Studio | `/Applications/Android Studio.app`; disposable profile `/private/tmp/wrapperguard-studio-acceptance-736707cf/` |
| Current temporary package review | `/private/tmp/wrapperguard-release-audit-a97dcd1/`; nested bundle, four verified packages and `verification.json` retained |
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
