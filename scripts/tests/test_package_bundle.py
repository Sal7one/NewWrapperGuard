"""Offline guides retain their repository paths and cannot publish broken local links."""
import importlib.util
import io
from pathlib import Path
import stat
import tempfile
import unittest
import warnings
import zipfile


spec = importlib.util.spec_from_file_location('package_bundle', Path(__file__).parents[1]/'package-test-bundle.py')
bundle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bundle)


class PackageBundleTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='wg-bundle-links-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def write(self, path, content=''):
        target = self.root/path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content)
        return target

    def test_copied_guides_preserve_nested_gallery_extension_and_workflow_paths(self):
        source = self.root/'source'; output = self.root/'bundle'; source.mkdir(); output.mkdir()
        for directory in ('docs', 'schema', 'catalog', 'examples', 'research', 'test-fixtures/newfirstparty'):
            (source/directory).mkdir(parents=True)
        for name in ('HANDOVER.md', 'LICENSE', 'CHANGELOG.md', 'SECURITY.md', 'THIRD_PARTY_NOTICES.md'):
            self.write('source/'+name)
        self.write('source/docs/verification.md', '[Capture](../demo/screenshots/platform-0.7/device/README.md)\n'
                   '[SDK](../vscode-extension/README.md)')
        self.write('source/demo/screenshots/platform-0.7/device/README.md', '![Device](device.png)\n'
                   '[Guide](../../../../docs/verification.md)')
        self.write('source/demo/screenshots/platform-0.7/device/device.png', 'inert image fixture')
        self.write('source/vscode-extension/README.md', '[Guide](../docs/verification.md)')
        self.write('source/examples/device/README.md', '[Workflow](../../.github/workflows/android-device.yml)')
        self.write('source/.github/workflows/android-device.yml', 'name: inert workflow fixture')
        bundle.copy_review_material(source, output)
        bundle.validate_markdown_links(output)
        self.assertTrue((output/'demo/screenshots/platform-0.7/device/device.png').is_file())
        self.assertFalse((output/'screenshots').exists())
        self.assertTrue((output/'.github/workflows/android-device.yml').is_file())

    def test_missing_inline_and_reference_destinations_report_owning_document(self):
        self.write('docs/guide.md', '[Inline](missing.md)\n![Reference][capture]\n\n[capture]: ../missing.png "Title"')
        with self.assertRaisesRegex(ValueError, r'docs/guide.md: missing destination: missing.md') as error:
            bundle.validate_markdown_links(self.root)
        self.assertIn('../missing.png', str(error.exception))

    def test_fragments_external_uris_titles_encoded_spaces_and_nested_parentheses(self):
        self.write('docs/space name.md'); self.write('docs/name(part).md')
        self.write('docs/guide.md', '[Anchor](#section) [HTTPS](https://example.test/a_(b))\n'
                   '[App](codex://review?pr=test) [Mail](mailto:test@example.test)\n'
                   '[Space](space%20name.md?download=1#heading "Title")\n'
                   '[Angle](<space name.md>) [Parentheses](name(part).md)\n'
                   '[Reference][local]\n[local]: name(part).md "A title"')
        bundle.validate_markdown_links(self.root)

    def test_local_links_cannot_escape_bundle_even_when_outside_destination_exists(self):
        self.write('outside.md')
        output = self.root/'bundle'; output.mkdir()
        for link in ('../outside.md', '%2e%2e/outside.md', str(self.root/'outside.md'),
                     (self.root/'outside.md').as_uri(), 'file://host/outside.md', 'C:/outside.md'):
            with self.subTest(link=link):
                (output/'guide.md').write_text('[Outside]('+link+')')
                with self.assertRaisesRegex(ValueError, 'outside bundle'):
                    bundle.validate_markdown_links(output)

    def test_literal_code_samples_do_not_create_bundled_destinations(self):
        self.write('guide.md', '`[Inline](missing.md)`\n```markdown\n[Example](missing.md)\n```\n'
                   '~~~markdown\n[other]: missing.md\n~~~\n')
        bundle.validate_markdown_links(self.root)

    def cli_zip(self, entries):
        output = io.BytesIO()
        with warnings.catch_warnings(), zipfile.ZipFile(output, 'w') as archive:
            warnings.simplefilter('ignore', UserWarning)
            for name, content, mode in entries:
                info = zipfile.ZipInfo(name); info.create_system = 3; info.external_attr = mode << 16
                archive.writestr(info, content)
        return output.getvalue()

    def test_cli_uses_reviewed_zip_bytes_and_preserves_executable_mode_despite_live_drift(self):
        live = self.write('wrapperguard-cli/build/install/wrapperguard-cli/bin/wrapperguard-cli', 'changed live file')
        data = self.cli_zip([('wrapperguard-cli-0.7.0/bin/wrapperguard-cli', b'reviewed launcher', stat.S_IFREG|0o755)])
        output = self.root/'bundle/cli'
        bundle.extract_cli_distribution(data, output, '0.7.0')
        launcher = output/'bin/wrapperguard-cli'
        self.assertEqual(b'reviewed launcher', launcher.read_bytes())
        self.assertEqual('changed live file', live.read_text())
        if __import__('os').name != 'nt':
            self.assertEqual(0o755, stat.S_IMODE(launcher.stat().st_mode))

    def test_cli_unsafe_archive_entries_are_rejected_before_extraction(self):
        normal = ('wrapperguard-cli-0.7.0/bin/cli', b'reviewed', stat.S_IFREG|0o755)
        unsafe = [('outside/cli', b'outside', stat.S_IFREG|0o755),
                  ('wrapperguard-cli-0.7.0/../outside', b'outside', stat.S_IFREG|0o644),
                  ('wrapperguard-cli-0.7.0/C:/outside', b'outside', stat.S_IFREG|0o644),
                  ('wrapperguard-cli-0.7.0/link', b'../outside', stat.S_IFLNK|0o777),
                  ('wrapperguard-cli-0.7.0/bin', b'conflict', stat.S_IFREG|0o644), normal]
        for entry in unsafe:
            with self.subTest(entry=entry[0]):
                output = self.root/'rejected'
                with self.assertRaises(ValueError):
                    bundle.extract_cli_distribution(self.cli_zip([normal, entry]), output, '0.7.0')
                self.assertFalse(output.exists())


if __name__ == '__main__':
    unittest.main()
