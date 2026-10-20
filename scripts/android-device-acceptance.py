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
import re
import struct
import subprocess
import tempfile
import time
import urllib.request
import xml.etree.ElementTree as ET
import zlib

PACKAGE = "com.wrapperguard.acceptance"


def screenshot_metrics(png: bytes) -> dict:
    """Bounded RGB/RGBA PNG check for a rendered activity, not semantic visual approval.

    Inspect only the central display area so status/navigation icons cannot make a black
    activity pass. Android screencap emits non-interlaced8-bit RGB/RGBA. Implement all five
    PNG row filters with stdlib; reject unsupported encodings instead of guessing.
    """
    if not isinstance(png,bytes) or not 45 <= len(png) <= 16*1024*1024 or png[:8] != b'\x89PNG\r\n\x1a\n':
        raise ValueError('Invalid device screenshot PNG')
    offset = 8; header = None; compressed = bytearray(); ended = False; chunks = 0
    while offset < len(png):
        if offset + 12 > len(png): raise ValueError('Truncated screenshot PNG')
        size = struct.unpack_from('>I',png,offset)[0]
        kind = png[offset+4:offset+8]; last = offset + 12 + size
        if last > len(png) or chunks >= 1000: raise ValueError('Screenshot PNG chunk limit')
        data = png[offset+8:last-4]
        if zlib.crc32(kind+data) & 0xffffffff != struct.unpack_from('>I',png,last-4)[0]:
            raise ValueError('Screenshot PNG checksum mismatch')
        if chunks == 0 and kind != b'IHDR': raise ValueError('Screenshot PNG header is missing')
        if kind == b'IHDR':
            if header is not None or size != 13: raise ValueError('Invalid screenshot PNG header')
            header = struct.unpack('>IIBBBBB',data)
        elif kind == b'IDAT': compressed.extend(data)
        elif kind == b'IEND':
            if size != 0 or last != len(png): raise ValueError('Invalid screenshot PNG ending')
            ended = True; break
        elif kind not in (b'PLTE',) and kind[:1].isupper():
            raise ValueError('Unsupported screenshot PNG chunk')
        offset = last; chunks += 1
    if not ended or header is None or not compressed: raise ValueError('Incomplete screenshot PNG')
    width,height,bits,color,compression,filtering,interlace = header
    if (min(width,height) < 240 or max(width,height) < 320 or max(width,height) > 8192 or width*height > 16_000_000
            or bits != 8 or color not in (2,6) or compression != 0 or filtering != 0 or interlace != 0):
        raise ValueError('Unsupported or oversized device screenshot')
    channels = 4 if color == 6 else 3
    row_bytes = width*channels; expected = (row_bytes+1)*height
    decoder = zlib.decompressobj()
    try: raw = decoder.decompress(compressed,expected+1)
    except zlib.error as error: raise ValueError('Invalid screenshot PNG compression') from error
    if len(raw) != expected or not decoder.eof or decoder.unconsumed_tail or decoder.unused_data:
        raise ValueError('Screenshot PNG expanded budget or stream mismatch')
    previous = bytearray(row_bytes); histogram = [0]*256; sampled = transparent = 0
    for y in range(height):
        start = y*(row_bytes+1); method = raw[start]
        row = bytearray(raw[start+1:start+1+row_bytes])
        if method not in (0,1,2,3,4): raise ValueError('Unsupported screenshot PNG filter')
        if method:
            for x in range(row_bytes):
                left = row[x-channels] if x >= channels else 0
                above = previous[x]
                if method == 1: predictor = left
                elif method == 2: predictor = above
                elif method == 3: predictor = (left+above)//2
                else:
                    corner = previous[x-channels] if x >= channels else 0
                    p = left+above-corner
                    a,b,c = abs(p-left),abs(p-above),abs(p-corner)
                    predictor = left if a <= b and a <= c else above if b <= c else corner
                row[x] = (row[x]+predictor) & 255
        if height//10 <= y < height*9//10 and y % 4 == 0:
            for x in range(width//20,width*19//20,4):
                pixel = x*channels; sampled += 1
                if channels == 4 and row[pixel+3] < 250: transparent += 1
                histogram[(row[pixel]+row[pixel+1]+row[pixel+2])//3] += 1
        previous = row
    nonzero = [index for index,count in enumerate(histogram) if count]
    minority = sampled - max(histogram)
    if (sampled == 0 or transparent > sampled//100 or len(nonzero) < 2
            or nonzero[-1]-nonzero[0] < 32 or minority/sampled < .002):
        raise ValueError('Device screenshot is blank or lacks rendered activity content')
    return {'width':width,'height':height,'sha256':hashlib.sha256(png).hexdigest(),
            'sampledCentralPixels':sampled,'nonDominantPixelFraction':round(minority/sampled,6),
            'luminanceRange':nonzero[-1]-nonzero[0], 'nonBlankPassed':True,
            'method':'Central rendered-content variation; not semantic image review', 'humanReviewed':False}


def owned_activity_foreground(activities: str) -> bool:
    component = re.escape(PACKAGE) + r'/(?:\.|' + re.escape(PACKAGE) + r'\.)MainActivity\b'
    return re.search(r'(?:topResumedActivity|mResumedActivity)[^\r\n]*' + component,activities) is not None


def capture_rendered_screenshot(adb, output: Path, *, attempts: int = 5) -> dict:
    for attempt in range(attempts):
        png = adb('exec-out','screencap','-p',binary=True)
        try:
            metrics = screenshot_metrics(png)
        except ValueError:
            # Retain a bounded failed artifact for diagnosis, never label it device.png.
            if len(png) <= 16*1024*1024: (output/'device-rejected.png').write_bytes(png)
            if attempt+1 == attempts: raise
            time.sleep(.5); continue
        (output/'device.png').write_bytes(png)
        rejected = output/'device-rejected.png'
        if rejected.exists(): rejected.unlink()
        return metrics
    raise ValueError('No device screenshot was captured')


def owned_ui_status(hierarchy: str) -> dict:
    if len(hierarchy.encode('utf-8')) > 1024*1024:
        raise ValueError('Device accessibility tree exceeds limit')
    root = ET.fromstring(hierarchy)
    nodes = list(root.iter())
    if len(nodes) > 10000: raise ValueError('Device accessibility node limit exceeded')
    # Decode XML entities/newlines, and do not accept matching notification or launcher text.
    text = '\n'.join(node.attrib.get(key,'') for node in nodes if node.attrib.get('package') == PACKAGE
                     for key in ('text','content-desc'))
    return {'ownedNodeCount':sum(node.attrib.get('package') == PACKAGE for node in nodes),
            'titlePresent':'Device acceptance' in text,'readyPresent':'Ready on 127.0.0.1:8765' in text}


def capture_owned_ui(adb, output: Path, *, attempts: int = 5) -> dict:
    """Persist failure evidence before enforcing all UI/rendering checks, then settle boundedly."""
    last = {}
    for attempt in range(attempts):
        adb('shell','input','keyevent','KEYCODE_WAKEUP')
        adb('shell','wm','dismiss-keyguard')
        foreground = owned_activity_foreground(adb('shell','dumpsys','activity','activities'))
        last = {'attempt':attempt+1,'foregroundOwned':foreground,'treeAvailable':False,
                'titlePresent':False,'readyPresent':False,'nonBlankPassed':False,'accepted':False}
        try:
            adb('shell','uiautomator','dump','/data/local/tmp/wrapperguard-acceptance-ui.xml')
            hierarchy = adb('shell','cat','/data/local/tmp/wrapperguard-acceptance-ui.xml')
            # Save the exact tree even if its text is missing; absence must remain diagnosable.
            (output/'device-ui.xml').write_text(hierarchy,encoding='utf-8')
            last.update(owned_ui_status(hierarchy)); last['treeAvailable'] = True
        except (ET.ParseError,ValueError,subprocess.CalledProcessError,subprocess.TimeoutExpired):
            pass
        screenshot = None
        try:
            screenshot = capture_rendered_screenshot(adb,output,attempts=1)
            last['nonBlankPassed'] = True
        except ValueError:
            pass
        last['accepted'] = all(last[key] for key in ('foregroundOwned','treeAvailable','titlePresent','readyPresent','nonBlankPassed'))
        (output/'ui-check.json').write_text(json.dumps(last,indent=2)+'\n',encoding='utf-8')
        if last['accepted']: return screenshot
        candidate = output/'device.png'
        if candidate.exists(): candidate.replace(output/'device-rejected.png')
        if attempt+1 < attempts: time.sleep(1)
    raise RuntimeError('Owned Android UI/rendering acceptance did not pass; inspect device-ui.xml, ui-check.json and device-rejected.png')


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
        adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        adb("shell", "wm", "dismiss-keyguard")
        launch = adb("shell", "am", "start", "-W", "--activity-reorder-to-front", "-n", PACKAGE + "/.MainActivity")
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

        screenshot = capture_owned_ui(adb,args.output)
        evidence = {"schemaVersion": 1, "package": PACKAGE, "serial": args.serial,
                    "deviceKind": "emulator" if args.serial.startswith("emulator-") else "physical",
                    "api": adb("shell", "getprop", "ro.build.version.sdk"),
                    "androidRelease": adb("shell", "getprop", "ro.build.version.release"),
                    "abi": adb("shell", "getprop", "ro.product.cpu.abi"),
                    "apkSha256": hashlib.sha256(args.apk.read_bytes()).hexdigest(),
                    "launchPassed": True, "loopbackHeadersPassed": True, "sdkAudit": sdk_results,
                    "screenshot":screenshot, "visualReview":"PENDING_HUMAN_REVIEW",
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
