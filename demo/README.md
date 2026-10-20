# Atlas Payments UI demo

Atlas Payments and its provider identities are fictional. The shared reports contain
simulated findings, permissions, approvals, timings and failures for repeatable interface
review. They are not real scanner output, performance measurements or security assessments.

The [sample project](project/README.md) supplies 12 inspectable text fixtures. It has no
runnable Gradle wrapper/JAR; build examples are comments and shell examples exit before
their illustrative payload. Hosts use `.invalid` and the dummy credential is labeled
`DEMO_NOT_A_REAL_SECRET`. Keep the fixture untrusted and do not import, build or execute it.

## Reproduce the data

From the repository root:

```sh
python3 demo/generate.py
python3 demo/generate.py --check
```

The generator writes shared reports/settings, the known fixture files and an ignored IDE
display-name file. `--check` is read-only. It verifies generated content, findings, grants,
call paths, baseline fingerprints and source references without running scanners or SDKs.
Regenerate after moving the checkout so project-local editor navigation uses the new path.
The fixed demo timestamp is 2026-10-19 10:50 UTC / 13:50 Riyadh.

| Artifact | Purpose |
|---|---|
| [report.json](report.json) | Blocked/incomplete primary scenario, report schema 1 |
| [report-clean.json](report-clean.json) | Alternate simulated clean state; not a scan of the sample sources |
| [settings.json](settings.json) | Display-only scanner/method settings |
| [baseline.json](baseline.json) | Simulated user approval, never production trust data |
| [design-manifest.json](design-manifest.json) | Expected inventory, counts, hashes and source references |
| [generate.py](generate.py) | Generator and read-only consistency check |

The primary scenario has 12 findings, 10 active findings, 3 active blockers, 6 active review
warnings/alerts and 1 audit observation. It includes suppressed and accepted examples,
a graph with 6 files / 7 edges, and 6 fictional provider runs. Two optional failures make
coverage incomplete; granted blockers keep the verdict blocked. These counts illustrate
UI state and are not measured detection coverage. No risk score or coverage percentage is
invented.

## Interactive renderer and screenshots

The [gallery](index.html) links native IDE captures and browser captures of the actual
compiled VS Code renderer. [Screenshot metadata](screenshots/manifest.json) records image
hashes and source provenance. New captures should include Tools and Research as well as
the existing Summary, Findings, Execution and Policy states.

```sh
npm --prefix vscode-extension run compile
node demo/preview/build-vscode-preview.js
node demo/preview/serve.js
```

Open `http://127.0.0.1:4173` for the renderer and `/index.html` for the gallery.
The preview's host bridge simulates setup, switches, permissions and disconnects in memory.
It cannot install tools, connect a model or write production settings. Native demo controls
are read-only for method setup; the report cannot authorize build or synchronization.

To share this demo on a LAN, choose this computer's actual network address:

```sh
node demo/preview/serve.js 4173 192.168.1.20
```

The server remains loopback-only by default and serves only the explicit demo allowlist.
No repository-wide file browsing or production service is exposed.

Capture and rebuild provenance after renderer/UI changes:

```sh
node demo/preview/capture-vscode.js
python3 scripts/build-demo-gallery.py
```

The [preview guide](preview/README.md) explains browser engines, interaction checks and
custom report/settings inputs. Do not present old image hashes as a new validation run.

## Native IDE capture

Use the development IDE's explicit demo mode to load the same report without a Gradle import:

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

The ignored control file selects `summary`, `findings`, `execution`, `policy`, `tools` or
`research`; `overview` and `graph` are aliases. For example:

```sh
printf '%s\n' tools > demo/native-control.txt
```

The UI polls that presentation-only file and records the demo IDE PID in
`demo/native-control.txt.pid`. These files are not configuration or trust sources.
If a project-trust prompt appears, use **Preview in Safe Mode** and keep Gradle import off.

On macOS, the capture helper needs installed Swift/Xcode command-line tools and existing
Screen Recording access. Select the specific floating WrapperGuard window:

```sh
demo_ide_pid="$(cat demo/native-control.txt.pid)"
swift scripts/capture-native.swift --list --pid "$demo_ide_pid" --title WrapperGuard
```

Replace `12345` with the listed window ID:

```sh
swift scripts/capture-native.swift --pid "$demo_ide_pid" --window 12345 \
  --title WrapperGuard --output "$PWD/demo/screenshots/intellij/05-tools-dark.png"
```

Capture each state after it renders and rebuild the gallery manifest. The helper captures
only the selected application window. Close the development IDE before packaging/verifier
tasks that share its sandbox. Keep all captures labeled as demo data.
