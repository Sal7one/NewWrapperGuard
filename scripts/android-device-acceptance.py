#!/usr/bin/env python3
"""Install only the owned acceptance APK, exercise its UI/HTTP audit and save device evidence.

No AVD creation, emulator wiping, project build or target guessing happens here.
Supply an explicit adb serial and an already-built debug APK. The optional SDK
argument runs the active HTTP audit after the app is started by this test driver.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import time
import urllib.request

PACKAGE = "com.wrapperguard.acceptance"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True, type=Path)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--sdk-python", type=Path)
    args = parser.parse_args()
    assert args.adb.is_absolute() and args.adb.is_file()
    assert args.apk.is_file()
    args.output.mkdir(parents=True, exist_ok=False)

    def adb(*arguments: str, binary: bool = False, timeout: int = 30):
        result = subprocess.run([str(args.adb), "-s", args.serial, *arguments], capture_output=True, timeout=timeout, check=True)
        return result.stdout if binary else result.stdout.decode("utf-8", errors="strict").strip()

    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        try:
            if adb("shell", "getprop", "sys.boot_completed", timeout=10) == "1":
                break
        except subprocess.CalledProcessError:
            pass
        time.sleep(1)
    else:
        raise RuntimeError("The explicitly selected Android device did not finish booting within 180 seconds")

    installed = False
    forwarded = None
    try:
        adb("install", "-r", "-t", str(args.apk.resolve()), timeout=60)
        installed = True
        launch = adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
        assert "Status: ok" in launch, launch
        forwarded = int(adb("forward", "tcp:0", "tcp:8765"))
        origin = "http://127.0.0.1:" + str(forwarded)
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        for attempt in range(30):
            try:
                with opener.open(origin + "/acceptance/secure", timeout=2) as response:
                    assert response.status == 200
                    assert response.headers.get("X-Content-Type-Options") == "nosniff"
                    assert "default-src 'none'" in response.headers.get("Content-Security-Policy", "")
                    assert len(response.read(1024)) < 1024
                break
            except OSError:
                if attempt == 29:
                    raise
                time.sleep(0.5)
        with opener.open(origin + "/acceptance/weak", timeout=2) as response:
            assert response.status == 200 and response.headers.get("Content-Security-Policy") is None

        sdk_results = {}
        if args.sdk_python:
            assert args.sdk_python.is_absolute()
            with tempfile.TemporaryDirectory(prefix="wg-device-audit-") as temporary:
                owned = Path(temporary).resolve()
                project = owned / "project"
                project.mkdir()
                for route in ("secure", "weak"):
                    profile = {
                        "id": "acceptance.android-http", "displayName": "Owned Android response audit", "version": "1.0.0",
                        "analysisKind": "DAST", "format": "http-audit", "allowedOperations": ["SCAN"], "requestedActions": ["WARNING"],
                        "targetScope": {"origins": [origin], "pathPrefixes": ["/acceptance"]},
                        "runner": {"tool": "http-audit", "ownershipConfirmed": True, "targetUrls": [origin + "/acceptance/" + route],
                                   "maxRequests": 1, "requestTimeoutMs": 2000, "timeoutMs": 5000, "maxResponseBytes": 4096},
                    }
                    profile_file = owned / "profile.json"
                    profile_file.write_text(json.dumps(profile), encoding="utf-8")
                    result = subprocess.run([str(args.sdk_python), "-I", "-m", "wrapperguard_sdk", "--profile", str(profile_file)],
                                            input=json.dumps({"apiVersion": 1, "projectPath": str(project), "operation": "SCAN"}) + "\n",
                                            text=True, capture_output=True, timeout=15)
                    assert result.returncode == 0, result.stderr[:500]
                    report = json.loads(result.stdout)
                    assert report["complete"], report.get("notices")
                    assert all(set(finding.get("actions", [])) <= {"WARNING"} for finding in report["findings"])
                    if route == "weak":
                        assert report["findings"], "Missing security headers on the owned Android target must be reported"
                    sdk_results[route] = {"complete": True, "findingCount": len(report["findings"]),
                                          "ruleIds": sorted({finding["ruleId"] for finding in report["findings"]})}
                assert sdk_results["weak"]["findingCount"] > sdk_results["secure"]["findingCount"]

        adb("shell", "uiautomator", "dump", "/data/local/tmp/wrapperguard-acceptance-ui.xml")
        hierarchy = adb("shell", "cat", "/data/local/tmp/wrapperguard-acceptance-ui.xml")
        assert "Device acceptance" in hierarchy and "Ready on 127.0.0.1:8765" in hierarchy
        (args.output / "device-ui.xml").write_text(hierarchy, encoding="utf-8")
        (args.output / "device.png").write_bytes(adb("exec-out", "screencap", "-p", binary=True))
        evidence = {"schemaVersion": 1, "package": PACKAGE, "serial": args.serial,
                    "deviceKind": "emulator" if args.serial.startswith("emulator-") else "physical",
                    "api": adb("shell", "getprop", "ro.build.version.sdk"),
                    "androidRelease": adb("shell", "getprop", "ro.build.version.release"),
                    "abi": adb("shell", "getprop", "ro.product.cpu.abi"),
                    "apkSha256": hashlib.sha256(args.apk.read_bytes()).hexdigest(),
                    "launchPassed": True, "loopbackHeadersPassed": True, "sdkAudit": sdk_results,
                    "sourceCommit": subprocess.run(["git", "rev-parse", "HEAD"], text=True, capture_output=True, check=True).stdout.strip()}
        (args.output / "evidence.json").write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(evidence))
    finally:
        if forwarded is not None:
            adb("forward", "--remove", "tcp:" + str(forwarded))
        if installed:
            adb("shell", "am", "force-stop", PACKAGE)


if __name__ == "__main__":
    main()
