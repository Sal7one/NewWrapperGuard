"""The audit inspects delivered bytes, including LICENSE.md and unlisted files."""
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location('distribution_audit', Path(__file__).parents[1]/'distribution-audit.py')
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


def zipped(files):
    output = io.BytesIO()
    with zipfile.ZipFile(output, 'w') as archive:
        for name, value in files.items(): archive.writestr(name, value)
    return output.getvalue()


class DistributionAuditTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='wg-distribution-audit-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.version = '0.7.0'; self.sdk = '0.4.0'
        self.license = b'Controlled Apache license fixture\n'; self.notice = b'Controlled notices fixture\n'
        self.sax_js = b'// controlled SAX runtime fixture\n'; self.sax_license = b'Controlled BlueOak license fixture\n'
        for name, value in [('SAX_JS_SHA256', self.sax_js), ('SAX_LICENSE_SHA256', self.sax_license)]:
            mock = patch.object(audit, name, audit.digest(value)); mock.start(); self.addCleanup(mock.stop)
        self.write('build.gradle.kts', 'version = "0.7.0"\n')
        self.write('sdk/python/pyproject.toml', 'version = "0.4.0"\n')
        self.write('LICENSE', self.license); self.write('THIRD_PARTY_NOTICES.md', self.notice)
        self.module = b'__version__ = "0.4.0"\n'
        self.write('sdk/python/src/wrapperguard_sdk/__init__.py', self.module)
        self.metadata = ('Metadata-Version: 2.4\nName: wrapperguard-sdk\nVersion: 0.4.0\n'
            'License-Expression: Apache-2.0\nRequires-Python: >=3.10\n\n')
        self.wheel_files = {'wrapperguard_sdk/__init__.py': self.module,
            'wrapperguard_sdk-0.4.0.dist-info/METADATA': self.metadata,
            'wrapperguard_sdk-0.4.0.dist-info/licenses/LICENSE': self.license}
        self.cli = self.root/'wrapperguard-cli/build/install/wrapperguard-cli'
        for key, (_, version) in audit.JARS.items(): self.write(self.cli/'lib'/f'{key}-{version}.jar', ('jar '+key).encode())
        for part in ('core', 'rules', 'cli'): self.write(self.cli/'lib'/f'wrapperguard-{part}-0.7.0.jar', ('jar '+part).encode())
        self.write(self.cli/'LICENSE', self.license); self.write(self.cli/'THIRD_PARTY_NOTICES.md', self.notice)
        self.write(self.cli/'bin/wrapperguard-cli', '#!/bin/sh\n# inert fixture\n')
        self.cli_zip = self.root/'wrapperguard-cli/build/distributions/wrapperguard-cli-0.7.0.zip'
        self.write(self.cli_zip, zipped({'wrapperguard-cli-0.7.0/'+path.relative_to(self.cli).as_posix():path.read_bytes()
            for path in self.cli.rglob('*') if path.is_file()}))
        self.lock = {'packages': {'': {'name':'wrapperguard'}, 'node_modules/sax': {'version':'1.6.1','license':'BlueOak-1.0.0'},
            'node_modules/development-fixture': {'version':'1','dev':True}}}
        self.write('vscode-extension/package-lock.json', json.dumps(self.lock))
        self.sax = {'name':'sax','version':'1.6.1','license':'BlueOak-1.0.0','main':'lib/sax.js'}
        self.rebuild()

    def write(self, file, data):
        file = self.root/file; file.parent.mkdir(parents=True, exist_ok=True)
        file.write_bytes(data if isinstance(data, bytes) else data.encode())

    def rebuild(self):
        wheel = zipped(self.wheel_files)
        self.write('build/sdk/wrapperguard_sdk-0.4.0-py3-none-any.whl', wheel)
        main = zipped({'integrations/wrapperguard_sdk-0.4.0-py3-none-any.whl':wheel,
            'META-INF/LICENSE':self.license,'META-INF/THIRD_PARTY_NOTICES.md':self.notice})
        self.native_files = {'wrapperguard-intellij/lib/'+path.name:path.read_bytes()
            for path in (self.cli/'lib').glob('*.jar') if not path.name.startswith('wrapperguard-cli-')}
        self.native_files['wrapperguard-intellij/lib/wrapperguard-intellij-0.7.0.jar'] = main
        self.native = self.root/'wrapperguard-intellij/build/distributions/wrapperguard-intellij-0.7.0.zip'
        self.write(self.native, zipped(self.native_files))
        self.vsix_files = {'extension/resources/sdk/wrapperguard_sdk-0.4.0-py3-none-any.whl':wheel,
            'extension/package.json':json.dumps({'version':'0.7.0','license':'Apache-2.0','dependencies':{'sax':'1.6.1'}}),
            'extension/node_modules/sax/package.json':json.dumps(self.sax),
            'extension/node_modules/sax/lib/sax.js':self.sax_js,'extension/node_modules/sax/LICENSE.md':self.sax_license,
            'extension/LICENSE.txt':self.license,'extension/resources/THIRD_PARTY_NOTICES.md':self.notice}
        self.vsix = self.root/'vscode-extension/wrapperguard-0.7.0.vsix'
        self.write(self.vsix, zipped(self.vsix_files))

    def test_actual_license_md_and_all_nine_runtime_components_are_inventoried(self):
        bom, report = audit.inventory(self.root)
        self.assertEqual(9, len(bom['components']))
        self.assertEqual(4, len(report['artifactSha256']))
        self.assertTrue(report['bundledWheelsIdentical'])
        sax = next(item for item in bom['components'] if item['name']=='sax')
        self.assertEqual(audit.digest(self.sax_js), sax['hashes'][0]['content'])

    def test_unknown_cli_jar_and_changed_cli_archive_are_rejected(self):
        self.write(self.cli/'lib/unreviewed.jar', b'new runtime')
        with self.assertRaisesRegex(ValueError, 'Unreviewed'): audit.inventory(self.root)
        (self.cli/'lib/unreviewed.jar').unlink()
        self.write(self.cli_zip, zipped({'wrapperguard-cli-0.7.0/bin/wrapperguard-cli':b'changed'}))
        with self.assertRaisesRegex(ValueError, 'Packaged CLI'): audit.inventory(self.root)

    def test_native_missing_unknown_and_changed_shared_runtime_are_rejected(self):
        original = dict(self.native_files)
        for change in ('missing', 'unknown', 'different'):
            with self.subTest(change=change):
                files = dict(original)
                if change=='missing': del files['wrapperguard-intellij/lib/annotations-13.0.jar']
                elif change=='unknown': files['wrapperguard-intellij/lib/unknown.jar'] = b'new'
                else: files['wrapperguard-intellij/lib/annotations-13.0.jar'] = b'changed'
                self.write(self.native, zipped(files))
                with self.assertRaises(ValueError): audit.inventory(self.root)

    def test_sdk_metadata_license_dependencies_and_source_must_match(self):
        metadata_key = 'wrapperguard_sdk-0.4.0.dist-info/METADATA'; original = self.wheel_files[metadata_key]
        for metadata in (original.replace('Version: 0.4.0','Version: 0.3.0'),
                original.replace('Apache-2.0','Unreviewed-License'), original.replace('\n\n','\nRequires-Dist: injected\n\n')):
            with self.subTest(metadata=metadata):
                self.wheel_files[metadata_key]=metadata; self.rebuild()
                with self.assertRaises(ValueError): audit.inventory(self.root)
        self.wheel_files[metadata_key]=original
        self.wheel_files['wrapperguard_sdk-0.4.0.dist-info/licenses/LICENSE']=b'changed license'; self.rebuild()
        with self.assertRaisesRegex(ValueError, 'license'): audit.inventory(self.root)
        self.wheel_files['wrapperguard_sdk-0.4.0.dist-info/licenses/LICENSE']=self.license
        self.wheel_files['wrapperguard_sdk/extra.py']=b'unreviewed'; self.rebuild()
        with self.assertRaisesRegex(ValueError, 'runtime files'): audit.inventory(self.root)

    def test_one_different_embedded_wheel_is_not_accepted_by_version_name(self):
        self.vsix_files['extension/resources/sdk/wrapperguard_sdk-0.4.0-py3-none-any.whl']=b'same name different bytes'
        self.write(self.vsix, zipped(self.vsix_files))
        with self.assertRaisesRegex(ValueError, 'VS Code SDK wheel differs'): audit.inventory(self.root)
        self.rebuild()
        self.native_files['wrapperguard-intellij/lib/wrapperguard-intellij-0.7.0.jar']=zipped({
            'integrations/wrapperguard_sdk-0.4.0-py3-none-any.whl':b'same name different metadata',
            'META-INF/LICENSE':self.license,'META-INF/THIRD_PARTY_NOTICES.md':self.notice})
        self.write(self.native, zipped(self.native_files))
        with self.assertRaisesRegex(ValueError, 'Native SDK wheel differs'): audit.inventory(self.root)

    def test_actual_node_closure_checks_files_metadata_bytes_and_notices(self):
        original = dict(self.vsix_files)
        changes = [('extension/node_modules/hidden/index.js', b'new runtime'),
            ('extension/node_modules/sax/lib/extra.js', b'new runtime'),
            ('extension/node_modules/sax/package.json', json.dumps({**self.sax,'dependencies':{'hidden':'1'}})),
            ('extension/node_modules/sax/lib/sax.js', b'changed parser'),
            ('extension/node_modules/sax/LICENSE.md', b'changed license'),
            ('extension/resources/THIRD_PARTY_NOTICES.md', b'wrong notices')]
        for name, value in changes:
            with self.subTest(file=name):
                self.write(self.vsix, zipped({**original,name:value}))
                with self.assertRaises(ValueError): audit.inventory(self.root)

    def test_lockfile_runtime_expansion_cannot_be_hidden_by_packaging(self):
        self.lock['packages']['node_modules/new-runtime']={'version':'1'}
        self.write('vscode-extension/package-lock.json', json.dumps(self.lock))
        with self.assertRaisesRegex(ValueError, 'Node runtime'): audit.inventory(self.root)


if __name__=='__main__': unittest.main()
