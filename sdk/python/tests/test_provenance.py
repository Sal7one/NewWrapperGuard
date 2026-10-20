from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from wrapperguard_sdk.cli import evaluate, build_plan
from wrapperguard_sdk.profile import load_profile
from wrapperguard_sdk.provenance import create_manifest
from wrapperguard_sdk.dependency_coverage import summarize


class ProvenanceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.user = Path(self.temp.name).resolve(); self.root = self.user / 'project'; self.root.mkdir()
        (self.root / 'build.gradle.kts').write_text('// inert declared source')
        self.report = self.user / 'report.json'
        self.report.write_text(json.dumps({'bomFormat': 'CycloneDX', 'specVersion': '1.7',
            'components': [{'bom-ref': 'component', 'name': 'fixture', 'version': '1.0'}], 'vulnerabilities': []}))
        self.manifest = self.user / 'manifest.json'; self.write_manifest()
        self.profile_file = self.user / 'profile.json'
        self.value = {'id': 'user.sca', 'displayName': 'Declared report', 'version': '1', 'analysisKind': 'SCA',
            'format': 'cyclonedx', 'reportPath': str(self.report), 'reportProvenance': {'manifestPath': str(self.manifest)},
            'dependencyCoverage': {'requireResolvedVersions': True}}

    def write_manifest(self, **change):
        document = create_manifest(self.report.read_bytes(), self.root, ['build.gradle.kts'],
            producer={'name': 'reviewed-ci', 'version': '1'}, scope='Listed build input only',
            sourceIdentity={'revision': 'reviewed-revision'}, database={'version': 'db-1'})
        document.update(change); self.manifest.write_text(json.dumps(document)); return document

    def load(self):
        self.profile_file.write_text(json.dumps(self.value)); return load_profile(self.profile_file, self.root)

    def test_valid_import_verifies_inputs_without_claiming_producer_authenticity_or_full_coverage(self):
        result = evaluate(self.load(), self.root, 'SCAN')
        self.assertTrue(result['complete']); self.assertEqual([], result['findings'])
        notice = next(n for n in result['notices'] if n['code'] == 'WG-REPORT-PROVENANCE')
        self.assertIn('1 listed input digests', notice['message']); self.assertIn('reviewed-ci', notice['message'])
        self.assertIn('does not prove authenticity', notice['message'])
        self.assertIn('full transitive resolution', result['notices'][-1]['message'])

    def test_report_changes_source_changes_missing_sources_staleness_and_future_dates_fail(self):
        profile = self.load()
        original = self.report.read_bytes(); self.report.write_bytes(original + b' ')
        with self.assertRaises(ValueError): evaluate(profile, self.root, 'SCAN')
        self.report.write_bytes(original); (self.root / 'build.gradle.kts').write_text('// changed')
        with self.assertRaises(ValueError): evaluate(profile, self.root, 'SCAN')
        (self.root / 'build.gradle.kts').unlink()
        with self.assertRaises(OSError): evaluate(profile, self.root, 'SCAN')
        (self.root / 'build.gradle.kts').write_text('// restored')
        for delta in (timedelta(days=-2), timedelta(days=2)):
            self.write_manifest(generatedAt=(datetime.now(timezone.utc)+delta).isoformat().replace('+00:00', 'Z'))
            with self.assertRaises(ValueError): evaluate(profile, self.root, 'SCAN')

    def test_unknown_metadata_and_old_database_are_visible_without_dropping_existing_findings(self):
        self.write_manifest(database={'updatedAt': '2020-01-01T00:00:00Z', 'version': 'token=do-not-leak'})
        result = evaluate(self.load(), self.root, 'SCAN')
        self.assertTrue(result['complete']); self.assertIn('WG-REPORT-DATABASE-AGE', [n['code'] for n in result['notices']])
        self.assertNotIn('do-not-leak', json.dumps(result))
        document = self.write_manifest(); del document['producer']; self.manifest.write_text(json.dumps(document))
        result = evaluate(self.load(), self.root, 'SCAN')
        self.assertIn('producer: unknown', next(n for n in result['notices'] if n['code'] == 'WG-REPORT-PROVENANCE')['message'])
        document['database'] = None; self.manifest.write_text(json.dumps(document))
        self.assertTrue(evaluate(self.load(), self.root, 'SCAN')['complete'])

    def test_legacy_report_imports_surface_unknown_provenance_without_changing_scope_success(self):
        self.value.pop('reportProvenance')
        result = evaluate(self.load(), self.root, 'SCAN')
        self.assertTrue(result['complete'])
        self.assertIn('WG-REPORT-PROVENANCE-UNKNOWN', [notice['code'] for notice in result['notices']])

    @unittest.skipUnless(os.name == 'posix', 'POSIX symlink fixture')
    def test_symlink_file_parent_escape_and_hostile_manifest_paths_never_read_sentinel(self):
        outside = self.user / 'outside'; outside.mkdir(); (outside / 'sentinel').write_text('PRIVATE SENTINEL')
        (self.root / 'linked').symlink_to(outside, target_is_directory=True)
        for name in ('../outside/sentinel', 'linked/sentinel', '/outside/sentinel', 'build.gradle.kts/../sentinel'):
            document = self.write_manifest(inputs=[{'path': name, 'sha256': hashlib.sha256(b'PRIVATE SENTINEL').hexdigest()}])
            with self.subTest(name=name), self.assertRaises((OSError, ValueError)): evaluate(self.load(), self.root, 'SCAN')

    def test_strict_optional_profile_settings_and_review_plan_do_not_read_manifest_contents(self):
        with patch('wrapperguard_sdk.provenance.bounded_read', side_effect=AssertionError('no read')):
            self.assertIn('reportProvenance', build_plan(self.load(), self.root))
        for invalid in ({'manifestPath': str(self.manifest), 'maxAgeSeconds': True},
                        {'manifestPath': str(self.root / 'build.gradle.kts')}, {'manifestPath': 'relative'}):
            self.value['reportProvenance'] = invalid
            with self.assertRaises(ValueError): self.load()
        self.value.pop('reportProvenance'); self.value['dependencyCoverage']['requireResolvedVersions'] = 'yes'
        with self.assertRaises(ValueError): self.load()


class DependencyCoverageTests(unittest.TestCase):
    def test_osv_trivy_and_bom_include_safe_and_unresolved_records_in_coverage(self):
        for name, document in (
            ('osv', {'results': [{'packages': [{'package': {'version': '1.0'}}, {'package': {'version': '${unresolved}'}}]}]}),
            ('trivy', {'Results': [{'Packages': [{'Version': '1.0'}, {'Name': 'unresolved'}]}]}),
            ('cyclonedx', {'components': [{'version': '1.0', 'components': [{'version': '^2.0'}]}]})):
            with self.subTest(name=name):
                notices, incomplete = summarize(name, document, require_resolved=True)
                self.assertTrue(incomplete); self.assertIn('2 records, 1 with fixed', notices[0]['message'])
                self.assertFalse(summarize(name, document, require_resolved=False)[1])

    def test_unknown_inventory_zero_records_and_limits_cannot_satisfy_required_resolution(self):
        for name, document in (('trivy', {'Results': [{}]}), ('osv', {'results': []}),
                               ('cyclonedx', {'components': [{'version': '1'}] * 10001})):
            self.assertTrue(summarize(name, document, require_resolved=True)[1])
        self.assertTrue(summarize('cyclonedx', {'components': [{'version': '1'}]}, require_resolved=True)[1] is False)


if __name__ == '__main__': unittest.main()
