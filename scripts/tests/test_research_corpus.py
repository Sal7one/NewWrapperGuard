import gzip
import importlib.util
import io
from pathlib import Path
import tarfile
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location('corpus', Path(__file__).parents[1] / 'research-corpus.py')
corpus = importlib.util.module_from_spec(spec)
spec.loader.exec_module(corpus)


class CorpusBoundaryTests(unittest.TestCase):
    def zip_bytes(self, files):
        output = io.BytesIO()
        with zipfile.ZipFile(output, 'w') as archive:
            for name, data in files:
                archive.writestr(name, data)
        return output.getvalue()

    def test_source_is_returned_as_bytes_and_no_code_is_imported(self):
        raw = self.zip_bytes([('package/setup.py', b'raise RuntimeError("NEVER EXECUTE")'), ('package/readme.md', b'ignored')])
        self.assertEqual([('package/setup.py', b'raise RuntimeError("NEVER EXECUTE")')], corpus.parse_archive(raw, 'zip'))

    def test_archive_paths_are_validated_even_for_ignored_extensions(self):
        for name in ('../escape.txt', '/absolute.txt', 'package/../escape.py', 'C:\\secret.py', 'package//file.py'):
            with self.subTest(name=name), self.assertRaises(ValueError):
                corpus.parse_archive(self.zip_bytes([(name, b'data')]), 'zip')

    def test_case_aliases_duplicate_names_and_source_limits_fail_closed(self):
        with self.assertRaises(ValueError):
            corpus.parse_archive(self.zip_bytes([('a.py', b'one'), ('A.py', b'two')]), 'zip')
        with patch.object(corpus, 'MAX_FILE', 10), self.assertRaises(ValueError):
            corpus.parse_archive(self.zip_bytes([('a.py', b'x' * 11)]), 'zip')

    def test_zip_symlink_is_never_materialized(self):
        output = io.BytesIO()
        with zipfile.ZipFile(output, 'w') as archive:
            entry = zipfile.ZipInfo('package/escape.py'); entry.create_system = 3; entry.external_attr = 0o120777 << 16
            archive.writestr(entry, '/outside/source.py')
        with self.assertRaises(ValueError):
            corpus.parse_archive(output.getvalue(), 'zip')

    def test_tar_links_are_ignored_and_expansion_is_bounded_before_parsing(self):
        output = io.BytesIO()
        with tarfile.open(fileobj=output, mode='w') as archive:
            entry = tarfile.TarInfo('project/code.py'); entry.size = 4; archive.addfile(entry, io.BytesIO(b'pass'))
            link = tarfile.TarInfo('project/link.py'); link.type = tarfile.SYMTYPE; link.linkname = '/outside'; archive.addfile(link)
        raw = gzip.compress(output.getvalue())
        self.assertEqual([('project/code.py', b'pass')], corpus.parse_archive(raw, 'tar-gz'))
        with patch.object(corpus, 'MAX_EXPANDED', 64), self.assertRaises(ValueError):
            corpus.parse_archive(raw, 'tar-gz')


if __name__ == '__main__': unittest.main()
