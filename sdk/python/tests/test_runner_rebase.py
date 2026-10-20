from pathlib import Path, PureWindowsPath
import unittest

from wrapperguard_sdk.runner import _rebase


class RunnerRebaseTests(unittest.TestCase):
    def test_osv_windows_slashes_drive_case_and_nested_source_records_map_to_owned_snapshot_only(self):
        snapshot = PureWindowsPath(r'D:\a\_temp\wrapperguard-osv-test\source')
        for path in ('D:/a/_temp/wrapperguard-osv-test/source/package-lock.json',
                     r'D:\a\_temp\wrapperguard-osv-test\source\package-lock.json',
                     'd:/A/_TEMP/wrapperguard-osv-test/SOURCE/package-lock.json',
                     'file:///D:/a/_temp/wrapperguard-osv-test/source/package-lock.json'):
            with self.subTest(path=path):
                document = {'results': [{'source': {'path': path, 'type': 'lockfile'}, 'packages': []}]}
                mapped = _rebase(document, snapshot)
                self.assertEqual('package-lock.json', mapped['results'][0]['source']['path'])
        for path in ('D:/a/_temp/wrapperguard-osv-test/source-other/package-lock.json',
                     'C:/a/_temp/wrapperguard-osv-test/source/package-lock.json',
                     'D:/unrelated/package-lock.json'):
            self.assertEqual(path, _rebase(path, snapshot))

    def test_posix_paths_keep_case_sensitive_containment_and_unrelated_strings(self):
        snapshot = Path('/owned/Snapshot/source')
        self.assertEqual('nested/pom.xml', _rebase('/owned/Snapshot/source/nested/pom.xml', snapshot))
        self.assertEqual('/owned/snapshot/source/pom.xml', _rebase('/owned/snapshot/source/pom.xml', snapshot))
        self.assertEqual('/owned/Snapshot/source-elsewhere/pom.xml', _rebase('/owned/Snapshot/source-elsewhere/pom.xml', snapshot))


if __name__ == '__main__': unittest.main()
