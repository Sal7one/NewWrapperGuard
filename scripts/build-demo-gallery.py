#!/usr/bin/env python3
"""Index captured app screenshots without modifying their pixels."""
import hashlib
import html
import json
from pathlib import Path
import struct

ROOT = Path(__file__).resolve().parents[1]
DEMO = ROOT / "demo"
SCREENSHOTS = DEMO / "screenshots"
LABELS = {
    "01-summary-dark": "Workspace overview", "01-overview-dark": "Workspace overview",
    "02-findings-dark": "Findings & evidence", "03-execution-dark": "Execution graph",
    "04-policy-dark": "Provider permissions", "05-findings-light": "Light appearance",
    "06-findings-narrow": "Narrow workspace", "07-approved-baseline": "Approved baseline",
    "08-demo-permission-controls": "Permission controls", "09-execution-expanded": "Expanded execution workspace",
}


def main():
    entries = []
    for image in sorted(SCREENSHOTS.glob("*/*.png")):
        contents = image.read_bytes()
        assert contents[:8] == b"\x89PNG\r\n\x1a\n", image
        width, height = struct.unpack(">II", contents[16:24])
        relative = image.relative_to(DEMO).as_posix()
        entries.append({"file": relative, "surface": image.parent.name,
            "label": LABELS.get(image.stem, image.stem.replace("-", " ").title()),
            "width": width, "height": height, "sha256": hashlib.sha256(contents).hexdigest()})
    assert entries, "Capture demo screenshots first."
    data = {"demo": True, "version": "0.4.0", "project": "Atlas Payments",
        "reportSha256": hashlib.sha256((DEMO / "report.json").read_bytes()).hexdigest(),
        "settingsSha256": hashlib.sha256((DEMO / "settings.json").read_bytes()).hexdigest(),
        "sourceSha256": {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted([ROOT / "vscode-extension/src/webviewUi.ts",
                ROOT / "wrapperguard-intellij/src/main/kotlin/com/sal7one/wrapperguard/intellij/demo/WrapperGuardDemo.kt",
                DEMO / "preview/build-vscode-preview.js", DEMO / "preview/demo-bridge.js"] +
                list((ROOT / "wrapperguard-intellij/src/main/kotlin/com/sal7one/wrapperguard/intellij/ui").rglob("*.kt")))},
        "screenshots": entries}
    (SCREENSHOTS / "manifest.json").write_text(json.dumps(data, indent=2) + "\n")
    sections = []
    for surface, title in [("intellij", "IntelliJ / Android Studio"), ("vscode", "VS Code webview preview")]:
        images = [entry for entry in entries if entry["surface"] == surface]
        review_order = {"02-findings-dark": 0, "04-policy-dark": 1, "01-summary-dark": 2,
            "01-overview-dark": 2, "03-execution-dark": 3}
        images.sort(key=lambda entry: (review_order.get(Path(entry["file"]).stem, 4), entry["file"]))
        if not images:
            continue
        cards = []
        for entry in images:
            source, label = html.escape(entry["file"], quote=True), html.escape(entry["label"])
            cards.append(f'<figure><a href="{source}"><img src="{source}" alt="{label} — simulated Atlas Payments workspace" loading="lazy"></a><figcaption><span>{label}</span><small>{entry["width"]} × {entry["height"]}</small></figcaption></figure>')
        sections.append(f'<section id="{surface}"><div class="section-heading"><h2>{title}</h2><span class="capture-count">{len(images)} captures</span></div><div class="grid">{"".join(cards)}</div></section>')
    document = '''<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>WrapperGuard · Atlas Payments UI baseline</title>
<style>
:root{color-scheme:light dark;font-family:Inter,system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;background:light-dark(#f5f6fa,#13121b);color:light-dark(#232335,#e4e1ee);--muted:light-dark(#616174,#b5b2c8);--border:light-dark(#dce0e9,#383744);--panel:light-dark(#fff,#1b1b24);--accent:light-dark(#4f46e5,#c3c0ff)}
*{box-sizing:border-box}body{margin:0 auto;padding:28px 32px 56px;max-width:1560px}.topline{display:flex;align-items:center;gap:10px;padding-bottom:24px;border-bottom:1px solid var(--border);font-size:14px;font-weight:600}.mark{width:28px;height:28px;background:#4f46e5;border-radius:5px;display:grid;place-items:center;color:#fff}.topline .muted{font-weight:400;color:var(--muted);margin-left:8px}.topline .demo-label{margin-left:auto;font-size:11px;letter-spacing:.08em;border:1px solid var(--border);padding:4px 8px;border-radius:4px;color:var(--muted)}header{display:flex;gap:32px;justify-content:space-between;align-items:center;padding:36px 0 28px}.eyebrow{font-size:11px;font-weight:600;letter-spacing:.1em;text-transform:uppercase;color:var(--accent)}h1{font-size:30px;line-height:1.2;letter-spacing:-.03em;margin:10px 0}p{font-size:13px;line-height:1.6;color:var(--muted);margin:0}.actions{display:flex;gap:8px;flex-wrap:wrap}a{color:inherit;text-decoration:none}a:focus-visible{outline:2px solid var(--accent);outline-offset:4px}.actions a{padding:9px 13px;border:1px solid var(--border);border-radius:4px;font-size:12px}.actions a:first-child{background:#4f46e5;color:#fff;border-color:transparent}.section-heading{display:flex;align-items:center;justify-content:space-between;border-bottom:1px solid var(--border);padding-bottom:12px;margin-bottom:16px}h2{font-size:15px;font-weight:600;margin:0}.capture-count{font-size:11px;color:var(--muted)}section{margin-top:20px;scroll-margin-top:20px}section+section{margin-top:36px}.grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:20px}figure{margin:0;min-width:0;background:var(--panel);border:1px solid var(--border);border-radius:6px;overflow:hidden}figure>a{display:block;background:light-dark(#e9eaf1,#101018);aspect-ratio:3/2;overflow:hidden}img{width:100%;height:100%;object-fit:contain;display:block}figcaption{display:flex;justify-content:space-between;gap:12px;padding:12px 14px;font-size:12px;border-top:1px solid var(--border)}small{color:var(--muted);font-variant-numeric:tabular-nums}footer{margin-top:32px;padding-top:18px;border-top:1px solid var(--border);font-size:11px;line-height:1.7;color:var(--muted)}footer a{color:var(--accent)}@media(max-width:760px){body{padding:20px 16px}header{display:block;padding:28px 0 12px}.actions{margin-top:20px}.grid{grid-template-columns:1fr}h1{font-size:26px}.topline .muted{display:none}}
</style></head><body>
<div class="topline"><span class="mark" aria-hidden="true"><svg width="17" height="18" viewBox="0 0 20 22" fill="none"><path d="M10 1 18 4v6c0 5-5 9-8 11-3-2-8-6-8-11V4l8-3Z" stroke="currentColor" stroke-width="1.6"/><path d="m6 11 3 3 5-6" stroke="currentColor" stroke-width="1.6"/></svg></span>WrapperGuard<span class="muted">/ Design review</span><span class="demo-label">SIMULATED DEMO · 0.4.0</span></div>
<header><div><div class="eyebrow">Interface baseline</div><h1>Atlas Payments</h1><p>Six providers. One review workspace. Findings, execution paths, and permissions.</p></div><nav class="actions" aria-label="Demo resources"><a href="preview/vscode.html">Open interactive demo ↗</a><a href="README.md">Reproduce captures</a></nav></header>
''' + "".join(sections) + '''
<footer>All findings, providers, and timings are fictional. Native captures use the running plugin; browser captures use the actual VS Code renderer with a simulated host. Project execution and sync stay disabled in the native demo. <a href="screenshots/manifest.json">Capture manifest</a></footer>
</body></html>
'''
    (DEMO / "index.html").write_text(document)
    print(f"Indexed {len(entries)} screenshots in demo/index.html and demo/screenshots/manifest.json")


if __name__ == "__main__":
    main()
