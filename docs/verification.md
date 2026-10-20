# Build and acceptance verification

App **0.7.0**, SDK **0.4.0**, provider **API 1 / schema 1**; frozen candidate **v0.7.0-rc.1**. The user accepted a partial roadmap and stopped further development. Accepted installers come from ce1 main CI 39485233803, tested checkout **1c69fa84019eae80d6c1d3ad05c73e796d73721c**. The tag additionally records documentation/test-only changes. Release `RELEASE-MANIFEST.json` separates installer-build, bundle/documentation and tag commits; no new CI pass is implied for a documentation-only commit. [Handover](../HANDOVER.md) records completed and deferred scope.

The **acceptance bundle** supports standalone manual installation and `CHECK-GATES.py` with JDK 21/Python 3.10+. The **source build, regression suites and Android driver commands below require a full Git checkout** of the recorded source. The bundle includes fixture sources/workflows, but does not include every repository script or Git history.

## Install and test the build

| Host | Candidate artifact | Installation |
|---|---|---|
| Android Studio / IntelliJ | `wrapperguard-intellij/build/distributions/wrapperguard-intellij-0.7.0.zip` | Settings → Plugins → gear → Install Plugin from Disk; restart |
| VS Code | `vscode-extension/wrapperguard-0.7.0.vsix` | Extensions → … → Install from VSIX; reload |
| CLI | `wrapperguard-cli/build/install/wrapperguard-cli/bin/wrapperguard-cli` | JDK 21; use `:wrapperguard-cli:installDist` |
| SDK | `build/sdk/wrapperguard_sdk-0.4.0-py3-none-any.whl` | Offline install into a dedicated trusted Python 3.10+ environment |

The bundle generator includes installers, CLI, wheel, docs, inert source fixtures, checksums and `CHECK-GATES.py`. Use the bundle whose canonical release metadata and `SOURCE-COMMIT.txt` match the intended source. Verify `SHA256SUMS` and `BUNDLE-SHA256SUMS.txt` before installation. It is not a mobile WrapperGuard app: Android Studio/VS Code/CLI run on the development machine, while the separate Android acceptance app supplies a controlled device endpoint.

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

## Archived evidence and accepted release snapshot

**Archived pre-fix evidence: a97dcd1.** Check canonical 0.7 release metadata and current PR checks for closure; the following outcomes remain attached to their original source. If the intended release is already published and verified, do not republish it.

| Archived check at a97dcd1 | Result and scope |
| --- | --- |
| [Main CI 39480825937](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39480825937) | Passed JVM build/tests, SDK Python 3.10/3.12/3.14, parity, hardening, conformance, both packages, runtime audit and generated-bundle gate checks |
| Local Node / scripts | 158 Node and 53 script cases passed; seven bundle cases cover offline paths, containment and audited-CLI integrity |
| [Native matrix/raw reports](../demo/screenshots/platform-0.7/intellij-verifier/a97dcd1/README.md) | All binary verifiers compatible. Minimum2025.2 passed 6/6 actual cases; 2026.2.2 and 2026.2.3 each passed 5/6, failing the enabled-plugin assertion. No errors/skips; no waiver |
| [VS Code host matrix 39480826114](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39480826114) | Six VS Code jobs  / 48 cases passed:1.95.0 and1.142.0 on Linux/macOS/Windows. Its separate native jobs made the overall workflow red |
| [Actual a97 Studio installation](../demo/screenshots/platform-0.7/android-studio/minimum-a97dcd1/evidence.json) | Exact minimum-built ZIP passed real Studio 261 startup, Tools and persisted PAUSED automation review. Earlier build/sync/lifecycle evidence remains under the0.6 gallery |
| [Android 16/API 36 emulator](../demo/screenshots/platform-0.7/android-device/a97dcd1/README.md) | Passed owned foreground/UI and bounded HTTP audit; actual 720×1280 PNG directly inspected, APK hash independently matched; secure 0 findings, weak 3. No physical-phone or human visual review |
| [Windows / official scanners 39480825766](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39480825766) | Passed actual five-scanner integrations and Windows process/file boundaries |
| [Docker 39480826426](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39480826426) | Passed no-network/privilege/resource/timer/cleanup checks with inert selected entries |
| [Corpus 39480825819](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39480825819) / [source rules 39480826099](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39480826099) | Passed reproducible static evaluation; known misses, unsupported inputs and contextual limitations remain in the research record |
| [Resources 39480825873](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39480825873) | Passed real-project subsets, 4096 generated files, caps and cancellation; intentionally incomplete scans were not counted clean |
| Distribution review | Nested a97 bundle: 288 checksums, 28 Markdown destinations, CLI bytes/file set exactly equal audited ZIP, executable mode preserved, identical SDK wheels across hosts. Expanded artifact omitted one hidden workflow; the follow-up adds hidden upload and delivered-file roundtrip checks |

The accepted ce1 follow-up passed main CI, its hidden-file upload/download roundtrip, 348 checksum records and standalone packaged gate checks on macOS/Linux. Its exact native ZIP passed actual Studio 261 startup, Tools and session-PAUSED automation; all six VS Code jobs passed 48 cases. Android emulator acceptance passed after one rejected System UI ANR attempt. The minimum native runtime passed six cases, with zero internal usages. Release acceptance assets retain exact ZIP/VSIX/APK/image hashes, raw failures and scope; none of these results is a human accessibility or physical-phone test.

The a97 SDK archive changed only WHEEL/RECORD generator metadata from setuptools 84.2.0 to 84.2.1; runtime/rules/license/identity bytes were unchanged. This source pins setuptools 84.2.1 for reproducible final builds. Retain artifact-specific hashes rather than calling different archives byte-identical.

[Evidence index](../demo/screenshots/platform-0.7/README.md) distinguishes actual captures from synthetic demo views. Rejected black ATD and SystemUI ANR images remain in `research/diagnostics/2026-10-20/`; they are not accepted product screenshots. Automated keyboard/AX/theme/focus checks do not replace human screen-reader/usability review.

## Deferred native host validation

Both 262 targets created actual Jewel components at a97 but could not enable Gradle/WrapperGuard because `smRunner` was unresolved in product-module registration. The follow-up in this source selects IPGP 2.18.1: the [upstream 2.18 TestIdeTask fix](https://github.com/JetBrains/intellij-platform-gradle-plugin/releases/tag/2.18.0) assembles product-info bundled-plugin classpaths and resolves module aliases. A Java factory entry point inherits internal platform JVM defaults directly, avoiding Kotlin-generated forwarding overrides; content/error handling remains in Kotlin. Source-matched green matrix results and released installer evidence determine closure. The [cached 253 local preflight](../demo/screenshots/platform-0.7/intellij-verifier/local-253-ipgp2181/README.md) passed all six cases with zero failures/errors/skips; it is not minimum 252 or 262 proof.

When this deferred item is resumed, require **six actual cases with zero failures/errors/skips on each target**. Keep enabled-plugin and actual factory assertions; binary compatibility alone is insufficient. The frozen candidate does not claim this newer-host lifecycle gate is closed.

The two descriptor/component cases use the official JUnit 4 `ApplicationRule`: they initialize the real IDE application and plugin set without opening an unrelated project. The previous heavy fixture failed in a bundled Ultimate project post-startup extension before either test body ran on 262. Application-only setup preserves all assertions and actual component creation; the heavy project/build/sync tests remain unchanged. These component tests do not establish full project-lifecycle acceptance on every newer IDE.

Target frameworks are pinned and checked in both custom test configurations:

| Runtime | Exact framework build |
| --- | --- |
| 2025.2 | 252.23892.409 |
| 2026.2.2 | 262.10315.125 |
| 2026.2.3 | 262.10968.63 |

From a full checkout, reproduce a target with:

```sh
./gradlew :wrapperguard-intellij:testJewelBridgeRuntime \
  -Pwrapperguard.bridgeRuntimeVersion=2026.2.2 \
  -Pwrapperguard.bridgeRuntimeBuild=262.10315.125
```

Retain framework-resolution logs, binary-verifier warnings and actual XML results. Invocation failures remain visible; do not retry a failed public factory through a different signature or exclude failed assertions.

## Reproduce automated checks: full Git checkout

Use JDK 21, Node 22+ and Python 3.10+ with pip. The `python3` below must meet that version. Isolated SDK subprocesses need an installed SDK; `PYTHONPATH` alone is insufficient. These commands do not run from an extracted acceptance bundle.

```sh
python3 scripts/build-sdk.py --copy-vscode
./gradlew build :wrapperguard-cli:installDist :wrapperguard-cli:distZip :wrapperguard-intellij:buildPlugin
./gradlew :wrapperguard-intellij:verifyPlugin
npm --prefix vscode-extension ci
npm --prefix vscode-extension test
npm --prefix vscode-extension run parity
npm --prefix vscode-extension run hardening
npm --prefix vscode-extension run test:host
npm --prefix vscode-extension run package
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

Release compilation defaults to minimum 2025.2; verifier defaults to2025.3. Select verifier targets with `-Pwrapperguard.verifierVersion=2025.2`, `2026.2.2` or `2026.2.3`. A local override such as `-Pwrapperguard.localIdePath=/absolute/IDE/Contents` is development acceptance only. Compiling against a newer cached SDK can inline Compose APIs absent from 252. The wheel builder stages clean source, pins setuptools 84.2.1 and normalizes archive metadata; all hosts must bundle the same reviewed wheel.

Real optional tools use `scripts/sdk-bandit-smoke.py`, `scripts/sdk-semgrep-smoke.py` and `scripts/sdk-tool-smoke.py` with explicit installed executables. The pinned downloader verifies official asset hashes. OSV sends dependency metadata; Trivy databases need disk. AI smoke/evaluation requires an already-running explicit loopback model and downloads no weights. [Research](../research/README.md) records commands and limits; no population accuracy or paper-parity claim follows.

### Reproduce Android acceptance: full Git checkout

Use trusted Gradle 8.13/JDK 21 and Android SDK 36 to build only the owned fixture:

```sh
gradle -p examples/android-acceptance :app:assembleDebug
python3 scripts/android-device-acceptance.py --help
```

The driver is a repository script and records Git HEAD, so run it from a full checkout of the intended source, not solely from the extracted bundle. Follow [the fixture guide](../examples/android-acceptance/README.md) with explicit absolute adb/APK/SDK-Python paths, serial and a new output directory. If a matching owned-fixture APK is attached to release metadata, verify its hash and select it instead of rebuilding.

The driver installs only `com.wrapperguard.acceptance`, forwards an ephemeral host port to device loopback port 8765, checks secure/weak endpoints, captures owned UI/screenshot, removes its forward and stops its app. Emulator evidence does not establish a physical-phone pass; the fixture is not a mobile WrapperGuard plugin.

## Build and audit the final source

Use a clean committed full checkout and build all four packages from that exact source. Prefer final main CI's same-build `wrapperguard-distributions` artifact when local disk cannot hold the required IDEs.

```sh
python3 scripts/distribution-audit.py --output "$PWD/build/distribution-audit" --query-osv
python3 scripts/package-test-bundle.py --tag v0.7.0-rc.1 \
  --output /absolute/new/persistent/wrapperguard-0.7.0-rc.1
```

Both output directories must be new. The audit checks shipped runtime closure, licenses, source/wheel identity and matching SDKs. Optional live OSV queries send five dependency identities/versions; no advisories is not proof of no vulnerabilities. The packager captures audited artifact bytes, extracts CLI from that ZIP, validates offline Markdown destinations and rejects dirty source or changed hashes.

Final CI must pass standalone packaged `CHECK-GATES.py`, upload hidden workflow files, download the delivered artifact and verify both `SHA256SUMS` and `BUNDLE-SHA256SUMS.txt`. Artifact transport can drop loose-file executable modes; use the nested ZIP or restore the CLI launcher's mode for manual tests.

First check whether the intended canonical 0.7 release/tag is already present and verified; if so, use it rather than republishing. The user accepted this frozen partial prerelease from the recorded verified ce1 installer build. Its refreshed documentation bundle and tag include only documentation/test-fixture changes; manifest provenance distinguishes those commits from the actual artifact build. For a future full-scope release, require current PR checks, the complete native matrix, source-appropriate installer acceptance and final merged-source CI. Verify source identities, uploaded hashes and real test instructions. Human screen-reader/usability review, physical hardware, signing/public channels and research/model quality remain deferred [roadmap](roadmap.md) work. Do not resume automatically.
