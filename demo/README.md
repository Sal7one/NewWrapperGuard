# Atlas Payments UI baseline

This local demo supplies repeatable data for reviewing WrapperGuard's interface.
Atlas Payments, Acme Supply Chain, Sentinel Policy, Registry Audit, License Check,
and Legacy Audit are fictional demo identities. The reports contain simulated
findings, provider states, approvals, and timings. They are not scanner output,
SDK integrations, security assessments, or performance measurements.

The [project](project/README.md) contains 12 inspectable text fixtures. Gradle
examples are comments, shell examples exit before their representative payload,
and there is no runnable Gradle wrapper or wrapper JAR. Hosts use `.invalid` and
the source credential is labeled `DEMO_NOT_A_REAL_SECRET`. Do not import, build,
sync, or execute the illustrative project. Generating and checking this demo
does none of those operations and makes no SDK or network calls.

## Reproduce the baseline

From the repository root, use Python 3 with its standard library:

```sh
python3 demo/generate.py
python3 demo/generate.py --check
```

Generation writes only the shared JSON artifacts below, the known project
fixtures, and the ignored `project/.idea/.name` display-name file. That small IDE
metadata file gives the native window the name **Atlas Payments · Demo** without
a Gradle import; it stays outside Git and the 12-file fixture inventory.
Generation does not modify preview code or screenshots. `--check` writes
nothing; it checks exact generated content, finding counts, active states,
provider counts and grants, build/sync action distinctions, the graph, baseline
fingerprints, and source line references.

The generated timestamp is fixed at **2026-10-19 10:50 UTC / 13:50 Riyadh**.
`projectPath` resolves to this checkout's `demo/project` directory so native
editor navigation can find the fixture files. Regenerate after moving the
checkout. File and line locations come from the fixture text rather than
manually entered offsets.

## Shared artifacts

| File | Purpose |
|---|---|
| [report.json](report.json) | Primary blocked/incomplete scenario, current `ScanReport` schema version 1 |
| [report-clean.json](report-clean.json) | Alternate complete, clean UI state with no findings; not a scan of these sample sources |
| [settings.json](settings.json) | Plain `ScanSettings` object for the preview; provider grants are in each report's `providerRuns` |
| [baseline.json](baseline.json) | One simulated user-approved `BaselineEntry`; demonstration data, never a production trust source |
| [design-manifest.json](design-manifest.json) | Expected counts, scenario names, fixture inventory, hashes, and source line counts |
| [generate.py](generate.py) | Single source for the shared fixtures and JSON, plus the read-only golden check |

The native demo reads a neighboring `settings.json` as display-only scanner settings when available. It does not write the IDE profile or grant execution permissions.

Every finding contains the full current model, including `providerId`, effective
`actions`, status, confidence, evidence, location, and call chain. Every provider
run contains its API 1 descriptor, user-granted permissions, status, duration,
finding count, and message. Notices explicitly identify the simulated scenario.

## Expected primary state

| Measure | Baseline |
|---|---:|
| Findings | 12 |
| Active findings | 10 |
| Active blockers | 3 |
| Active review warnings / alerts | 6 |
| Audit evidence | 1 |
| Suppressed findings | 1 |
| Accepted historical findings | 1 |
| Active build blockers | 2 |
| Active sync blockers | 2 |
| Execution graph files / edges | 6 / 7 |
| Fixture text files | 12 |

The three active blockers demonstrate distinct grants: WrapperGuard can block
build and sync, Acme can block build, and Sentinel can block sync. Suppressed and
accepted findings contribute to the full list but do not increase active counts.
The audit observation cannot block. The graph contains a visible sync/audit cycle.

| Provider | State | Findings |
|---|---|---:|
| WrapperGuard | Completed | 5 |
| Acme Supply Chain | Completed | 3 |
| Sentinel Policy | Completed | 4 |
| Registry Audit · demo | Optional, timed out | 0 |
| License Check · demo | Optional, failed | 0 |
| Legacy Audit · paused demo | Disabled | 0 |

`complete` is false because two optional providers did not complete. The verdict
is `EXECUTION_BLOCKED` because three active findings retain granted block actions.
`scannedFiles: 12` represents the fixture inventory in this simulated scenario;
it is not measured detector coverage. No risk score or coverage percentage is
invented.

## Preview and screenshots

Open the [screenshot gallery](index.html) to compare the captured native workspace
and VS Code renderer. The gallery is a local HTML artifact; it does not publish
the project or start SDKs. Screenshot sets and their validation manifests are
kept under `demo/screenshots/`.

The VS Code renderer harness lives in `demo/preview/`; screenshot artifacts live
in `demo/screenshots/`. Those tools consume the shared report and settings rather
than invoking scanners or SDKs. Native UI captures can load the same primary
report, preserving counts and provider provenance across both interfaces.

To use the existing VS Code renderer in a browser, compile the extension and
generate the preview from the repository root:

```sh
npm --prefix vscode-extension run compile
node demo/preview/build-vscode-preview.js
node demo/preview/serve.js
```

Open `http://127.0.0.1:4173` for the interactive renderer or
`http://127.0.0.1:4173/index.html` for the screenshot gallery.
The [preview instructions](preview/README.md) describe
the local interaction bridge and capture requirements. To regenerate its captures:

```sh
node demo/preview/capture-vscode.js
```

After updating either set of screenshots, rebuild the gallery and its SHA-256
manifest without altering image pixels:

```sh
python3 scripts/build-demo-gallery.py
```

On macOS, capture uses the system's isolated WKWebView with installed Swift/Xcode
command-line tools. It captures the generated renderer rather than a VS Code
application window. Other hosts can use an existing Playwright/browser setup;
see the preview README for `--engine chromium` and browser-path options. Neither
capture path downloads a browser automatically.

## Native workspace reproduction

The native development IDE loads the same report without importing Gradle. Set a
local tab-control file and start the explicit demo configuration from the repository
root:

```sh
printf '%s\n' summary > demo/native-control.txt
./gradlew :wrapperguard-intellij:runIde \
  -Pwrapperguard.demo=true \
  -Pwrapperguard.demoProject="$PWD/demo/project" \
  -Pwrapperguard.demoReport="$PWD/demo/report.json" \
  -Pwrapperguard.demoTab=summary \
  -Pwrapperguard.demoControl="$PWD/demo/native-control.txt" \
  -Pwrapperguard.demoWindow=1440x960
```

`demoWindow` requests a floating WrapperGuard window in `WIDTHxHEIGHT` form;
the IDE constrains it to available screen bounds. `demoTab` selects the initial
tab. The ignored control file selects `summary`, `findings`, `execution`, or
`policy` while the IDE remains open (`overview` and `graph` are aliases). For example:

```sh
printf '%s\n' findings > demo/native-control.txt
```

The UI checks that presentation-only file approximately every 250 ms. It also
writes the development IDE process ID to `demo/native-control.txt.pid`. These two
transient files are ignored by `demo/.gitignore`; they are not baseline or
permission sources.

If the IDE displays a project-trust prompt, choose **Preview in Safe Mode** and
keep Gradle import disabled. The demo needs only local text fixtures and its
prebuilt report; it does not require globally trusted projects.

On macOS, [capture-native.swift](../scripts/capture-native.swift) captures an
explicitly selected native window. Swift/Xcode command-line tools and existing
Screen Recording access for the terminal are required. In a second terminal,
list the matching windows:

```sh
demo_ide_pid="$(cat demo/native-control.txt.pid)"
swift scripts/capture-native.swift --list --pid "$demo_ide_pid" --title WrapperGuard
```

Choose the `windowId` of the floating demo workspace from that output. Replace
`12345` below with that ID; the helper captures only the matching window:

```sh
swift scripts/capture-native.swift \
  --pid "$demo_ide_pid" \
  --window 12345 \
  --title WrapperGuard \
  --output "$PWD/demo/screenshots/intellij/01-summary-dark.png"
```

Change the tab through `native-control.txt`, let it render, and repeat with a
descriptive screenshot filename. `--list` can also be used without `--pid` to
locate the demo process. Captures use the native application window; the helper
does not capture the desktop or unrelated windows.

The native gallery baseline uses `01-summary-dark.png`, `02-findings-dark.png`,
`03-execution-dark.png`, and `04-policy-dark.png` in
`demo/screenshots/intellij/`.

The explicit demo flag prevents these simulated results from authorizing build
or sync. Close this development IDE before ordinary plugin packaging or verifier
tasks. The clean report can be selected with
`-Pwrapperguard.demoReport="$PWD/demo/report-clean.json"` for a separate session;
that simulated clean state still cannot authorize execution.

Keep screenshots labeled as demo data. Baseline/permission interactions in a
preview are local simulations and must not change production user settings or
write trust entries into an actual project.
