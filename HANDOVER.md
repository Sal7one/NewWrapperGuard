# WrapperGuard engineering handover

Updated **2026-10-20, 16:59 UTC / 19:59 Riyadh**. App **0.7.0**, SDK **0.4.0**, provider **API 1**, report **schemaVersion 1**. Read this file first when resuming. The user requested a status report and a published handover for another agent; finish the remaining acceptance and release work from the recorded checkpoint without restarting the architecture work.

The last recorded runtime checkpoint is **`76312ace4306c906c329e408346a59d2e893f3c7`** on **`codex/flexible-provider-platform`**. This documentation/evidence handover is on **[`codex/handover-0.7-2026-10-20`](https://github.com/Sal7one/NewWrapperGaurd/tree/codex/handover-0.7-2026-10-20)**, branched from that source. The handover commit has now been brought into the active candidate branch for the authorized continuation. Documentation changes, including the extension README, must be included before final packaging; they change package bytes. A local build, pushed branch, merge and published release are separate events.

## Workspace and ownership

Active checkout: `/private/tmp/wrapperguard-optional-methods`. Original repository: `/Users/salehalanazi/Repos/NewWrapperGaurd`, remote `Sal7one/NewWrapperGaurd`. The Documents workspace path is blocked by macOS privacy controls. Use explicit workdirs. The published 0.6.0 RC1 bundle remains in the original repository's `build/releases/0.6.0-rc.1/`; preserve its tag/assets. Temporary evaluation directories/services are not a deployment.

The user authorized implementation, optional tools/GUI, research, tests, documentation cleanup, repo pushes, releases and deployment testing. GitHub CLI is authenticated as `Sal7one`; use `/opt/homebrew/bin/gh` and explicit `--repo Sal7one/NewWrapperGaurd`. No Marketplace/PyPI/signing credentials are configured. Do not claim those publications, physical-device acceptance or formal human accessibility review occurred.

## Resumed release work

Resumed **2026-10-20** on `codex/flexible-provider-platform`, incorporating handover `25bec78`. The test-framework mismatch is confirmed in IPGP 2.11's dependency helper: its default uses the base compilation platform even within the custom IDE test task. The fix pins the target framework to the exact IDE build and records/asserts resolved modules before tests launch; fresh CI/runtime acceptance is required before calling the gate passed. Minimum 2025.2 release compilation and all six real runtime cases remain unchanged.

The framework fix at `59daa26` now lets all six cases execute on the newest IDE: actual Jewel creation passes, while the enabled-plugin assertion identified missing `smRunner`/`testRunner` modules in the custom 262 test runtime. The next patch adds those explicitly without changing production descriptors or weakening assertions. Main CI and all six VS Code jobs passed at `59daa26`; installed VSIX acceptance passed all eight cases and 158 Node tests. The integrated script suite now passes 53 tests, including seven offline-link/CLI-byte-integrity regressions.

VSIX packaging now uses exact-commit monorepo content/image URLs, correcting broken relative README links. Main CI also builds and exercises the acceptance bundle, verifies all checksums, and retains a matching distribution set as `wrapperguard-distributions`. The follow-up preserves offline gallery/guide paths, validates their local links and extracts the runnable CLI from the exact audited ZIP bytes; it does not copy an unaudited live installDist tree. These are build/packaging changes; the optional analysis/provider API is unchanged. Final installers and publication still require the gates below.

## Prior checkpoint and published state (`76312ac`)

- **PR #2 is merged**, at `2026-10-20T16:09:15Z`, merge `6581deccdb94abb42e06d5ce28b561f1a15bef3a`.
- **[PR #3](https://github.com/Sal7one/NewWrapperGaurd/pull/3) is open against `master`**, head `76312ace4306c906c329e408346a59d2e893f3c7`. It contains the optional-method platform and SDK 0.4. Do not mistake the earlier approved PR for this still-open follow-up.
- Latest published release: **[v0.6.0-rc.1](https://github.com/Sal7one/NewWrapperGaurd/releases/tag/v0.6.0-rc.1)**, published `2026-10-20T13:53:07Z`. Latest stable: **v0.4.0**, published `2026-10-19`. **No 0.7 release/tag or final acceptance bundle exists.**
- Local checks passed: **234 JVM** (96 core, 45 rules, 8 CLI, 85 native), **156 Node**, **46 script regressions**, and **148 SDK tests** with nine Windows-only skips. Five hardening checks, parity, provider conformance and eight actual local VS Code host cases also passed. Actual Windows CI covers the skipped platform boundaries.
- All **six real VS Code matrix jobs / 48 host cases** passed on source `76312ac`: versions 1.95.0 and 1.142.0 across Linux/macOS/Windows. The overall host workflow is red because the two newer native runtime tasks failed.
- **Android 16 / API 36 emulator acceptance passed** in [run 39459625818](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39459625818). The real unmodified 720×1280 screenshot was directly inspected: no modal or blank frame; secure endpoint zero SDK findings, weak endpoint three. [Evidence](demo/screenshots/platform-0.7/android-device/evidence.json) records head `76312ac`, CI PR-merge source `67e613e233904d894d05e1c3cc452c2e4a577695`, APK SHA-256 and agent visual review. No human visual review or physical phone test occurred.
- **Main CI passed. The native matrix is the immediate blocker:** all three binary verifiers passed, and minimum 2025.2 passed all six actual runtime tests; actual runtime tasks for 2026.2.2 and 2026.2.3 failed before any test ran, while Gradle instantiated `com.intellij.tests.JUnit5TestSessionListener` through ServiceLoader. Preserve and diagnose the failed runtime evidence before merging or releasing. This does not yet establish a product UI defect or a runtime pass. A binary-compatible verdict alone does not establish that the UI initializes in that runtime.

| Workflow on head `76312ac` | Recorded status | Run |
|---|---|---|
| CI / minimum build, packages, audit, SDK matrix | Passed | [39459625691](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39459625691) |
| Supported host compatibility | Failed: six VS Code jobs and minimum native passed; newer two native runtime tasks failed | [39459625955](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39459625955) |
| Android device acceptance | Passed, actual clean screenshot reviewed | [39459625818](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39459625818) |
| Platform boundaries and official scanners | Passed | [39459625654](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39459625654) |
| Isolated runtime acceptance | Passed | [39459625784](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39459625784) |
| Static research corpus evaluation | Passed | [39459625866](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39459625866) |
| Pinned Android and Ktor rule review | Passed | [39459625620](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39459625620) |
| Scanner resource acceptance | Passed | [39459625897](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39459625897) |

## Immediate blocker: newer native test-runtime startup

The failed 2026.2.2 and 2026.2.3 reports contain a synthetic **Gradle Test Executor 1 / failed to execute tests** case, not six executed product tests. The nested cause is **`NoClassDefFoundError: com/intellij/util/Java11Shim`** during `TestEnvironmentKt.initializeTestEnvironment` → `UsefulTestCase` static initialization → `JUnit5TestSessionListener` construction through ServiceLoader. The executor used target JBR 25.0.4. No product test or Jewel factory invocation occurred on either target.

All three binary-verifier verdicts are compatible; 2025.2 additionally executed and passed all six real cases. [Preserved final-checkpoint reports](demo/screenshots/platform-0.7/intellij-verifier/76312ac/) retain both successes and failures. Inspect `wrapperguard-intellij/build.gradle.kts`, particularly the base `testFramework(TestFrameworkType.Platform)` and `intellijPlatformTesting.testIde.register("testJewelBridgeRuntime")` dependencies. **Confirmed resolution mismatch:** IPGP 2.11 selects the base framework by default. The continuation pins the custom task to its exact target build and checks resolved modules. Validate that fix on both newer targets while keeping release compilation at 2025.2 and retaining all six case assertions. Do not hide ServiceLoader failures or call binary compatibility alone a pass.

## Resume in this order

1. **Read the live state before editing.** Run `git status --short --branch`, fetch `origin`, then inspect PR #3 and the completed runs, including the red native runtime tasks. Preserve local changes. Historical Actions results apply to their recorded source; GitHub may test a synthetic PR merge commit rather than the branch head. Repeated pushes to PR #3 cancel its long-running jobs; collect the current reports before starting a new checkpoint.
2. **Fix and close the native runtime matrix first.** Run `39459625955` passed all three binary verifiers and the minimum 2025.2 six-case runtime suite, but runtime tasks failed on **2026.2.2** (job `118755379923`) and **2026.2.3** (job `118755379963`). Read the preserved reports below and failed step **Invoke the public Jewel bridge in the actual target runtime**. The observed failure is test-runtime startup before cases execute; the product factory has not been validated on those two targets. Read the preserved ServiceLoader/JUnit listener exception and validate the explicit target-framework fix; do not weaken/skip tests or accept binary verification as a substitute. Re-run the matrix on the fix and require compatible verdicts plus **six runtime cases per target with zero failures/errors/skips**. Artifact names are `intellij-verifier-{version}` and `intellij-bridge-runtime-{version}`. Evidence belongs under `demo/screenshots/platform-0.7/intellij-verifier/<source>/`; retain both failed and earlier a7 records.
3. **Check final installers on the actual host.** Main CI `39459625691` passed; download its `wrapperguard-plugin` artifact and hash the ZIP. Use this **minimum-compiled** ZIP for final Android Studio acceptance. The local cached-2025.3 ZIP is not the release binary. The isolated Studio profile is described below; verify startup, Tools and persisted session-paused automation. Rebuild and reinstall the VSIX after its README changes; retain actual-host evidence. Re-run targeted checks if a gate requires a source fix.
4. **Consolidate docs and source.** Bring this handover branch's commits into `codex/flexible-provider-platform` after the existing evidence is collected. Run documentation/link/evidence checks. Refresh the PR description and review the exact diff/head. Merge PR #3 through the normal GitHub flow only when its required checks pass. Record the actual merge and run the required checks on the final release source; do not reuse a failed/canceled or different-source result as a final pass.
5. **Build, audit and package the final committed source.** Build the SDK, CLI, minimum-native ZIP and VSIX; re-run distribution audit after any package changes. The local `build/distribution-audit` directory currently describes an older native package and must not be reused as final evidence. `package-test-bundle.py` requires clean committed source and matching fresh artifact hashes. Use a new output directory; preserve the 0.6 release bundle.
6. **Publish the candidate only after those gates pass.** Proposed next tag is `v0.7.0-rc.1`. Attach the four packages, checksums, SBOM/audit and test bundle with exact source/evidence and clear remaining limits. Verify the uploaded release assets and install/test instructions. Marketplace/PyPI/signing remain unconfigured and are separate publication work. Stable 1.0, formal screen-reader/usability review, physical hardware and stronger research claims remain separate [roadmap](docs/roadmap.md) work.

Read-only status commands:

```sh
git status --short --branch
git log -5 --oneline
/opt/homebrew/bin/gh pr view 3 --repo Sal7one/NewWrapperGaurd --json state,headRefOid,baseRefName,url
/opt/homebrew/bin/gh run view 39459625955 --repo Sal7one/NewWrapperGaurd
/opt/homebrew/bin/gh run view 39459625691 --repo Sal7one/NewWrapperGaurd
/opt/homebrew/bin/gh release list --repo Sal7one/NewWrapperGaurd --limit 3
```

## Local tooling and retained evidence

| Resource | Location / instruction |
|---|---|
| JDK 21 | `/Users/salehalanazi/Library/Java/JavaVirtualMachines/jbr-21.0.8/Contents/Home` |
| Modern Python | `/opt/homebrew/bin/python3.12`; default Apple Python 3.9 is too old |
| Installed SDK test environment | `/private/tmp/wrapperguard-runtime-venv/bin/python` (includes jsonschema) |
| Node dependencies | The old `node_modules` symlink was removed; run `npm --prefix vscode-extension ci` before using Node build/test/package commands |
| Actual Studio | `/Applications/Android Studio.app`, 2026.1.1 Patch 2 / AI-261.23567.138.2611.15646644 |
| Disposable Studio profile | `/private/tmp/wrapperguard-studio-acceptance-736707cf/`; properties, VM options, config/SDK/logs retained; no IDE running at handover |
| Native capture helper | `scripts/capture-native.swift`; use the actual selected IDE window. A PID-locked UI helper in the disposable profile may activate real controls; never call scanner/guard internals for acceptance |
| Local native tests | `/private/tmp/wrapperguard-native-final-85.log`; cached 2025.3 compilation only |
| Node / script logs | `/private/tmp/wrapperguard-final-156-node-tests.log`, `/private/tmp/wrapperguard-scripts-final-46.log` |
| Prior minimum-built ZIP | a7 artifact SHA-256 `4b954145c39d481975812c919f07b79c2a6f3118f559962045104aae471aa28f`; tested in real Studio but predates the final Jewel adapter |
| Final Android evidence | `demo/screenshots/platform-0.7/android-device/`; APK SHA-256 `d5f1275d23dc69ad6bb42233b039c8cf1f7a34fb30009c65011c3c0284b77eda` |
| Rejected device captures | `research/diagnostics/2026-10-20/android-atd-black/` and `android-a7f3c7f/`; never present either as an accepted product screenshot |
| Research metadata | `research/results/2026-10-20/`; acquired inert source bytes remain outside the checkout at `/private/tmp/wrapperguard-research-final` |

Disk was limited to about 5 GiB free. Do not download multi-GiB IDEs/emulator images locally when CI already provides the real runtime checks. Local Docker daemon was not running; real Docker acceptance is hosted Linux CI. Android SDK is `/Users/salehalanazi/Library/Android/sdk`; no physical phone is connected. A task-only failed AVD index `wrapperguard-roadmap-api36.ini` may remain; do not delete other user AVDs, unrelated caches/daemons, personal IDE profiles or model service on port 11434. Temporary files are resumable conveniences, not the authoritative handover; source, tracked evidence and GitHub runs are authoritative. Documentation verification checked 31 Markdown files and 203 relative links with no broken targets, all 17 profile examples, 22 native evidence hashes and the accepted Android PNG hash; the VS Code evidence review also verified its six source-matched host records and gallery hashes.

For a low-disk local JVM regression, explicitly select cached `-Pwrapperguard.platformVersion=2025.3 --offline --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1280m -Pkotlin.compiler.execution.strategy=in-process`. This is a local test override only. Build the release against **2025.2**, the default. Compiling against 2025.3 can inline newer Compose APIs that fail at the advertised minimum.

For final packaging, from a clean final-source checkout after building all four packages:

```sh
python3 scripts/distribution-audit.py --output "$PWD/build/distribution-audit" --query-osv
python3 scripts/package-test-bundle.py --tag v0.7.0-rc.1 \
  --output /absolute/new/persistent/wrapperguard-0.7.0-rc.1
```

The output path must not exist. Audit hashes must match the actual four artifacts. See [verification](docs/verification.md) for builds, test commands, manual acceptance and package installation.

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

## Build, evidence and research

Use JDK 21, Node 22+ and modern Python 3.10+ with pip. [Verification](docs/verification.md) owns reproducible commands/manual tests and exact evidence. Build IDE defaults to the minimum 2025.2; verifier defaults to 2025.3; minimum platform 252; minimum/current verifier properties and actual Android Studio tests are separate. Close disposable IDEs before replacing their packages; save settings before forced process cleanup.

`buildIntegrationSdk`/`scripts/build-sdk.py` stage clean package sources, validate name/version/dependencies/module bytes and normalize archive metadata. Current artifact: `wrapperguard_sdk-0.4.0-py3-none-any.whl`. README/source changes require a rebuild. `scripts/distribution-audit.py` validates actual runtime closure, wheel/license identity and optional live OSV metadata; `scripts/package-test-bundle.py` requires clean committed source.

Actual IDEA/Studio gates, VS Code setup/automation/preset/AX/theme flows and the accepted Android 16 emulator result are recorded in galleries. Synthetic demo screenshots stay separately labeled. [Research](research/README.md) records a 77-source inert acquisition, known misses, two real models and resource measurements; no malware samples/model weights are redistributed. Task-owned model server/weights were removed after evidence collection. Do not touch the user's model services, SDKs, unrelated Gradle daemons or personal IDE profiles.

## Remaining work

[Roadmap](docs/roadmap.md) distinguishes completed engineering work from final exact-commit release checks, human accessibility/usability, physical hardware where required, signed/chosen public channels and stronger research-quality claims. Preserve those limits rather than checking boxes without evidence. Obsolete duplicated release-readiness documentation was removed; the current focused guides and historical release evidence remain useful.
