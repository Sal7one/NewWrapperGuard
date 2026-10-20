import contextlib
import io
import json
import os
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch

from wrapperguard_sdk import cli
from wrapperguard_sdk.profile import FORMATS, load_profile
from wrapperguard_sdk.rule_packs import RULE_PACKS
from wrapperguard_sdk.runner import _snapshot, run_tool


class RulePackTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.user = Path(self.temp.name).resolve()
        self.root = self.user / 'project'
        self.root.mkdir()
        self.profile = self.user / 'profile.json'
        self.value = {'id': 'sast.semgrep', 'displayName': 'review', 'version': 'test',
                      'analysisKind': 'SAST', 'format': 'sarif',
                      'runner': {'tool': 'semgrep', 'executable': str(Path(os.sys.executable).resolve())}}

    def load(self):
        self.profile.write_text(json.dumps(self.value))
        return load_profile(self.profile, self.root)

    def test_pack_selection_is_explicit_unique_and_exclusive_with_external_rules(self):
        for packs in ([], ['unknown'], ['android-manifest', 'android-manifest'], 'android-manifest', [None]):
            with self.subTest(packs=packs):
                self.value['runner']['rulePacks'] = packs
                with self.assertRaises(ValueError): self.load()
        self.value['runner']['rulePacks'] = ['android-manifest']
        self.value['runner']['rulesPath'] = str(self.user / 'rules.yml')
        with self.assertRaises(ValueError): self.load()
        del self.value['runner']['rulesPath']
        self.assertEqual(self.load().runner.rule_packs, ('android-manifest',))

    def test_legacy_default_and_review_plan_do_not_execute_tools(self):
        profile = self.load()
        with patch('subprocess.Popen', side_effect=AssertionError('no process')):
            self.assertEqual(cli.build_plan(profile, self.root)['rulePacks'], ['legacy-android-ktor'])
            self.value['runner']['rulePacks'] = ['android-manifest', 'jvm-crypto']
            self.assertEqual(cli.build_plan(self.load(), self.root)['rulePacks'], ['android-manifest', 'jvm-crypto'])

    @unittest.skipUnless(os.name == 'posix' and hasattr(os, 'O_NOFOLLOW'), 'POSIX snapshot')
    def test_manifest_pack_limits_snapshot_to_exact_manifests_not_resource_xml(self):
        (self.root / 'AndroidManifest.xml').write_text('<manifest/>')
        (self.root / 'activity.xml').write_text('not selected')
        (self.root / 'Main.kt').write_text('fun app() {}')
        (self.root / 'nested').mkdir()
        (self.root / 'nested/AndroidManifest.xml').write_text('<manifest/>')
        self.value['runner']['rulePacks'] = ['android-manifest']
        observed = {}
        def capture(argv, cwd, environment, deadline, maximum, accepted_codes):
            observed['files'] = sorted(str(p.relative_to(cwd)) for p in cwd.rglob('*') if p.is_file())
            observed['configs'] = [argv[i+1] for i, value in enumerate(argv[:-1]) if value == '--config']
            return b'{"version":"2.1.0","runs":[{"tool":{"driver":{"name":"test"}},"results":[]}]}'
        with patch('wrapperguard_sdk.runner._bounded_process', capture):
            _, notices = run_tool(self.load(), self.root)
        self.assertEqual(observed['files'], ['AndroidManifest.xml', 'nested/AndroidManifest.xml'])
        self.assertEqual(len(observed['configs']), 1)
        self.assertTrue(observed['configs'][0].endswith('android-manifest.yml'))
        self.assertEqual(notices, [])

    def test_capabilities_never_reads_a_project_profile_or_starts_a_process(self):
        output = io.BytesIO()
        class Stdout:
            buffer = output
            def write(self, text): output.write(text.encode())
        with patch('sys.stdout', Stdout()), patch('wrapperguard_sdk.cli.load_profile', side_effect=AssertionError('no profile')), patch('subprocess.Popen', side_effect=AssertionError('no process')):
            self.assertEqual(cli.main(['--capabilities']), 0)
        capabilities = json.loads(output.getvalue())
        self.assertEqual(set(capabilities['formats']), FORMATS)
        self.assertEqual({p['id'] for p in capabilities['rulePacks']}, set(RULE_PACKS))
        self.assertFalse(capabilities['executesProjectCode'])
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(cli.main(['--capabilities', '--profile', 'anything']), 1)


if __name__ == '__main__': unittest.main()
