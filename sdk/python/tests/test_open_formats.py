from pathlib import Path
import json
import tempfile
import unittest

from wrapperguard_sdk.adapters import parse_report
from wrapperguard_sdk.cli import build_plan
from wrapperguard_sdk.profile import load_profile


class OpenFormatTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='wg-open-report-')
        self.addCleanup(self.temp.cleanup)
        self.user = Path(self.temp.name).resolve(); self.root = self.user / 'project'; self.root.mkdir()
        (self.root / 'App.kt').write_text('// inert source\n')

    def bom(self):
        return {'bomFormat': 'CycloneDX', 'specVersion': '1.7',
                'components': [{'bom-ref': 'pkg:maven/test/component@1.0', 'name': 'component', 'version': '1.0'}],
                'vulnerabilities': [{'id': 'CVE-2021-44228', 'description': '<p>Dependency review</p>',
                    'ratings': [{'severity': 'critical'}], 'affects': [{'ref': 'pkg:maven/test/component@1.0'}]}]}

    def parse(self, format, value):
        return parse_report(format, value, self.root, ['WARNING', 'BLOCK_BUILD'])

    def test_cyclonedx_vulnerability_is_attributed_without_fetching_references(self):
        result = self.parse('cyclonedx', self.bom())
        self.assertTrue(result['complete']); self.assertEqual(len(result['findings']), 1)
        finding = result['findings'][0]
        self.assertEqual(finding['ruleId'], 'CVE-2021-44228')
        self.assertEqual(finding['severity'], 'ERROR')
        self.assertEqual(finding['location']['path'], '.')
        self.assertIn('component@1.0', finding['message'])
        self.assertNotIn('<p>', finding['message'])

    def test_unknown_cyclonedx_rating_remains_a_review_warning(self):
        bom = self.bom(); bom['vulnerabilities'][0]['ratings'] = [{'severity': 'unknown'}]
        result = self.parse('cyclonedx', bom)
        self.assertEqual(result['findings'][0]['severity'], 'WARN')

    def test_large_component_inventory_stops_iteration_at_the_bound(self):
        class LargeInventory(list):
            visited = 0
            def __iter__(self):
                for index in range(100_000):
                    self.visited += 1
                    yield {'bom-ref': 'component-' + str(index), 'name': 'fixture'}
        inventory = LargeInventory()
        bom = self.bom(); bom['components'] = inventory; bom['vulnerabilities'] = []
        result = self.parse('cyclonedx', bom)
        self.assertFalse(result['complete'])
        self.assertIn('WG-REPORT-LIMIT', [notice['code'] for notice in result['notices']])
        self.assertLessEqual(inventory.visited, 10001)

    def test_inventory_without_vulnerability_assessment_is_incomplete(self):
        bom = self.bom(); del bom['vulnerabilities']
        result = self.parse('cyclonedx', bom)
        self.assertFalse(result['complete']); self.assertEqual(result['findings'], [])
        self.assertEqual(result['notices'][0]['code'], 'WG-SBOM-INVENTORY')

    def test_vex_does_not_create_a_trusted_exception(self):
        bom = self.bom(); bom['vulnerabilities'][0]['analysis'] = {'state': 'not_affected', 'justification': 'code_not_reachable'}
        result = self.parse('cyclonedx', bom); finding = result['findings'][0]
        self.assertEqual(finding.get('kind', 'ACTIONABLE'), 'ACTIONABLE')
        self.assertIn('BLOCK_BUILD', finding['actions'])
        self.assertIn('not a host-approved exception', finding['message'])

    def test_invalid_component_refs_and_duplicate_inventory_are_incomplete(self):
        bom = self.bom(); bom['vulnerabilities'][0]['affects'][0]['ref'] = 'unknown'
        self.assertFalse(self.parse('cyclonedx', bom)['complete'])
        bom = self.bom(); bom['components'].append(dict(bom['components'][0]))
        self.assertFalse(self.parse('cyclonedx', bom)['complete'])

    def test_unsupported_cyclonedx_envelope_never_looks_clean(self):
        for version in ('2.0', None):
            bom = self.bom(); bom['specVersion'] = version
            with self.assertRaises(ValueError): self.parse('cyclonedx', bom)

    def test_current_sonar_rules_and_impacts_map_zero_based_columns(self):
        value = {'rules': [{'id': 'security-rule', 'engineId': 'example', 'name': 'Review server trust',
                    'impacts': [{'softwareQuality': 'SECURITY', 'severity': 'HIGH'}]}],
                 'issues': [{'ruleId': 'security-rule', 'primaryLocation': {
                     'message': 'Review the configured trust boundary', 'filePath': 'App.kt',
                     'textRange': {'startLine': 1, 'startColumn': 0}}}]}
        result = self.parse('sonar', value)
        self.assertTrue(result['complete']); finding = result['findings'][0]
        self.assertEqual(finding['ruleId'], 'SONAR/example/security-rule')
        self.assertEqual(finding['severity'], 'ERROR'); self.assertEqual(finding['location']['column'], 1)

    def test_legacy_sonar_format_remains_supported(self):
        value = {'issues': [{'engineId': 'external', 'ruleId': 'review', 'severity': 'MAJOR', 'type': 'VULNERABILITY',
                  'primaryLocation': {'message': 'Review this source', 'filePath': 'App.kt'}}]}
        result = self.parse('sonar', value)
        self.assertTrue(result['complete']); self.assertEqual(result['findings'][0]['severity'], 'WARN')

    def test_sonar_unknown_severity_and_escaping_paths_are_incomplete(self):
        value = {'issues': [{'engineId': 'external', 'ruleId': 'review', 'severity': 'UNKNOWN',
                  'primaryLocation': {'message': 'Review', 'filePath': 'App.kt'}}]}
        result = self.parse('sonar', value)
        self.assertFalse(result['complete']); self.assertEqual(result['findings'][0]['actions'], [])
        value['issues'][0]['primaryLocation']['filePath'] = '../outside.kt'
        result = self.parse('sonar', value)
        self.assertFalse(result['complete']); self.assertFalse(result['findings'])

    def test_malformed_open_format_never_returns_clean_success(self):
        for format in ('sonar', 'cyclonedx'):
            with self.assertRaises(ValueError): self.parse(format, {'unrelated': []})

    def test_optional_profile_project_binding_is_checked_before_report_reads(self):
        path = self.user / 'profile.json'
        base = {'id': 'sca.user', 'displayName': 'User report', 'version': '1', 'analysisKind': 'SCA',
                'format': 'cyclonedx', 'reportPath': str(self.user / 'missing-report.json'), 'projectRoot': str(self.root)}
        path.write_text(json.dumps(base))
        profile = load_profile(path, self.root)
        self.assertEqual(build_plan(profile, self.root)['projectBinding'], 'explicit')
        other = self.user / 'other'; other.mkdir()
        with self.assertRaises(ValueError): load_profile(path, other)
        with self.assertRaises(ValueError): build_plan(profile, other)
        del base['projectRoot']; path.write_text(json.dumps(base))
        self.assertEqual(build_plan(load_profile(path, other), other)['projectBinding'], 'unbound')


if __name__ == '__main__': unittest.main()
