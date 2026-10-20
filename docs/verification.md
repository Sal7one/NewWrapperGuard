# Verification record and release gates

Checkpoint **2026-10-20**, candidate **0.6.0**, SDK **0.3.0**, provider **API 1**.
Branch: `codex/flexible-provider-platform`, based on `8336a61` (0.5 RC1).
Candidate builds and test evidence do not imply stable-release acceptance or complete
security coverage. Prior RC1 assets and its [green CI](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39416921098)
remain historical evidence, not validation of this candidate.

## Install and test the build

| Host | Candidate package | Installation |
|---|---|---|
| Android Studio / IntelliJ IDEA | `wrapperguard-intellij/build/distributions/wrapperguard-intellij-0.6.0.zip` | Settings → Plugins → gear → Install Plugin from Disk; restart |
| VS Code | `vscode-extension/wrapperguard-0.6.0.vsix` | Extensions → … → Install from VSIX; reload |
| CLI | `wrapperguard-cli/build/install/wrapperguard-cli/bin/wrapperguard-cli` | Run after `:wrapperguard-cli:installDist` |
| SDK | `build/sdk/wrapperguard_sdk-0.3.0-py3-none-any.whl` | Install offline in a dedicated trusted Python 3.10+ environment |

A useful first manual pass takes about ten minutes after scanner installation:

1. Open [test-fixtures/newfirstparty](../test-fixtures/newfirstparty/README.md) as a folder,
   or copy it into a fresh test directory. It has no build file, wrapper or application
   startup. Trust only this controlled fixture when external analyzer setup requires
   workspace trust. Do not build or execute the fixture.
2. Open WrapperGuard → **Tools → Built-in**. Confirm six independent checks are present
   and on by default. Scan, inspect provider status, and confirm the selected scope is
   visible. These build/script checks alone are not source-SAST coverage of Kotlin/Java.
3. Choose **Install SDK…**, select trusted Python 3.10+, and explicitly select Semgrep
   1.181.0 and/or Bandit 1.9.4 downloads. Under **SAST**, connect the installed Semgrep using
   bundled rules; connect Bandit for Python. Review the setup summary, keep warning-only
   SCAN permissions, then enable and scan.
4. Expect `wrapperguard.ktor.unrestricted-cors` and
   `wrapperguard.kotlin.empty-server-trust-check` in `Application.kt`, plus
   `wrapperguard.android.javascript-bridge` and
   `wrapperguard.android.raw-query-concatenation` in `Browser.java`. Bandit should report
   `B602` in `app.py` (Bandit may also report `B404` and `B607`). The `nosemgrep` /
   `nosec` comments must not remove these findings.
   Select a finding and confirm editor navigation resolves to the fixture source.
5. Disable Semgrep and rescan: its findings disappear, Bandit remains independent, and
   disabled/limited scope stays visible. Re-enable and rescan to restore its findings.
   Review **Permissions**: warning-only connections must not receive build/sync block
   actions in exported JSON. Research/AI cannot acquire blocking actions. Disconnecting
   one method must preserve another connection and the six independent built-in switches.
   In the native IDE, open **Configure… → Disconnect**; VS Code exposes **Disconnect**
   directly. Edit and save a fixture source: the previous result must become stale.
   Rescan before treating the displayed result as current.

For a gate check, use a separate trusted disposable Gradle project with a known supported
IDE run/sync path. Through the connection's permission UI, explicitly allow BUILD/SYNC
and the matching block action, then use an ERROR-level test finding. A supported native
launch should stop at the blocker dialog; choose Cancel. Test Gradle synchronization
separately with Block sync enabled: it should stop before supported resolution begins.
Do not use the illustrative source fixture as a runnable build. Warning-only grants should
continue to show review findings without blocking. A failed required provider can also
block when its matching action is granted.

VS Code has cooperative gates: use an integration that calls `gate('BUILD')` /
`gate('SYNC')`, or the installed CLI operation tests below. An arbitrary terminal
command is not intercepted. CLI `--operation build|sync` evaluates authority without
starting a build or synchronization:

```sh
wrapperguard-cli/build/install/wrapperguard-cli/bin/wrapperguard-cli \
  /absolute/test-project --providers /absolute/user/providers.json \
  --operation build --enforce --json
```

Expect exit **2** only for active granted blockers, **1** for invalid/incomplete input
without blockers, and **0** for completed scope without blockers. The automated provider
smoke below exercises warning-only clipping and independent build/sync actions without
executing project programs.

Additional platform acceptance:

1. **Tools → Add SDK / custom command**: use a copied outside-project sample adapter.
   Choose a category/tags, literal argv rows and declared source inputs. Save disabled;
   verify its independent warning-only grants, then explicitly enable/scan. Edit/remove
   must remain available after its executable is missing. Do not execute source fixtures.
2. Open two VS Code roots, select each project and scan. A root-bound connection must
   be disabled on the other project; findings/navigation/history must use the selected root.
3. Preview a preset. Applying it changes flags only: no downloads, execution, command
   enablement or grant changes. A newly selected method without a connected provider
   must remain incomplete. Choose Semgrep packs explicitly and exercise the new cases
   in [newfirstparty](../test-fixtures/newfirstparty/README.md).
4. History starts off with no summary writes. Opt in, scan twice, and compare compatible
   scans. Baseline approval must not count as disappearance. A rule/permission/operation
   change or incomplete/stale scan must prevent comparison. Pause, change retention and
   clear owned summaries. Titles/relative paths are stored; source/messages/evidence are not.
5. Inventory managed storage. Remove only selected unused generated data; referenced or
   active environments, original reports/configuration and unrelated files must survive.
6. Edit a declared source, unusual recursively read file, selected external rule/profile
   or report during analysis. Old results must be stale/incomplete and cannot authorize
   a supported gate from a mismatched content seal.

## Automated evidence

| Check | Result at this source checkpoint | Meaning |
|---|---|---|
| JVM build and packages | 200 tests: core 87, rules 40, CLI 8, native 65; zero failures/skips | Shared grants, scopes, actual-read seals, legacy descriptor ABI, custom GUI/storage/history |
| VS Code | 122 tests and emitted-webview checks passed | Multi-root, generic setup, presets, scope seals, history, cleanup and UI message validation |
| Python installed SDK wheel | 118 tests, 6 real-Windows skips before remote CI | Eight passive formats, runners, research, structured AI and isolated experimental helpers |
| Downloader | 4 trust/archive regressions passed | Official asset/hash boundaries and bounded no-traversal extraction |
| Catalog/profile contracts | 17 methods, 15 profiles, 10 format IDs passed | Examples cover every format; host examples remain disabled |
| Scanner parity | 19/19 fixture findings, 9/9 blockers; 46/46 checkout findings, 19/19 blockers; no fingerprint differences | Existing scanner behavior remains aligned |
| IntelliJ verifier | Compatible with IDEA 2025.3 / IU-253.28294.334 | Binary compatibility for this target; 8 deprecated / 32 experimental API usages remain |
| Real Semgrep 1.181.0 | All five selected packs passed generated positive/negative Kotlin/Java/manifest fixtures | Local configs, suppression isolation and source mapping, not accuracy evaluation |
| Real PSScriptAnalyzer 1.25.0 / PowerShell 7.6.7 | Passed; fixed required runtime-module import | Actual analyzer, fixed script and mapped diagnostics |
| Real OSV-Scanner 2.7.0 | Passed; vulnerable inert npm lock fixture produced findings | Actual online package-advisory integration; database results can change |
| Real local model | Ollama + SmolLM2:135m structured schema passed, 1 advisory finding, ~0.7 s | Real protocol compatibility; does not measure explanation quality/detection accuracy |
| Local Trivy 0.76.0 | Database download could not finish: insufficient local disk | Not a pass; roomier Linux CI runs the real check |
| Windows source runners | Remain disabled; 6 real Windows handle tests run in CI | Helpers are experimental until platform acceptance; imports/model review remain supported |

Normal-host workflow acceptance and candidate CI results are recorded below when complete.
Do not substitute renderer captures or fake scanner protocol tests for real host/tool evidence.

## Reproduce the checks

Use JDK 21, Node 22+, and Python 3.10+ with pip. The installed SDK is required for tests
that launch isolated `python -I` subprocesses; setting only `PYTHONPATH` is insufficient.
Build from the repository root:

```sh
./gradlew build :wrapperguard-cli:installDist :wrapperguard-intellij:buildPlugin
./gradlew :wrapperguard-intellij:verifyPlugin
npm --prefix vscode-extension ci
npm --prefix vscode-extension test
npm --prefix vscode-extension run parity
npm --prefix vscode-extension run hardening
npm --prefix vscode-extension run package
```

The packaging tasks build the bundled wheel. To build/copy it explicitly:

```sh
python3 scripts/build-sdk.py --copy-vscode
```

The helper selects Python 3.10+ with pip, stages clean sources, and checks package name,
version, dependency metadata and bundled module bytes. Set `WRAPPERGUARD_BUILD_PYTHON`
to the intended build interpreter when needed. Installing the finished SDK wheel in the
GUI is offline; building a wheel may need the declared build backend from package indexes.

Create a verification environment outside scanned projects:

```sh
python3 -m venv /absolute/user/wg-verification
/absolute/user/wg-verification/bin/python -m pip install ./sdk/python \
  'bandit==1.9.4' 'jsonschema==4.25.1'
/absolute/user/wg-verification/bin/python -m unittest discover -s sdk/python/tests -v
/absolute/user/wg-verification/bin/python scripts/validate-integration-profiles.py
/absolute/user/wg-verification/bin/python scripts/provider-smoke.py
/absolute/user/wg-verification/bin/python scripts/sdk-smoke.py \
  --sdk-python /absolute/user/wg-verification/bin/python
node vscode-extension/scripts/sdk-smoke.js \
  --sdk-python /absolute/user/wg-verification/bin/python
/absolute/user/wg-verification/bin/python scripts/sdk-bandit-smoke.py \
  --sdk-python /absolute/user/wg-verification/bin/python \
  --bandit /absolute/user/wg-verification/bin/bandit
```

For the optional real source-SAST check, use an explicitly installed Semgrep 1.181.0:

```sh
/absolute/user/wg-verification/bin/python scripts/sdk-semgrep-smoke.py \
  --sdk-python /absolute/user/wg-verification/bin/python \
  --semgrep /absolute/installed/semgrep
```

The Semgrep check runs trusted scanner code on generated source data. No project build
or application backend is started. Automated local-AI regressions use loopback fake servers. A separate real-model smoke
requires an already installed local server/model and downloads neither. Test fixtures and UI demo
reports remain synthetic and must be identified as such.

Additional real tools use explicitly installed executables:

```sh
python scripts/sdk-tool-smoke.py --sdk-python /absolute/sdk/bin/python \
  --tool psscriptanalyzer --executable /absolute/pwsh \
  --module /absolute/PSScriptAnalyzer/PSScriptAnalyzer.psd1
python scripts/sdk-tool-smoke.py --sdk-python /absolute/sdk/bin/python \
  --tool osv --executable /absolute/osv-scanner
python scripts/sdk-tool-smoke.py --sdk-python /absolute/sdk/bin/python \
  --tool trivy --executable /absolute/trivy --cache /absolute/user/trivy-cache
python scripts/sdk-ai-smoke.py --sdk-python /absolute/sdk/bin/python \
  --endpoint http://127.0.0.1:11434/v1 --model YOUR_INSTALLED_MODEL
```

The optional Linux CI downloader uses pinned official GitHub release assets and SHA-256
values in `scripts/upstream-tools.lock.json`; it does not install product defaults.
Trivy requires substantial free space for vulnerability databases. OSV sends fixture package
metadata to its vulnerability service. The model smoke requires your own already-running
loopback server; it does not pull weights or contact a cloud model.

## Candidate acceptance and distribution

Candidate source/CI, actual host acceptance, artifact hashes and remaining gates are
updated as evidence is produced. The older
[0.5 RC1](https://github.com/Sal7one/NewWrapperGaurd/releases/tag/v0.5.0-rc.1)
remains available separately. Marketplace/PyPI publication, stable release approval,
broader host/OS accessibility/performance matrices, active DAST and research evaluation
remain in [the roadmap](roadmap.md).

## Editor evidence

[0.6 screenshots and acceptance evidence](../demo/screenshots/platform-0.6/README.md) distinguish actual isolated VS Code setup/execution/project-binding/history from synthetic rendered examples. Native acceptance and its registration regression are recorded separately; renderer captures do not prove BUILD/SYNC vetoes.
