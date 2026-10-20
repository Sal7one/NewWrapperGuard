import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile


spec = importlib.util.spec_from_file_location("wg_upstream_downloader", Path(__file__).resolve().parents[1] / "download-upstream-tools.py")
downloader = importlib.util.module_from_spec(spec)
spec.loader.exec_module(downloader)


class UpstreamDownloaderTests(unittest.TestCase):
    def test_reviewed_asset_requires_matching_official_manifest_and_utf16_checksum(self):
        expected = "a" * 64
        name = "powershell-example.tar.gz"
        checksum = (expected + " *" + name + "\n").encode("utf-16")
        checksum_hash = hashlib.sha256(checksum).hexdigest()
        release = {"tag_name": "v1", "draft": False, "assets": [
            {"name": name, "digest": "sha256:" + expected, "browser_download_url": "https://github.com/PowerShell/PowerShell/releases/download/v1/" + name},
            {"name": "hashes.sha256", "digest": "sha256:" + checksum_hash, "browser_download_url": "https://github.com/PowerShell/PowerShell/releases/download/v1/hashes.sha256"}]}
        asset = {"repository": "PowerShell/PowerShell", "tag": "v1", "name": name, "sha256": expected, "checksums": "hashes.sha256"}
        with patch.object(downloader, "fetch", side_effect=[(json.dumps(release).encode(), ""), (checksum, checksum_hash)]):
            self.assertTrue(downloader.verified_asset(asset)["checksumManifestVerified"])
        release["assets"][0]["digest"] = "sha256:" + "b" * 64
        with patch.object(downloader, "fetch", return_value=(json.dumps(release).encode(), "")):
            with self.assertRaises(ValueError):
                downloader.verified_asset(asset)

    def test_archive_paths_and_special_tar_files_cannot_escape_owned_output(self):
        for name in ("../outside", "/absolute", "C:/drive", "folder\\escape", "."):
            with self.subTest(name=name):
                with self.assertRaises(ValueError):
                    downloader.safe_member(name)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / "bad.tar"
            with tarfile.open(archive, "w") as output:
                item = tarfile.TarInfo("unsafe-link")
                item.type = tarfile.SYMTYPE
                item.linkname = "../outside"
                output.addfile(item)
            with self.assertRaises(ValueError):
                downloader.extract(archive, root / "owned", "tar")
            self.assertFalse((root / "outside").exists())

    def test_zip_traversal_and_budget_exhaustion_fail_before_writing_payload(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / "bad.zip"
            with zipfile.ZipFile(archive, "w") as output:
                output.writestr("../outside", b"payload")
            with self.assertRaises(ValueError):
                downloader.extract(archive, root / "owned", "zip")
            self.assertFalse((root / "outside").exists())
            archive = root / "large.tar"
            with tarfile.open(archive, "w") as output:
                item = tarfile.TarInfo("large")
                item.size = 5
                output.addfile(item, io.BytesIO(b"12345"))
            with patch.object(downloader, "MAX_EXTRACTED", 4):
                with self.assertRaises(ValueError):
                    downloader.extract(archive, root / "bounded", "tar")
            self.assertFalse((root / "bounded/large").exists())

    def test_regular_release_data_extraction_preserves_selected_executable_mode(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / "valid.tar"
            with tarfile.open(archive, "w") as output:
                directory = tarfile.TarInfo(".")
                directory.type = tarfile.DIRTYPE
                output.addfile(directory)
                item = tarfile.TarInfo("bin/scanner")
                item.size = 4
                item.mode = 0o755
                output.addfile(item, io.BytesIO(b"data"))
            downloader.extract(archive, root / "owned", "tar")
            self.assertEqual(b"data", (root / "owned/bin/scanner").read_bytes())


if __name__ == "__main__":
    unittest.main()
