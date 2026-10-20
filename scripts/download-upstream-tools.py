#!/usr/bin/env python3
"""Explicit CI-only installer for pinned official Linux scanner releases.

Downloads are checked against the reviewed lock SHA256, the official GitHub release
asset digest and, where published, its release checksum manifest. No latest-version
resolution, package manager, repository code, install hook or downloaded script runs.
The official digests authenticate integrity relative to upstream; they are not a
claim of independent third-party signature verification.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import platform
import re
import shutil
import stat
import sys
import tarfile
import tempfile
import urllib.request
from urllib.parse import urlsplit
import zipfile

LOCK = Path(__file__).with_name("upstream-tools.lock.json")
REPOSITORIES = {"PowerShell/PowerShell", "PowerShell/PSScriptAnalyzer", "google/osv-scanner", "aquasecurity/trivy"}
HOSTS = {"api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"}
MAX_DOWNLOAD = 512 * 1024 * 1024
MAX_EXTRACTED = 1024 * 1024 * 1024


def fetch(url: str, output: Path | None = None, maximum: int = MAX_DOWNLOAD) -> tuple[bytes, str]:
    if urlsplit(url).scheme != "https" or urlsplit(url).hostname not in HOSTS:
        raise ValueError("Only official HTTPS release endpoints are allowed")
    request = urllib.request.Request(url, headers={"User-Agent": "WrapperGuard-pinned-upstream-ci", "Accept": "application/vnd.github+json" if "api.github.com" in url else "application/octet-stream"})
    digest = hashlib.sha256()
    data = bytearray()
    count = 0
    with urllib.request.urlopen(request, timeout=120) as response:
        actual = urlsplit(response.geturl())
        if actual.scheme != "https" or actual.hostname not in HOSTS:
            raise ValueError("Unexpected release download redirect")
        stream = output.open("xb") if output else None
        try:
            while chunk := response.read(131072):
                count += len(chunk)
                if count > maximum:
                    raise ValueError("Release artifact exceeds download budget")
                digest.update(chunk)
                if stream:
                    stream.write(chunk)
                else:
                    data.extend(chunk)
        finally:
            if stream:
                stream.close()
    return bytes(data), digest.hexdigest()


def verified_asset(asset: dict) -> dict:
    repository, tag, name = asset["repository"], asset["tag"], asset["name"]
    if repository not in REPOSITORIES or not re.fullmatch(r"[A-Za-z0-9._-]+", tag) or "/" in name or "\\" in name:
        raise ValueError("Unreviewed release identity")
    raw, _ = fetch(f"https://api.github.com/repos/{repository}/releases/tags/{tag}", maximum=4 * 1024 * 1024)
    release = json.loads(raw)
    if release.get("tag_name") != tag or release.get("draft"):
        raise ValueError("Unexpected upstream release")
    matches = [item for item in release["assets"] if item["name"] == name]
    if len(matches) != 1 or matches[0].get("digest") != "sha256:" + asset["sha256"]:
        raise ValueError("Reviewed SHA256 does not match the official release asset manifest")
    metadata = matches[0]
    expected_url = f"https://github.com/{repository}/releases/download/{tag}/{name}"
    if metadata["browser_download_url"] != expected_url:
        raise ValueError("Unexpected release artifact URL")
    if checksums := asset.get("checksums"):
        candidates = [item for item in release["assets"] if item["name"] == checksums]
        if len(candidates) != 1:
            raise ValueError("Official checksum manifest is missing")
        content, digest = fetch(candidates[0]["browser_download_url"], maximum=2 * 1024 * 1024)
        if candidates[0].get("digest") != "sha256:" + digest:
            raise ValueError("Official checksum manifest digest does not match")
        entries = []
        text = content.decode("utf-16" if content.startswith((b"\xff\xfe", b"\xfe\xff")) else "utf-8-sig")
        for line in text.splitlines():
            match = re.fullmatch(r"([a-fA-F0-9]{64})\s+\*?(.+)", line.strip())
            if match and match[2] == name:
                entries.append(match[1].lower())
        if entries != [asset["sha256"]]:
            raise ValueError("Official checksum manifest does not match the reviewed asset")
    return {"repository": repository, "tag": tag, "name": name, "url": expected_url,
            "sha256": asset["sha256"], "officialManifestVerified": True,
            "checksumManifestVerified": bool(asset.get("checksums"))}


def safe_member(name: str) -> PurePosixPath:
    path = PurePosixPath(name)
    if path.is_absolute() or ".." in path.parts or "\\" in name or ":" in name or not path.parts:
        raise ValueError("Release archive contains an unsafe path")
    return path


def extract(archive: Path, directory: Path, kind: str) -> None:
    directory.mkdir()
    count = total = 0

    def target(name: str, size: int) -> Path:
        nonlocal count, total
        relative = safe_member(name)
        count += 1
        total += size
        if count > 20000 or size < 0 or total > MAX_EXTRACTED:
            raise ValueError("Release archive exceeds extraction limits")
        output = directory.joinpath(*relative.parts)
        output.parent.mkdir(parents=True, exist_ok=True)
        return output

    if kind == "tar":
        with tarfile.open(archive) as items:
            for item in items:
                if item.isdir() and item.name in (".", "./"):
                    count += 1
                    if count > 20000:
                        raise ValueError("Release archive exceeds extraction limits")
                    continue
                safe_member(item.name)
                if item.isdir():
                    target(item.name, 0).mkdir(parents=True, exist_ok=True)
                    continue
                if not item.isfile():
                    raise ValueError("Release archive contains links or special files")
                output = target(item.name, item.size)
                with items.extractfile(item) as source, output.open("xb") as sink:
                    shutil.copyfileobj(source, sink, length=131072)
                output.chmod(0o755 if item.mode & 0o111 else 0o644)
    elif kind == "zip":
        with zipfile.ZipFile(archive) as items:
            for item in items.infolist():
                safe_member(item.filename)
                if item.is_dir():
                    target(item.filename, 0).mkdir(parents=True, exist_ok=True)
                    continue
                if stat.S_ISLNK(item.external_attr >> 16):
                    raise ValueError("Release archive contains symbolic links")
                output = target(item.filename, item.file_size)
                with items.open(item) as source, output.open("xb") as sink:
                    shutil.copyfileobj(source, sink, length=131072)
    else:
        raise ValueError("Unsupported release archive kind")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tool", required=True, choices=["psscriptanalyzer", "osv", "trivy"])
    parser.add_argument("--destination", type=Path)
    parser.add_argument("--verify-only", action="store_true", help="Check official manifests without downloading/installing binaries")
    args = parser.parse_args()
    lock = json.loads(LOCK.read_text(encoding="utf-8"))
    if lock.get("schemaVersion") != 1 or lock.get("platform") != "linux-amd64":
        raise ValueError("Unsupported reviewed tool lock")
    tool = lock["tools"][args.tool]
    verified = [verified_asset(asset) for asset in tool["assets"]]
    result = {"tool": args.tool, "version": tool["version"], "platform": lock["platform"], "assets": verified}
    if args.verify_only:
        print(json.dumps(result))
        return
    if sys.platform != "linux" or platform.machine().lower() not in {"x86_64", "amd64"}:
        raise ValueError("Pinned tool installer currently supports Linux amd64 only")
    if args.destination is None or not args.destination.is_absolute() or args.destination.exists():
        raise ValueError("Choose a new absolute owned tool directory")
    destination = args.destination
    destination.mkdir(parents=True, mode=0o700)
    try:
        with tempfile.TemporaryDirectory(prefix="download-", dir=destination) as temporary:
            for index, (asset, record) in enumerate(zip(tool["assets"], verified)):
                download = Path(temporary) / f"asset-{index}"
                _, digest = fetch(record["url"], output=download)
                if digest != record["sha256"]:
                    raise ValueError("Downloaded release artifact SHA256 does not match")
                output = destination / asset["directory"]
                if asset["kind"] == "binary":
                    output.mkdir()
                    shutil.copyfile(download, output / asset["filename"])
                else:
                    extract(download, output, asset["kind"])
        executable = destination / tool["executable"]
        executable.chmod(0o755)
        result["executable"] = str(executable)
        if module := tool.get("module"):
            result["module"] = str(destination / module)
        (destination / "installed-manifest.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(result))
    except BaseException:
        shutil.rmtree(destination)
        raise


if __name__ == "__main__":
    main()
