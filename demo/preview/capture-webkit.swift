// macOS fallback: snapshots only an isolated WKWebView containing the generated demo.
// No desktop, other windows, source scripts, providers, or SDKs are captured/executed.
import Cocoa
import WebKit

@MainActor
final class DemoCapture: NSObject, WKNavigationDelegate {
    let web: WKWebView
    let window: NSWindow
    let html: URL
    let destination: URL
    var screenshots: [[String: Any]] = []
    var started = false

    init(html: URL, destination: URL) {
        self.html = html
        self.destination = destination
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .nonPersistent()
        self.web = WKWebView(frame: NSRect(x: 0, y: 0, width: 1440, height: 960), configuration: config)
        self.window = NSWindow(contentRect: NSRect(x: -20000, y: -20000, width: 1440, height: 960), styleMask: .borderless, backing: .buffered, defer: false)
        super.init()
        window.contentView = web
        window.isReleasedWhenClosed = false
        web.navigationDelegate = self
    }

    func start() {
        window.orderFrontRegardless()
        web.loadFileURL(html, allowingReadAccessTo: html.deletingLastPathComponent())
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        guard !started else { return }
        started = true
        Task { @MainActor in
            do { try await captureAll(); finish(0) }
            catch { fputs("Demo capture failed: \(error)\n", stderr); finish(1) }
        }
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        fputs("Demo navigation failed: \(error)\n", stderr); finish(1)
    }

    func finish(_ code: Int32) {
        window.orderOut(nil)
        exit(code)
    }

    func js(_ script: String) async throws -> Any? {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Any?, Error>) in
            web.evaluateJavaScript(script) { value, error in
                if let error { continuation.resume(throwing: error) }
                else { continuation.resume(returning: value) }
            }
        }
    }

    func pause() async throws { try await Task.sleep(nanoseconds: 180_000_000) }

    func check(_ script: String, _ message: String) async throws {
        let result = try await js(script)
        guard (result as? Bool) == true else { throw NSError(domain: "DemoCheck", code: 1, userInfo: [NSLocalizedDescriptionKey: message]) }
    }

    func resize(_ width: CGFloat, _ height: CGFloat) async throws {
        window.setContentSize(NSSize(width: width, height: height))
        web.frame = NSRect(x: 0, y: 0, width: width, height: height)
        try await pause()
    }

    func tab(_ name: String) async throws {
        _ = try await js("document.querySelector('[data-tab=\"\(name)\"]').click();document.querySelector('main').scrollTop=0;true")
        try await pause()
    }

    func screenshot(_ name: String, _ description: String) async throws {
        _ = try await js("document.getElementById('demo-toast').hidden=true;document.activeElement?.blur();true")
        try await pause()
        let config = WKSnapshotConfiguration()
        config.rect = web.bounds
        config.afterScreenUpdates = true
        let image: NSImage = try await withCheckedThrowingContinuation { continuation in
            web.takeSnapshot(with: config) { image, error in
                if let error { continuation.resume(throwing: error) }
                else if let image { continuation.resume(returning: image) }
                else { continuation.resume(throwing: NSError(domain: "DemoSnapshot", code: 1)) }
            }
        }
        guard let tiff = image.tiffRepresentation,
              let bitmap = NSBitmapImageRep(data: tiff),
              let png = bitmap.representation(using: .png, properties: [:]) else {
            throw NSError(domain: "DemoPNG", code: 1)
        }
        try png.write(to: destination.appendingPathComponent(name))
        let light = (try await js("document.body.classList.contains('vscode-light')") as? Bool) ?? false
        screenshots.append(["file": name, "description": description, "viewport": ["width": Int(web.bounds.width), "height": Int(web.bounds.height)], "theme": light ? "light" : "dark"])
        print("Captured \(name)")
    }

    func captureAll() async throws {
        try FileManager.default.createDirectory(at: destination, withIntermediateDirectories: true)
        try await check("document.documentElement.dataset.demoReady==='true'", "Demo did not become ready")
        _ = try await js("var freeze=document.createElement('style');freeze.textContent='*,*::before,*::after{animation:none!important;transition:none!important;caret-color:transparent!important}';document.head.append(freeze);true")
        try await check("Number(document.getElementById('sumBlockers').textContent)===window.__WRAPPERGUARD_DEMO_DATA__.report.findings.filter(f=>f.status==='ACTIVE'&&f.policyAction==='BLOCK').length", "Active blocker count mismatch")
        try await screenshot("01-overview-dark.png", "Fictional blocked project overview with active counts and incomplete coverage.")
        try await tab("findings")
        try await check("document.querySelectorAll('.finding-row').length===window.__WRAPPERGUARD_DEMO_DATA__.report.findings.length", "A finding status is missing from the full list")
        _ = try await js("var demoFocus=window.__WRAPPERGUARD_DEMO_DATA__.report.findings.find(f=>f.providerId==='acme.supply-chain'&&f.status==='ACTIVE'&&f.policyAction==='BLOCK')||window.__WRAPPERGUARD_DEMO_DATA__.report.findings[0];document.querySelector('.finding-row[data-fp=\"'+demoFocus.fingerprint+'\"]').click();true")
        try await screenshot("02-findings-dark.png", "Real finding list, evidence inspector, provenance and effective actions.")
        try await tab("graph")
        try await check("Array.from(document.querySelectorAll('.detached-card')).every(el=>el.closest('.graph-inspector'))", "Detached files escaped the graph inspector")
        try await screenshot("03-execution-dark.png", "Static execution graph and active blocker paths.")
        try await resize(1600, 1000)
        try await screenshot("09-execution-expanded.png", "Expanded workspace showing the graph, detached files and risky-path inspector.")
        try await resize(1440, 960)
        try await tab("policy")
        let height = (try await js("Math.min(1200,Math.max(960,document.querySelector('main').scrollHeight+160))") as? NSNumber)?.doubleValue ?? 1600
        try await resize(1440, height)
        try await screenshot("04-policy-dark.png", "Provider grants, completion, timeout, failure and disabled states.")
        try await resize(1440, 960)
        _ = try await js("window.wrapperGuardDemo.setTheme(true);true")
        try await tab("findings")
        _ = try await js("var demoFocus=window.__WRAPPERGUARD_DEMO_DATA__.report.findings.find(f=>f.providerId==='acme.supply-chain'&&f.status==='ACTIVE'&&f.policyAction==='BLOCK')||window.__WRAPPERGUARD_DEMO_DATA__.report.findings[0];document.querySelector('.finding-row[data-fp=\"'+demoFocus.fingerprint+'\"]').click();true")
        try await screenshot("05-findings-light.png", "Light theme with the same real renderer and fictional report.")
        _ = try await js("window.wrapperGuardDemo.setTheme(false);true")
        try await resize(430, 1000)
        _ = try await js("document.getElementById('detailBack').click();var demoFocus=window.__WRAPPERGUARD_DEMO_DATA__.report.findings.find(f=>f.providerId==='acme.supply-chain'&&f.status==='ACTIVE'&&f.policyAction==='BLOCK')||window.__WRAPPERGUARD_DEMO_DATA__.report.findings[0];document.querySelector('.finding-row[data-fp=\"'+demoFocus.fingerprint+'\"]').click();true")
        try await check("document.getElementById('findingDetail').scrollWidth<=document.getElementById('findingDetail').clientWidth+1", "Narrow finding detail overflows horizontally")
        try await check("(()=>{const detail=document.getElementById('findingDetail');detail.scrollTop=detail.scrollHeight;const action=document.getElementById('detailBaseline');return action&&action.getBoundingClientRect().height>0&&action.getBoundingClientRect().bottom<=detail.getBoundingClientRect().bottom+1})()", "Narrow final action cannot be reached by scrolling")
        _ = try await js("document.getElementById('findingDetail').scrollTop=0;true")
        try await screenshot("06-findings-narrow.png", "Narrow detail route and Back navigation.")
        try await resize(1440, 960)
        try await tab("findings")
        _ = try await js("var baseline=window.__WRAPPERGUARD_DEMO_DATA__.report.findings.find(f=>f.status==='SUPPRESSED');document.querySelector('.finding-row[data-fp=\"'+baseline.fingerprint+'\"]').click();true")
        try await screenshot("07-approved-baseline.png", "Approved baseline evidence stays visible without blocking execution.")
        try await check("document.getElementById('findingDetail').textContent.includes('Approved baseline')", "Baseline is mislabeled as blocking")
        _ = try await js("document.getElementById('detailUnbaseline').click();true")
        try await check("window.wrapperGuardDemo.getReport().findings.find(f=>f.fingerprint===baseline.fingerprint).status==='ACTIVE'", "Demo restore failed")
        _ = try await js("document.getElementById('detailBaseline').click();true")
        try await check("window.wrapperGuardDemo.getReport().findings.find(f=>f.fingerprint===baseline.fingerprint).status==='SUPPRESSED'", "Demo baseline approval failed")
        _ = try await js("document.getElementById('demo-reset').click();true")
        try await tab("policy")
        _ = try await js("document.querySelector('.provider-permissions').click();true")
        try await check("document.getElementById('demo-dialog').open", "Demo permission dialog failed")
        try await screenshot("08-demo-permission-controls.png", "Clearly labelled browser-only permission controls.")
        _ = try await js("document.getElementById('demo-dialog').close();document.getElementById('demo-reset').click();true")
        try await tab("findings")
        _ = try await js("document.getElementById('filterInput').value='Sentinel';document.getElementById('filterInput').dispatchEvent(new Event('input'));true")
        try await check("document.querySelectorAll('.finding-row').length>0", "Provider search failed")
        _ = try await js("document.getElementById('btnClearFilter').click();true")
        try await check("window.__WRAPPERGUARD_DEMO_ERRORS__.length===0", "Webview JavaScript errors occurred")
        let manifest: [String: Any] = ["demo": true, "project": "Atlas Payments", "source": "vscode-extension/src/webviewUi.ts", "report": "demo/report.json", "browser": "macOS WKWebView", "capture": "WKWebView.takeSnapshot of generated HTML only; no desktop capture", "checks": ["active blocker counts agree with report", "tabs and finding selection", "baseline restore/approval stay in browser", "permission dialog opens", "search and reset", "no webview script errors"], "screenshots": screenshots]
        try JSONSerialization.data(withJSONObject: manifest, options: [.prettyPrinted, .sortedKeys]).write(to: destination.appendingPathComponent("manifest.json"))
        print("Captured and verified \(screenshots.count) real-renderer demo screenshots.")
    }
}

let arguments = CommandLine.arguments
guard arguments.count == 3 else { fputs("Usage: capture-webkit <generated-vscode.html> <output-directory>\n", stderr); exit(1) }
MainActor.assumeIsolated {
    let app = NSApplication.shared
    app.setActivationPolicy(.prohibited)
    let capture = DemoCapture(html: URL(fileURLWithPath: arguments[1]), destination: URL(fileURLWithPath: arguments[2]))
    Timer.scheduledTimer(withTimeInterval: 60, repeats: false) { _ in fputs("Demo capture timed out.\n", stderr); exit(1) }
    capture.start()
    app.run()
}
