# Build and acceptance verification

Checkpoint **2026-10-20**: app **0.7.0**, SDK **0.4.0**, provider **API 1 / schema 1**. This is candidate acceptance work. The previous [0.6.0 RC1 release](https://github.com/Sal7one/NewWrapperGaurd/releases/tag/v0.6.0-rc.1) remains unchanged. Final release metadata/checksums identify the packaged commit; earlier CI is evidence for its recorded checkpoint only.

## Install and test the build

| Host | Candidate artifact | Installation |
|---|---|---|
| Android Studio / IntelliJ | `wrapperguard-intellij/build/distributions/wrapperguard-intellij-0.7.0.zip` | Settings → Plugins → gear → Install Plugin from Disk; restart |
| VS Code | `vscode-extension/wrapperguard-0.7.0.vsix` | Extensions → … → Install from VSIX; reload |
| CLI | `wrapperguard-cli/build/install/wrapperguard-cli/bin/wrapperguard-cli` | JDK 21; use `:wrapperguard-cli:installDist` |
| SDK | `build/sdk/wrapperguard_sdk-0.4.0-py3-none-any.whl` | Offline install into a dedicated trusted Python 3.10+ environment |

The packaged test bundle includes installers, CLI, wheel, docs, inert source fixtures, checksums and `CHECK-GATES.py`. Verify its `SHA256SUMS` before installation. It is not a mobile WrapperGuard app: Android Studio/VS Code/CLI run on the development machine, while the separate Android acceptance app supplies a controlled device endpoint.

### Ten-minute source-analysis check

1. Open `test-fixtures/newfirstparty/` as a disposable source-only folder. It has no runnable build; do not execute its files. Trust only the controlled fixture when your external-tool setup needs Workspace Trust.
2. Open **Tools → Built-in** and scan. Six independent static groups default on. Check provider status, selected scope and findings/navigation. Build-input checks alone do not establish Kotlin/Java source-SAST coverage.
3. Install the bundled SDK using trusted Python 3.10+. Choose separately whether to download pinned Semgrep 1.181.0 / Bandit 1.9.4. Connect **SAST → Semgrep** with bundled rules and **Bandit** with its installed executable; keep warning-only SCAN grants and explicitly enable/scan.
4. Expect `wrapperguard.ktor.unrestricted-cors` and `wrapperguard.kotlin.empty-server-trust-check` in `Application.kt`, `wrapperguard.android.javascript-bridge` and `wrapperguard.android.raw-query-concatenation` in `Browser.java`, plus Bandit `B602` in `app.py` (other Bandit diagnostics may appear). Source suppression comments must not create approvals.
5. Disable one analyzer: its findings disappear while another remains independent. Re-enable/rescan. Change source: old results must become stale. Grant changes and unknown/incomplete scope must remain visible; research/AI cannot acquire blockers.

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

Exit2 means active granted blockers, exit1 invalid/incomplete input without a blocker, exit0 completed configured scope without blockers. INFO/audit/research output cannot block. The bundle's `CHECK-GATES.py` exercises actual provider behavior and action clipping.

## Recorded evidence

| Check | Verified checkpoint | Scope |
|---|---|---|
| JVM | Core 96, rules 45, CLI 8, native 79 tests passed locally |228 tests; final CLI/package rerun records subsequent changes |
| VS Code |152 tests,5 hardening checks, webview checks,8 actual local host cases | Real installed VS Code1.116; keyboard/AX/four themes/narrow/focus/cancel |
| Python SDK |148 tests locally,9 Windows-only skips | Windows-specific boundaries run in actual Windows CI |
| Windows / official scanners | [3877542 platform run](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39452049023) passed | Actual five scanners plus Python 3.10/3.12/3.14 boundary matrix |
| Container isolation | [3877542 Docker run](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39452049100) passed | Real no-network/privilege/resource/root-clock/SDK-kill cleanup acceptance; inert selected entries |
| VS Code host matrix | All six jobs passed at [5b71265](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39450889378) | Minimum1.95.0 and current 1.142.0, Windows/macOS/Linux;8 real extension-host cases each |
| IntelliJ | Prior development2025.3 verifier passed; minimum/current matrix underway | Initial matrix jobs exhausted hosted disk; reserved disk before rerun, no fabricated report |
| Android Studio | Actual2026.1.1 / AI261 install0.5→0.6/restart/removal, clean/blocked Run/Sync passed | Separate isolated profile, real marker checks and dialogs; new0.7 SDK/automation acceptance also exercised |
| Device | [Android16/API36 emulator passed](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39450889177) | Launch, UI/screenshot, owned loopback server, SDK secure0 findings/weak3 findings; physical phone not connected |
| Provider conformance | Installed JVM and actual TS bridge cases passed | Healthy/malformed/partial/deadline/cancel/action/attribution cases and descendant death |
| Resource evaluation | Four real-project subsets plus4096 generated files; five caps/cancel checks passed | Latency/RSS/completion recorded; Android subset legitimately incomplete, Python native scope limited |
| Research |77 pinned sources and two real local model runs | Misses, unsupported inputs, label bias and incorrect/injected model prose retained; no population accuracy claim |
| Distribution | Runtime audit/SBOM and embedded wheel/license checks | Current final artifacts must pass after build; live advisory databases are time-sensitive |

Actual captures and machine-readable evidence are in [Android Studio0.6](../demo/screenshots/platform-0.6/android-studio/), [native/VS Code gallery](../demo/screenshots/flexible-provider-platform/) and [Android device](../demo/screenshots/platform-0.7/android-device/evidence.json). Synthetic demo renders remain separately labeled. A CI PR merge commit can differ from its branch head; device JSON records both identities.

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

Choose minimum/current targets using `-Pwrapperguard.verifierVersion=2025.2` or `2026.2.3`; the build defaults to minimum 2025.2; verifier defaults to 2025.3. A local IDE may be selected explicitly with `-Pwrapperguard.localIdePath=/absolute/IDE/Contents`. Native actual GUI acceptance is separate from binary verification. The wheel builder chooses modern Python/pip, verifies source/metadata and normalizes archive metadata so independently packaged hosts carry identical SDK bytes.

Real optional tools use `scripts/sdk-bandit-smoke.py`, `scripts/sdk-semgrep-smoke.py`, and `scripts/sdk-tool-smoke.py` with explicitly installed executables. The pinned CI downloader uses official assets and exact hashes. OSV sends dependency metadata; Trivy databases need disk. `scripts/sdk-ai-smoke.py` and `ai-evaluation.py` require an already-running explicit loopback model and download no weights. [Research commands/results](../research/README.md) and `benchmark-scans.py --help` record evaluation scope.

### Reproduce device acceptance

Build only our owned fixture with trusted Gradle8.13/JDK 21 and Android SDK 36:

```sh
gradle -p examples/android-acceptance :app:assembleDebug
python3 scripts/android-device-acceptance.py --help
```

Run the driver with explicit absolute adb/APK/SDK-Python paths, serial and new output directory as shown in [the fixture guide](../examples/android-acceptance/README.md). It installs only `com.wrapperguard.acceptance`, forwards an ephemeral host port to device loopback8765, tests secure/weak endpoints, captures UI/screenshot, removes its forward and stops its app. It does not claim a physical phone when using an emulator.

## Remaining distribution gates

Exact final-commit CI and candidate checksums, final installer reinstall, minimum/current verifier reports and signed/chosen public-channel distribution remain separate gates. Human screen-reader/independent usability review, physical hardware when required and robust research/model-quality claims remain explicitly unperformed. [Roadmap](roadmap.md) tracks those limits. GitHub candidate publication does not imply Marketplace/PyPI publication or stable1.0 acceptance.
