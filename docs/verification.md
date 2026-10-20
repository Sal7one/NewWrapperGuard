# Build and acceptance verification

Checkpoint **2026-10-20**, runtime source **`76312ac`**: app **0.7.0**, SDK **0.4.0**, provider **API 1 / schema 1**. This is candidate acceptance work. The previous [0.6.0 RC1 release](https://github.com/Sal7one/NewWrapperGaurd/releases/tag/v0.6.0-rc.1) remains unchanged. No 0.7 release or final acceptance bundle exists yet. Final release metadata/checksums identify the packaged commit; earlier CI is evidence for its recorded checkpoint only. [Handover](../HANDOVER.md) has the exact branch/CI state and ordered continuation procedure.

## Continuation checkpoint

At `59daa26`, main CI and all six VS Code host jobs passed; 158 Node tests, five hardening checks, 53 integrated script tests and eight actual installed-VSIX host cases passed locally. The minimum-built native ZIP is byte-identical across the 763 and 59 builds and passed actual Studio startup, Tools and persisted paused automation. [Installed VSIX record](../demo/screenshots/flexible-provider-platform/vscode/installed-vsix-59daa26/README.md) and [Studio record](../demo/screenshots/platform-0.7/android-studio/minimum-76312ac/evidence.json) preserve their exact artifact identities.

The explicit test-framework fix lets all six cases execute on both newer native targets: actual Jewel creation and four compatibility-boundary cases pass, but the enabled-plugin assertion fails because the custom runtime omitted Gradle's `smRunner`/`testRunner` modules. The follow-up includes those modules without changing the shipped descriptor; its fresh runtime matrix must pass before release. [Raw 59 reports](../demo/screenshots/platform-0.7/intellij-verifier/59daa26/README.md).

The bundle follow-up preserves gallery paths, validates every local Markdown destination and builds its runnable CLI from the exact audited ZIP bytes. Main CI exercises the generated bundle and retains its complete matching artifact set. Download the final green-source bundle, rather than an earlier checkpoint package.

## Install and test the build

| Host | Candidate artifact | Installation |
|---|---|---|
| Android Studio / IntelliJ | `wrapperguard-intellij/build/distributions/wrapperguard-intellij-0.7.0.zip` | Settings → Plugins → gear → Install Plugin from Disk; restart |
| VS Code | `vscode-extension/wrapperguard-0.7.0.vsix` | Extensions → … → Install from VSIX; reload |
| CLI | `wrapperguard-cli/build/install/wrapperguard-cli/bin/wrapperguard-cli` | JDK 21; use `:wrapperguard-cli:installDist` |
| SDK | `build/sdk/wrapperguard_sdk-0.4.0-py3-none-any.whl` | Offline install into a dedicated trusted Python 3.10+ environment |

The bundle generator includes installers, CLI, wheel, docs, inert source fixtures, checksums and `CHECK-GATES.py`. The published 0.6 bundle is historical; generate a new 0.7 bundle only after the remaining gates pass. Verify its `SHA256SUMS` before installation. It is not a mobile WrapperGuard app: Android Studio/VS Code/CLI run on the development machine, while the separate Android acceptance app supplies a controlled device endpoint.

### Ten-minute source-analysis check

1. Open `test-fixtures/newfirstparty/` as a disposable source-only folder. It has no runnable build; do not execute its files. Trust only the controlled fixture when your external-tool setup needs Workspace Trust.
2. Open **Tools → Built-in** and scan. Six independent static groups default on. Check provider status, selected scope and findings/navigation. Build-input checks alone do not establish Kotlin/Java source-SAST coverage.
3. Install the bundled SDK using trusted Python 3.10+. Choose separately whether to download pinned Semgrep 1.181.0 / Bandit 1.9.4. Connect **SAST → Semgrep** with bundled rules and **Bandit** with its installed executable; keep warning-only SCAN grants and explicitly enable/scan.
4. Expect `wrapperguard.ktor.unrestricted-cors` and `wrapperguard.kotlin.empty-server-trust-check` in `Application.kt`, `wrapperguard.android.javascript-bridge` and `wrapperguard.android.raw-query-concatenation` in `Browser.java`, plus Bandit `B602` in `app.py` (other Bandit diagnostics may appear). Source suppression comments must not create approvals.
5. Disable one analyzer: its findings disappear while another remains independent. Re-enable/rescan. Change source: old results must become stale. Grant changes and unknown/incomplete scope must remain visible; bundled research/AI methods cannot acquire blockers. Generic custom-provider categories are metadata; their explicit grants remain independently controlled.

### Setup, automation and maintenance

- **Add SDK**: choose a copied outside-project conformance adapter, literal argv, optional declared scope and independent grants. Save disabled. Blank scope remains visibly unknown; it does not silently declare the entire project. Missing-executable edit/remove paths must remain accessible.
- **Multiple roots** in VS Code: switch selected project and verify bound profiles/navigation/history. A provider bound elsewhere is disabled there without launching.
- **Report provenance / SCA**: select a report/manifest, producer age and resolved-version requirement. Mutate input/report during analysis; coverage must become stale/incomplete. Missing identity or unresolved transitives cannot establish current full coverage.
- **HTTP audit**: use only the controlled acceptance app or your explicitly owned numeric-IP server. Cancel ownership/auth-name review: no profile/request/scan. Explicit connect/scan should produce header diagnostics. A managed SCAN-only connection must not launch on BUILD/SYNC.
- **Automation**: save change/interval settings with provider network/cost review. Save/enable/Start must produce no immediate request. A later harmless change triggers one nonoverlapping SCAN; Pause cancels/stops future runs. Trust/project/config changes pause the session.
- **Presets**: export/import through native file dialogs; inspect issuer/version/digest. Apply changes flags only, without commands, tool installation, grants or analysis.
- **History/storage**: history starts off; opt in, compare compatible completed scans, change retention and clear owned summaries. Source/messages/evidence are not persisted. Repair SDK offline; remove only unused generated environments. Active/referenced/user/legacy installations must survive.
- **UI**: use keyboard across all tabs/dialogs, Cancel/error and focus-return paths; light/dark/high-contrast themes and 300px layout. Automated AX/keyboard evidence does not replace a human screen-reader session.

### Native and cooperative gates

Use a separate disposable trusted Gradle project with harmless marker tasks. Grant the provider BUILD/SYNC plus the matching block action. Clean supported Run/Sync should execute markers; a controlled ERROR blocker should display the actual blocker dialog; choose Cancel and verify markers are absent. Test sync veto separately before project configuration. Do not build the source-analysis fixture.

VS Code gates are cooperative (`gate('BUILD')` / `gate('SYNC')`); arbitrary terminal commands are not intercepted. The CLI evaluates grants without starting a build:

```sh
wrapperguard-cli/build/install/wrapperguard-cli/bin/wrapperguard-cli \
  /absolute/test-project --providers /absolute/user/providers.json \
  --operation build --enforce --json
```

Exit 2 means active granted blockers, exit 1 invalid/incomplete input without a blocker, and exit 0 completed configured scope without blockers. INFO/audit output and bundled advisory research/AI methods cannot block; a custom provider category alone does not remove its explicitly granted authority. The bundle's `CHECK-GATES.py` exercises actual provider behavior and action clipping.

## Recorded evidence

The exact-source workflows below all use branch head `76312ace4306c906c329e408346a59d2e893f3c7`. GitHub PR checkout commits can differ from the head; retain both identities from the evidence. The overall host workflow failed its two newer native runtime tasks; passing binary verifiers do not override those failures.

| Check | Verified checkpoint | Scope |
|---|---|---|
| JVM | 234 local tests: core 96, rules 45, CLI 8, native 85 | Main [76312ac CI](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39459625691) passed, including minimum build/test/package, conformance, parity and distribution audit. Local native tests used cached 2025.3 and do not replace minimum-built acceptance |
| VS Code | 156 Node tests, five hardening checks, webview checks, eight actual local host cases | Installed VS Code 1.116; keyboard/AX/four themes/narrow/focus/cancel checks |
| Python SDK | 148 tests locally, nine Windows-only skips | Actual Windows-specific boundary checks run separately; main Python matrix 3.10/3.12/3.14 is in CI |
| Windows / official scanners | [76312ac platform run](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39459625654) passed | Five actual scanners plus Python 3.10/3.12/3.14 process/file boundaries |
| Container isolation | [76312ac Docker run](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39459625784) passed | Real no-network/privilege/resource/root-clock/SDK-kill cleanup acceptance; inert selected entries |
| VS Code host matrix | All six jobs / 48 cases passed in [76312ac host run](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39459625955) | Minimum 1.95.0 and current 1.142.0, Windows/macOS/Linux; eight real cases each. [Preserved JSON](../demo/screenshots/flexible-provider-platform/vscode/host-matrix-76312ac/acceptance-summary.json) |
| IntelliJ | All three binary verifiers passed; minimum six-case actual runtime suite passed; newer two runtime tasks failed before cases ran, at JUnit listener startup | Immediate blocker: diagnose and fix 2026.2.2 and 2026.2.3 runtime task failures, then require six actual Jewel runtime tests each. Earlier a7 endpoints were compatible: minimum eight deprecated/27 experimental/six generated internal usages; current 88 deprecated/18 experimental/no internal usages |
| Android Studio | Actual 2026.1.1 / AI-261 install 0.5→0.6/restart/removal and clean/blocked Run/Sync passed; 0.7 SDK/automation and a7 minimum ZIP exercised | Isolated profile, real markers/dialogs/Tools. Final 76312ac CI minimum-built ZIP still needs final install/smoke |
| Android device | [76312ac Android 16/API 36 emulator](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39459625818) passed | Actual unmodified clean 720×1280 PNG inspected; owned UI/foreground/no-modal gate passed; secure endpoint zero findings and weak endpoint three. APK SHA independently verified. No physical device or human visual review |
| Script regressions | 46 passed | Includes Mac zombie-group cleanup and rejection of blank/obscured device captures |
| Provider conformance | Installed JVM and actual TS bridge cases passed | Healthy/malformed/partial/deadline/cancel/action/attribution and descendant-death cases |
| Resource evaluation | Four real-project subsets, 4096 generated files, five caps/cancel checks; [76312ac Linux CI](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39459625897) passed | Latency/RSS/completion recorded; Android subset legitimately incomplete and Python native scope limited |
| Research | 77 pinned sources/two real local models; [76312ac corpus](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39459625866) and [source rules](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39459625620) passed | Misses, unsupported inputs, label bias and incorrect/injected prose retained; no population accuracy claim |
| Distribution | Runtime audit/SBOM, embedded wheel and license checks implemented | Rebuild final artifacts and rerun audit; existing local audit describes an older native package. Live advisory results are time-sensitive |

Actual captures and machine-readable evidence are in [Android Studio 0.6](../demo/screenshots/platform-0.6/android-studio/), [native/VS Code gallery](../demo/screenshots/flexible-provider-platform/), [0.7 index](../demo/screenshots/platform-0.7/README.md) and [accepted Android device record](../demo/screenshots/platform-0.7/android-device/evidence.json). The old ATD run had a black image, and the next run had a System UI ANR modal; both were rejected and retained under `research/diagnostics/2026-10-20/`. Only the final clean image is accepted. Synthetic demo renders remain separately labeled.

## Reproduce automated checks

Use JDK 21, Node 22+, Python 3.10+ with pip. Isolated SDK subprocesses need an installed SDK; `PYTHONPATH` alone is insufficient.

```sh
./gradlew build :wrapperguard-cli:installDist :wrapperguard-cli:distZip :wrapperguard-intellij:buildPlugin
./gradlew :wrapperguard-intellij:verifyPlugin
npm --prefix vscode-extension ci
npm --prefix vscode-extension test
npm --prefix vscode-extension run parity
npm --prefix vscode-extension run hardening
npm --prefix vscode-extension run test:host
npm --prefix vscode-extension run package
python3 scripts/build-sdk.py --copy-vscode
python3 -m venv /absolute/user/wg-check
/absolute/user/wg-check/bin/python -m pip install ./sdk/python 'bandit==1.9.4' 'jsonschema==4.25.1'
/absolute/user/wg-check/bin/python -m unittest discover -s sdk/python/tests -v
/absolute/user/wg-check/bin/python scripts/validate-integration-profiles.py
python3 scripts/provider-smoke.py
python3 scripts/provider-conformance.py --python /absolute/user/wg-check/bin/python
/absolute/user/wg-check/bin/python scripts/sdk-smoke.py --sdk-python /absolute/user/wg-check/bin/python
node vscode-extension/scripts/sdk-smoke.js --sdk-python /absolute/user/wg-check/bin/python
node vscode-extension/scripts/provider-conformance.js --python /absolute/user/wg-check/bin/python
/absolute/user/wg-check/bin/python -m unittest discover -s scripts/tests -v
```

Choose targets using `-Pwrapperguard.verifierVersion=2025.2`, `2026.2.2` or `2026.2.3`; the build defaults to minimum 2025.2 and the verifier defaults to 2025.3. Keep release compilation at 2025.2: compiling against a newer cached SDK can inline Compose APIs absent from the minimum runtime. A local IDE may be selected explicitly with `-Pwrapperguard.localIdePath=/absolute/IDE/Contents`. Native actual GUI acceptance is separate from binary verification. The wheel builder chooses modern Python/pip, verifies source/metadata and normalizes archive metadata so independently packaged hosts carry identical SDK bytes.

Real optional tools use `scripts/sdk-bandit-smoke.py`, `scripts/sdk-semgrep-smoke.py`, and `scripts/sdk-tool-smoke.py` with explicitly installed executables. The pinned CI downloader uses official assets and exact hashes. OSV sends dependency metadata; Trivy databases need disk. `scripts/sdk-ai-smoke.py` and `ai-evaluation.py` require an already-running explicit loopback model and download no weights. [Research commands/results](../research/README.md) and `benchmark-scans.py --help` record evaluation scope.

### Reproduce device acceptance

Build only our owned fixture with trusted Gradle8.13/JDK 21 and Android SDK 36:

```sh
gradle -p examples/android-acceptance :app:assembleDebug
python3 scripts/android-device-acceptance.py --help
```

Run the driver with explicit absolute adb/APK/SDK-Python paths, serial and new output directory as shown in [the fixture guide](../examples/android-acceptance/README.md). It installs only `com.wrapperguard.acceptance`, forwards an ephemeral host port to device loopback8765, tests secure/weak endpoints, captures UI/screenshot, removes its forward and stops its app. It does not claim a physical phone when using an emulator.

## Remaining distribution gates

The two failed newer native runtime tasks are the immediate release blocker. Both stopped before product cases at `JUnit5TestSessionListener` initialization with missing `com.intellij.util.Java11Shim`; investigate target testing-artifact/classpath alignment, as described in the [handover diagnosis](../HANDOVER.md#immediate-blocker-newer-native-test-runtime-startup). Main exact-source CI passed. A corrected native matrix, final installer reinstall, matching package audit/checksums, PR #3 merge and 0.7 candidate publication remain pending at this checkpoint. The [handover runbook](../HANDOVER.md#resume-in-this-order) records the sequence and CI artifact names. Documentation changes can alter package bytes; rebuild the final VSIX and regenerate the audit after incorporating them.

The native matrix runs the minimum-compiled Jewel factory inside each selected IDE runtime, including 2026.2.2 whose upstream bridge removed a legacy overload. Reproduce one target with:

```sh
./gradlew :wrapperguard-intellij:testJewelBridgeRuntime \
  -Pwrapperguard.bridgeRuntimeVersion=2026.2.2 \
  -Pwrapperguard.bridgeRuntimeBuild=262.10315.125
```

Each remote target requires its exact framework build: 2025.2 → 252.23892.409, 2026.2.2 → 262.10315.125, 2026.2.3 → 262.10968.63. The task records and checks resolved test-framework modules before launching. Each target must report six passing tests without skips. Exact public signatures are selected at the compatibility boundary; invocation failures stay visible instead of being retried through another factory. Binary-verifier warnings and actual-runtime results must both be retained.

After building all four packages from the clean final committed source:

```sh
python3 scripts/distribution-audit.py --output "$PWD/build/distribution-audit" --query-osv
python3 scripts/package-test-bundle.py --tag v0.7.0-rc.1 \
  --output /absolute/new/persistent/wrapperguard-0.7.0-rc.1
```

The output directory must be new. Packaging rejects dirty source or artifact hashes changed after audit. The proposed candidate tag is not a published release. Human screen-reader/independent usability review, physical hardware when required, chosen public-channel signing/publication and robust research/model-quality claims remain explicitly unperformed. [Roadmap](roadmap.md) tracks those limits. GitHub candidate publication does not imply Marketplace/PyPI publication or stable 1.0 acceptance.
