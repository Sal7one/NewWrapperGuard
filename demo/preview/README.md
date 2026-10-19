# VS Code webview demo

This is the actual compiled `vscode-extension/src/webviewUi.ts` renderer running in a standalone browser. The surrounding **SIMULATED DEMO** ribbon and host bridge are demo utilities. Providers, findings, timings and permission changes are fictional. Scripts and SDKs never run. No production UI styling is replaced.

From the repository root:

```sh
cd vscode-extension
npm run compile
cd ..
node demo/preview/build-vscode-preview.js
node demo/preview/serve.js
```

Open `http://127.0.0.1:4173`. The generated `vscode.html` also opens directly as a local file. `?theme=light&tab=findings` selects a theme and tab. Choose findings, search, inspect the execution graph, simulate baseline approval/restore, change demo permissions, export fictional JSON, or reset the demo. All changes stay in the current browser page.

To share the demo on your LAN, pass a port and this computer's LAN IP as positional arguments. For example, `node demo/preview/serve.js 4173 192.168.1.20` serves the interactive demo at `http://192.168.1.20:4173` and the gallery at `/index.html`. Open that address from another device on the same network. The default remains loopback-only; the demo file allowlist also applies to LAN serving.

`http://127.0.0.1:4173/index.html` opens the screenshot gallery when `demo/index.html` has been generated. Gallery screenshot links and the demo/preview README links are served by the same server. Only the explicit demo pages, README files and PNG screenshot paths are served; repository files outside this allowlist remain inaccessible.

To regenerate screenshots:

```sh
node demo/preview/capture-vscode.js
```

On macOS this compiles a small Swift helper and uses the system's isolated `WKWebView.takeSnapshot` API. It captures the generated demo view only, without capturing the desktop or other windows. No browser download or extra package is needed. Swift/Xcode command line tools must already be installed.

`--engine chromium` uses an existing Playwright package and cached Chrome/Chromium instead. If necessary set `PLAYWRIGHT_MODULE_PATH` to an installed Playwright module and `DEMO_BROWSER_EXECUTABLE` to an existing browser executable. The Codex bundled Playwright runtime and Puppeteer browser cache are discovered automatically. Cached Chrome121 crashes on this macOS26 machine, so WebKit is the working default. Other platforms default to the Chromium path.

`build-vscode-preview.js` and `capture-vscode.js` accept `--report <ScanReport.json>` and `--settings <ScanSettings.json>`. The default report and settings come from `demo/report.json` and `demo/settings.json`. Capture writes PNGs and a validation manifest under `demo/screenshots/vscode/`; `--out <directory>` selects another screenshot destination.

The screenshot capture validates active counts, tabs, selection, search, browser-only baseline changes and permission dialogs, and fails if browser/webview script errors occur. Screenshots are browser captures of the generated demo, not desktop captures or screenshots of an installed VS Code host.

The nine captures cover Overview, Findings, Execution, the complete provider Policy list, light theme, narrow detail, the approved baseline, demo permission controls, and an expanded execution graph with its risky-path inspector. The desktop captures use a 1440×960 viewport; the expanded graph uses 1600×1000. The capture checks every finding status is represented, detached files stay inside the graph inspector, and narrow details can scroll to the last action without horizontal overflow.
