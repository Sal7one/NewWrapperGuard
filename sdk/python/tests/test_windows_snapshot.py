import os
from pathlib import Path
import struct
import subprocess
import tempfile
import time
import unittest
from unittest.mock import patch

from wrapperguard_sdk.windows_snapshot import (_Api, _component, _directory_entries,
                                               SnapshotLimits, copy_windows_snapshot)


def _entry(name, attributes=0, next_offset=0):
    encoded = name.encode("utf-16-le")
    data = bytearray(68 + len(encoded))
    struct.pack_into("<I", data, 0, next_offset)
    struct.pack_into("<II", data, 56, attributes, len(encoded))
    data[68:] = encoded
    return bytes(data)


class DirectoryRecordTests(unittest.TestCase):
    def test_valid_handle_directory_records_and_special_names(self):
        self.assertEqual([("Main.py", 0)], list(_directory_entries(_entry("Main.py"))))
        first = _entry("one.py", next_offset=80)
        self.assertEqual([("one.py", 0), ("two.py", 0)], list(_directory_entries(first + b"\x00" * (80-len(first)) + _entry("two.py"))))
        for name in ("..", ".", "file.py:secret", "CON.py", "COM¹.txt", "LPT²", "CONIN$", "bad/child", "name.", "name ", "*.py"):
            self.assertFalse(_component(name), name)

    def test_malformed_record_offsets_and_names_fail_closed(self):
        for data in (b"", _entry("one.py", next_offset=1), _entry("one.py", next_offset=65528), _entry("../escape")):
            with self.subTest(data=data):
                with self.assertRaises((ValueError, UnicodeError)):
                    list(_directory_entries(data))
        bad_length = bytearray(_entry("one.py"))
        struct.pack_into("<I", bad_length, 60, 10000)
        with self.assertRaises(ValueError):
            list(_directory_entries(bad_length))

    @unittest.skipIf(os.name == "nt", "non-Windows guard")
    def test_windows_helper_never_falls_back_to_path_based_source_reads(self):
        with self.assertRaises(NotImplementedError):
            copy_windows_snapshot(Path("/source"), Path("/destination"), time.monotonic()+1)


@unittest.skipUnless(os.name == "nt", "requires real Windows handle APIs")
class WindowsSnapshotTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="wg-windows-snapshot-")
        self.base = Path(self.temporary.name).resolve()
        self.root = self.base / "project"
        self.output = self.base / "snapshot"
        self.root.mkdir()
        self.output.mkdir()

    def tearDown(self):
        self.temporary.cleanup()

    def snapshot(self, **kwargs):
        return copy_windows_snapshot(self.root, self.output, time.monotonic()+5, **kwargs)

    def test_regular_unicode_sources_only_and_no_configuration_or_execution(self):
        (self.root / "main.py").write_text("print('inert data')", encoding="utf-8")
        nested = self.root / "nested"
        nested.mkdir()
        (nested / "安全.py").write_text("value = 2", encoding="utf-8")
        (self.root / "bandit.yaml").write_text("project-controlled: config", encoding="utf-8")
        (self.root / "payload.ps1").write_text("Write-Output 'not executed'", encoding="utf-8")
        count, partial = self.snapshot()
        self.assertEqual(2, count)
        self.assertFalse(partial)
        self.assertEqual({"main.py", "nested/安全.py"}, {p.relative_to(self.output).as_posix() for p in self.output.rglob("*") if p.is_file()})

    def test_junction_never_copies_external_sentinel(self):
        outside = self.base / "outside"
        outside.mkdir()
        (outside / "secret.py").write_text("private-sentinel", encoding="utf-8")
        junction = self.root / "linked"
        subprocess.run([os.environ.get("COMSPEC", r"C:\Windows\System32\cmd.exe"), "/d", "/c", "mklink", "/J", str(junction), str(outside)],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5)
        try:
            count, partial = self.snapshot()
            self.assertEqual(0, count)
            self.assertTrue(partial)
            self.assertFalse(any(self.output.rglob("*.py")))
        finally:
            os.rmdir(junction)

    def test_swapped_directory_to_junction_between_enumeration_and_open_is_rejected(self):
        source = self.root / "source"
        source.mkdir()
        (source / "inside.py").write_text("inside", encoding="utf-8")
        outside = self.base / "outside"
        outside.mkdir()
        (outside / "secret.py").write_text("private-sentinel", encoding="utf-8")
        original = _Api.child
        replaced = False

        def swap(api, parent, name, directory):
            nonlocal replaced
            if name == "source" and not replaced:
                replaced = True
                source.rename(self.root / "moved")
                subprocess.run([os.environ.get("COMSPEC", r"C:\Windows\System32\cmd.exe"), "/d", "/c", "mklink", "/J", str(source), str(outside)],
                               check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5)
            return original(api, parent, name, directory)

        try:
            with patch.object(_Api, "child", swap):
                _, partial = self.snapshot()
            self.assertTrue(replaced)
            self.assertTrue(partial)
            self.assertFalse(any(p.name == "secret.py" for p in self.output.rglob("*")))
        finally:
            if replaced:
                os.rmdir(source)

    def test_symlink_file_rejected_when_host_allows_symlink_creation(self):
        outside = self.base / "secret.py"
        outside.write_text("private-sentinel", encoding="utf-8")
        try:
            (self.root / "linked.py").symlink_to(outside)
        except OSError as error:
            self.skipTest("Windows account lacks symlink creation privilege: " + str(error.winerror))
        count, partial = self.snapshot()
        self.assertEqual(0, count)
        self.assertTrue(partial)

    def test_file_byte_entry_and_depth_budgets_are_explicitly_partial(self):
        (self.root / "large.py").write_bytes(b"x" * 32)
        count, partial = self.snapshot(limits=SnapshotLimits(max_file_bytes=8))
        self.assertEqual(0, count)
        self.assertTrue(partial)
        deep = self.root / "a" / "b"
        deep.mkdir(parents=True)
        (deep / "deep.py").write_text("data", encoding="utf-8")
        count, partial = self.snapshot(limits=SnapshotLimits(max_depth=1, max_entries=2))
        self.assertTrue(partial)
        self.assertFalse((self.output / "a/b/deep.py").exists())

    def test_deadline_and_cancellation_do_not_become_clean_empty_snapshots(self):
        with self.assertRaises(TimeoutError):
            copy_windows_snapshot(self.root, self.output, time.monotonic()-1)
        with self.assertRaises(InterruptedError):
            self.snapshot(cancelled=lambda: True)


if __name__ == "__main__":
    unittest.main()
