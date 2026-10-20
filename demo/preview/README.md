# VS Code renderer demo

This harness runs the actual compiled `vscode-extension/src/webviewUi.ts` renderer in a
standalone browser. The **SIMULATED DEMO** ribbon and host bridge are demo utilities.
Findings, providers, setup, permissions and model responses are fictional; no SDK, scanner,
source program or real model server runs.

From the repository root:

```sh
npm --prefix vscode-extension run compile
node demo/preview/build-vscode-preview.js
node demo/preview/serve.js
```

Open `http://127.0.0.1:4173`, or open generated `vscode.html` directly. Query parameters
such as `?theme=light&tab=tools` choose a theme/tab. The renderer includes Tools and
Research & AI alongside the existing report, graph and policy views.

Use the preview to inspect findings, search, navigate call paths, simulate baseline
approval/restore, change method switches, open setup/permissions, disconnect demo tools,
export fictional reports and reset state. Every change stays in the current browser page.
The simulated setup never downloads an analyzer or writes production connection files.

To expose the allowlisted demo to devices on the same LAN, pass this computer's network IP:

```sh
node demo/preview/serve.js 4173 192.168.1.20
```

The default is loopback-only. `/index.html` opens the generated screenshot gallery.
Only the explicit demo pages, README files, screenshot manifest and PNG paths are served;
repository files outside that allowlist remain inaccessible.

## Capture and verify

```sh
node demo/preview/capture-vscode.js
python3 scripts/build-demo-gallery.py
```

The capture harness checks report counts, tab navigation, finding selection/search,
simulated baseline and permission actions, method setup/disable/disconnect, advisory AI
state, narrow layouts and emitted-script errors. Image files and a capture manifest go
under `demo/screenshots/vscode/`; the gallery combines them with native IDE captures.
These are browser renderer captures, not screenshots of an installed VS Code application.

On macOS the default engine compiles a small Swift helper and uses isolated
`WKWebView.takeSnapshot`. Swift/Xcode command-line tools must already be installed.
It does not capture the desktop or other applications and does not download a browser.

`--engine chromium` uses an existing Playwright installation and browser. Set
`PLAYWRIGHT_MODULE_PATH` and `DEMO_BROWSER_EXECUTABLE` when automatic discovery cannot find
them. Other platforms use that Chromium path; install/select the browser separately.

Both build and capture scripts accept `--report <ScanReport.json>` and
`--settings <ScanSettings.json>`. Defaults are `demo/report.json` and `demo/settings.json`.
Capture accepts `--out <directory>`. Regenerate source data after moving the checkout,
and regenerate captures/manifests after UI changes; see [the demo guide](../README.md).
