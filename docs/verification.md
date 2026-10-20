# Verification record and release gates

Checkpoint **2026-10-20** for development **0.5.0**, integration SDK **0.2.0**, provider API **1**.
The source branch is `codex/optional-security-methods`, based on merged `8ed994f`.
This working-branch record replaces the artifact-specific 0.4.0 readiness document;
published release history remains in Git and [CHANGELOG.md](../CHANGELOG.md).

## Install and test the build

The local **0.5.0 test candidate** packages are built. They are not a stable release:

| Host | Package | Installation |
|---|---|---|
| Android Studio / IntelliJ IDEA | `wrapperguard-intellij/build/distributions/wrapperguard-intellij-0.5.0.zip` | Settings → Plugins → gear menu → Install Plugin from Disk; select ZIP and restart the IDE |
| VS Code | `vscode-extension/wrapperguard-0.5.0.vsix` | Extensions view → … → Install from VSIX; select file and reload VS Code |
| CLI | `wrapperguard-cli/build/install/wrapperguard-cli/bin/wrapperguard-cli` | Run the installed launcher after `:wrapperguard-cli:installDist` |

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

## Completed checks

| Area | Recorded result | What it establishes |
|---|---|---|
| Kotlin core / rules / CLI | **93 tests passed**: 50 core, 39 rules, 4 CLI | Method flags, grants, replacement, Maven, six project-boundary regressions and legacy behavior |
| Native IDE module | **48 tests passed** | Method setup, disconnect, source/dependency staleness and execution-seal regressions |
| VS Code | **79 tests passed** | GUI setup, permissions, method flags, AI input and source-change invalidation |
| Scanner parity | Fixture and whole-checkout fingerprints match | 19/19 fixture findings, 9/9 blockers; 46/46 checkout findings, 19/19 blockers; zero fingerprint differences |
| Hardening | **5 checks passed** | Bounded hostile-input scanner behavior |
| Command-provider hosts | JVM and VS Code installed SDK checks passed | Real protocol composition, user-grant clipping and independent operations |
| CLI distribution | Real provider smoke passed | Version, concurrent providers, BUILD/SYNC action independence, failure and trust boundaries |
| Python SDK | **81 tests passed** | Six report importers, controlled runners, bounded research signals and local AI boundaries |
| Real Bandit | Installed scanner check passed | Static generated input, project configuration/suppression isolation, no source execution, symlink coverage |
| Real Semgrep 1.181.0 | Kotlin/Java smoke passed | Selected Android/Ktor rules, source suppression isolation and mapping snapshot paths to project source |
| Persistent manual fixture | Real Semgrep and Bandit checks passed | Documented Kotlin/Java rule IDs and Bandit B602/B404/B607 were observed in `test-fixtures/newfirstparty` |
| Catalog / profile schemas | **15 methods, 13 profiles, 8 formats passed** | Shared metadata and documented example profiles satisfy their contracts |
| Fixed runner tests | Fake installed PowerShell/Trivy/OSV executables passed | Exact invocation, input snapshots, clean environment, report translation, timeout/overflow/error handling |
| Local AI | Loopback fake-server tests passed | Minimal payload, advisory-only output, path bounds, redirect/proxy rejection and total deadlines |

The fake executable/server tests do not establish compatibility with every upstream
scanner release or demonstrate a trained model's quality. PSScriptAnalyzer, Trivy and
OSV direct integration have automated protocol coverage; a real-tool run is a separate
check. The Semgrep smoke exercises selected Kotlin/Java rules, not all application risks.

## Final checkpoint

**301 local tests passed:** 141 JVM, 79 Node and 81 installed SDK tests, with no skips
in the final JVM run. Build and verifier completed on 2026-10-20.

The native binary verifier reports **Compatible** for IDEA **IU-253.28294.334 (2025.3)**,
with **6 deprecated** and **32 experimental** API usages. This is one verifier target,
not the complete Android Studio/IDE/OS matrix. The refreshed gallery contains **21**
labeled synthetic-demo captures: six native and fifteen VS Code renderer states.

Both IDE archives contain SDK 0.2.0 with current runner, semantics, AI modules and the
curated Android/Ktor rules; the VSIX includes the required SAX runtime. Embedded wheel
ZIP timestamps can differ while every wheel member is identical. The SDK suite was run
locally on Python 3.12 against the installed wheel; the 3.10/3.14 matrix belongs to CI.

Stable release gates and the remaining product work are tracked in [the roadmap](roadmap.md).
A successful build does not establish that every supported host, scanner or platform was
exercised.

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
or application backend is started. Local-AI tests use loopback fake HTTP servers; no
model weights are downloaded and no cloud model is contacted. Test fixtures and UI demo
reports remain synthetic and must be identified as such.

## Test candidate and artifacts

Candidate tag: `v0.5.0-rc.1`. Final candidate files are copied to
`/Users/salehalanazi/Repos/NewWrapperGaurd/build/releases/0.5.0-rc.1/`.
`WrapperGuard-0.5.0-rc.1-test-bundle.zip` includes both IDE installers, CLI, SDK wheel,
`TESTING.md`, inert `fixtures/`, `CHECK-GATES.py`, verification, roadmap and handover.
`CHECK-GATES.py` runs the packaged CLI against temporary protocol fixtures; JDK 21 and
Python 3.10+ are required. It does not build or execute project programs.

The test-candidate release and pull request remain separate from stable publication.
CI starts after the branch PR is pushed; check the actual run before stable promotion.

Built source outputs include:

- `wrapperguard-intellij/build/distributions/wrapperguard-intellij-0.5.0.zip`
- `vscode-extension/wrapperguard-0.5.0.vsix`
- `wrapperguard-cli/build/distributions/wrapperguard-cli-0.5.0.zip` and `.tar`
- `build/sdk/wrapperguard_sdk-0.2.0-py3-none-any.whl`

Final installer checksums (SHA-256):

| Artifact | SHA-256 |
|---|---|
| `wrapperguard-intellij-0.5.0.zip` | `dbc3ff251329c01998095fbdbcf8d9f5069390843db2bdea52dd7860257b82af` |
| `wrapperguard-0.5.0.vsix` | `40501911f65983de413857e8304c919fbe0ba7ec45904981d4da8146d3555378` |
| `wrapperguard-cli-0.5.0.zip` | `b895fab855e89635c2837081848cc58195cb19cc0314be094b2fe3784b96f160` |
| `wrapperguard_sdk-0.2.0-py3-none-any.whl` | `761be25591cfb8cd619e1badb92d673f0d402cb855b06ca1456f99ef12b4527d` |

Check the actual packaged host version, catalog, extension SDK declarations and bundled
wheel against source. The wheel must include the Android/Ktor rule file, current AI and
semantic modules, license metadata and no generated caches. Close the development IDE
before packaging or verification tasks that share its sandbox lock.

[Setup and coverage limits](integrations.md) · [Roadmap](roadmap.md) · [Engineering handover](../HANDOVER.md)
