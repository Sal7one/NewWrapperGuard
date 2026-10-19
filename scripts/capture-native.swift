import AppKit
import CoreGraphics
import Foundation
import ScreenCaptureKit

// Capture only an explicitly selected demo IDE window. Never captures the desktop.
let arguments = Array(CommandLine.arguments.dropFirst())
let application = NSApplication.shared
application.setActivationPolicy(.accessory)
func value(_ flag: String) -> String? {
    guard let index = arguments.firstIndex(of: flag), index + 1 < arguments.count else { return nil }
    return arguments[index + 1]
}

Task { @MainActor in
    do {
        guard CGPreflightScreenCaptureAccess() else {
            throw NSError(domain: "WrapperGuardDemo", code: 1, userInfo: [NSLocalizedDescriptionKey: "Screen capture permission is unavailable."])
        }
        let contents = try await SCShareableContent.excludingDesktopWindows(true, onScreenWindowsOnly: true)
        let selectedPid = value("--pid").flatMap(Int32.init)
        let title = value("--title") ?? "WrapperGuard"
        let windows = contents.windows.filter { window in
            let matchesPid = selectedPid.map { window.owningApplication?.processID == $0 } ?? true
            return matchesPid && (title.isEmpty || (window.title ?? "").localizedCaseInsensitiveContains(title))
                && window.frame.width >= 100 && window.frame.height >= 100
        }
        if arguments.contains("--list") {
            for window in windows {
                let output: [String: Any] = ["windowId": window.windowID,
                    "pid": window.owningApplication?.processID ?? 0, "title": window.title ?? "",
                    "width": window.frame.width, "height": window.frame.height]
                let data = try JSONSerialization.data(withJSONObject: output, options: [.sortedKeys])
                print(String(decoding: data, as: UTF8.self))
            }
            exit(0)
        }
        guard let id = value("--window").flatMap(UInt32.init),
              let destination = value("--output"),
              let window = windows.first(where: { $0.windowID == id }) else {
            throw NSError(domain: "WrapperGuardDemo", code: 2, userInfo: [NSLocalizedDescriptionKey:
                "Choose a matching demo window using --list, then --window ID --output file.png (optional --pid PID)."])
        }
        let output = URL(fileURLWithPath: destination)
        try FileManager.default.createDirectory(at: output.deletingLastPathComponent(), withIntermediateDirectories: true)
        // The system utility handles Retina scaling and window cropping directly.
        // Window ownership and title were checked above; no desktop pixels are captured.
        let capture = Process()
        capture.executableURL = URL(fileURLWithPath: "/usr/sbin/screencapture")
        capture.arguments = ["-x", "-o", "-l", String(window.windowID), output.path]
        try capture.run()
        capture.waitUntilExit()
        guard capture.terminationStatus == 0, FileManager.default.fileExists(atPath: output.path) else {
            throw NSError(domain: "WrapperGuardDemo", code: 3, userInfo: [NSLocalizedDescriptionKey: "Could not capture the selected demo window."])
        }
        print("Captured demo window \(id): \(output.path)")
        exit(0)
    } catch {
        FileHandle.standardError.write(Data((error.localizedDescription + "\n").utf8))
        exit(1)
    }
}
dispatchMain()
