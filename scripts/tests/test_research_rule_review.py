import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('rule_review',Path(__file__).parents[1] / 'research-rule-review.py')
review = importlib.util.module_from_spec(spec)
spec.loader.exec_module(review)


class RuleReviewTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.project = self.directory / 'example'
        self.project.mkdir()
        self.files = {'src/Main.kt': b'fun main() = println("inert")',
                      'src/AndroidManifest.xml': b'<manifest/>',
                      'build/Generated.kt':b'EXCLUDED', 'setup.py': b'raise RuntimeError("DO NOT IMPORT")'}
        for name,data in self.files.items():
            target = self.project / name
            target.parent.mkdir(parents=True,exist_ok=True)
            target.write_bytes(data)
        self.source = {'id':'example','label':'benign-project',
                       'files':{name:hashlib.sha256(data).hexdigest() for name,data in self.files.items()}}
        (self.directory / 'inventory.json').write_text(json.dumps({'schemaVersion':1,'sources':[self.source]}))

    def test_only_expected_source_inputs_are_selected_independently(self):
        files = review.verified_source(self.directory,self.source)
        self.assertEqual({'src/Main.kt'},review.candidates(files,'legacy-android-ktor'))
        self.assertEqual({'src/AndroidManifest.xml'},review.candidates(files,'android-manifest'))
        for value in ('/outside.kt','../outside.kt','x//a.kt','C:\\outside.kt'):
            with self.subTest(value=value),self.assertRaises(ValueError): review.relative(value)

    def test_missing_changed_extra_and_nonbenign_inputs_are_rejected(self):
        source = dict(self.source,label='malicious-package')
        with self.assertRaises(ValueError): review.verified_source(self.directory,source)
        (self.project / 'untracked.kt').write_text('INERT')
        with self.assertRaises(ValueError): review.verified_source(self.directory,self.source)
        (self.project / 'untracked.kt').unlink()
        (self.project / 'src/Main.kt').write_bytes(b'CHANGED')
        with self.assertRaises(ValueError): review.verified_source(self.directory,self.source)
        (self.project / 'src/Main.kt').unlink()
        with self.assertRaises(OSError): review.verified_source(self.directory,self.source)

    @unittest.skipUnless(os.name == 'posix','POSIX no-follow evaluation driver')
    def test_linked_leaf_and_parent_never_read_outside_source(self):
        outside = self.directory / 'outside.kt'; outside.write_bytes(self.files['src/Main.kt'])
        (self.project / 'src/Main.kt').unlink()
        (self.project / 'src/Main.kt').symlink_to(outside)
        with self.assertRaises(OSError): review.verified_source(self.directory,self.source)
        (self.project / 'src/Main.kt').unlink()
        (self.project / 'src/AndroidManifest.xml').unlink()
        (self.project / 'src').rmdir()
        (self.project / 'src').symlink_to(self.directory,target_is_directory=True)
        with self.assertRaises(OSError): review.verified_source(self.directory,self.source)

    def test_result_keeps_attribution_without_source_evidence_messages_or_absolute_paths(self):
        raw = {'complete':False,'findings':[{'ruleId':'wrapperguard.test','severity':'WARN',
            'location':{'path':'src/Main.kt','line':2},'message':'SECRET','evidence':'PRIVATE SOURCE'}],
            'notices':[{'code':'WG-REPORT-TOOL-ERROR','message':'/private/SECRET'}]}
        result = review.minimize(raw,{'src/Main.kt'})
        serialized = json.dumps(result)
        self.assertFalse(result['complete'])
        self.assertNotIn('SECRET',serialized);self.assertNotIn('PRIVATE',serialized)
        self.assertEqual({'wrapperguard.test':1},result['ruleCounts'])
        self.assertEqual([{'ruleId':'wrapperguard.test','severity':'WARN','path':'src/Main.kt','line':2}],result['findings'])
        raw['findings'][0]['location']['path'] = '../outside.kt'
        with self.assertRaises(ValueError): review.minimize(raw,{'src/Main.kt'})
        raw['findings'][0]['location']['path'] = 'src/Other.kt'
        with self.assertRaises(ValueError): review.minimize(raw,{'src/Main.kt'})

    def fake_run(self,argv,user,timeout,maximum=review.MAX_OUTPUT):
        if argv[-1] == review.IDENTITY_CODE:
            return json.dumps({'sdkVersion':'test','rulePackSha256':{pack:'a'*64 for pack in review.PACKS}}).encode()
        if argv[-1] == '--version': return b'1.181.0\n'
        profile = json.loads(Path(argv[-2]).read_text())
        self.assertEqual('/installed/semgrep',profile['runner']['executable'])
        self.assertEqual(['WARNING'],profile['requestedActions'])
        self.assertEqual(['SCAN'],profile['allowedOperations'])
        self.assertEqual(1,len(profile['runner']['rulePacks']))
        self.assertFalse(Path(argv[-2]).is_relative_to(self.project))
        return b'{"complete":true,"findings":[],"notices":[]}'

    def test_actual_sdk_is_called_with_one_pack_at_a_time_and_source_is_never_imported(self):
        result = review.evaluate(self.directory,Path('/installed/python'),Path('/installed/semgrep'),['example'],run=self.fake_run)
        self.assertTrue(result['allConfiguredScansComplete'])
        self.assertEqual(5,len(result['sources']))
        self.assertFalse(result['projectCodeExecuted']);self.assertFalse(result['precisionMeasured'])
        self.assertEqual(set(review.PACKS),{row['rulePack'] for row in result['sources']})
        self.assertNotIn(str(self.directory),json.dumps(result))

    def test_failed_or_zero_supported_runs_never_become_complete(self):
        def failing(argv,user,timeout,maximum=review.MAX_OUTPUT):
            if argv[-1] == review.IDENTITY_CODE or argv[-1] == '--version':
                return self.fake_run(argv,user,timeout,maximum)
            raise TimeoutError('PRIVATE source path')
        result = review.evaluate(self.directory,Path('/installed/python'),Path('/installed/semgrep'),['example'],run=failing)
        self.assertFalse(result['allConfiguredScansComplete'])
        self.assertTrue(all(row['status'] == 'FAILED' for row in result['sources']))
        self.assertNotIn('PRIVATE',json.dumps(result))
        for name in ('src/Main.kt','src/AndroidManifest.xml'):
            (self.project / name).unlink();del self.source['files'][name]
        (self.directory / 'inventory.json').write_text(json.dumps({'schemaVersion':1,'sources':[self.source]}))
        result = review.evaluate(self.directory,Path('/installed/python'),Path('/installed/semgrep'),['example'],run=self.fake_run)
        self.assertFalse(result['allConfiguredScansComplete'])
        self.assertTrue(all(row['status'] == 'NO_SUPPORTED_INPUTS' for row in result['sources']))

    def test_source_mutation_during_tool_run_aborts_pinned_baseline(self):
        def mutate(argv,user,timeout,maximum=review.MAX_OUTPUT):
            result = self.fake_run(argv,user,timeout,maximum)
            if argv[-1] != review.IDENTITY_CODE and argv[-1] != '--version':
                (self.project / 'src/Main.kt').write_text('changed during scan')
            return result
        with self.assertRaises(ValueError):
            review.evaluate(self.directory,Path('/installed/python'),Path('/installed/semgrep'),['example'],run=mutate)

    @unittest.skipUnless(os.name == 'posix','POSIX fixed-scanner bridge')
    def test_installed_sdk_bridge_runs_only_trusted_fake_scanner_without_project_execution(self):
        scanner = self.directory / 'trusted-semgrep'
        scanner.write_text('#!' + sys.executable + '\n' + '''import json,sys
if sys.argv[1:] == ['--version']:
    print('1.181.0')
else:
    assert '--disable-nosem' in sys.argv and '--no-git-ignore' in sys.argv
    assert '--metrics' in sys.argv and '--disable-version-check' in sys.argv
    print(json.dumps({'version':'2.1.0','runs':[{'tool':{'driver':{'name':'Semgrep'}},'results':[]}]}))
''')
        scanner.chmod(0o700)
        result = review.evaluate(self.directory,Path(sys.executable),scanner,['example'])
        self.assertTrue(result['allConfiguredScansComplete'])
        self.assertEqual('1.181.0',result['semgrepVersion'])
        self.assertEqual(5,len(result['sources']))
        self.assertEqual(self.source['files'],review.verified_source(self.directory,self.source))


if __name__ == '__main__': unittest.main()
