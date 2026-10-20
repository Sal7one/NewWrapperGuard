from pathlib import Path
import re
import tempfile
import unittest

from wrapperguard_sdk.adapters import parse_report


class SonarIdentifierTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='wg-sonar-id-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def parse(self, engine, rule, message='Review this issue'):
        document = {'rules': [{'id': rule, 'engineId': engine, 'name': 'Source rule', 'severity': 'CRITICAL'}],
                    'issues': [{'ruleId': rule, 'primaryLocation': {'message': message, 'filePath': 'App.kt'}}]}
        return parse_report('sonar', document, self.root, ['WARNING'])

    def test_official_example_with_spaces_in_producer_imports_its_issue(self):
        result = self.parse('third-party analyzer 1', 'rule1')
        self.assertTrue(result['complete'])
        finding = result['findings'][0]
        self.assertEqual('ERROR', finding['severity'])
        self.assertIn('Producer: third-party analyzer 1; rule: rule1.', finding['message'])
        self.assertRegex(finding['ruleId'], r'^SONAR/.+/[0-9a-f]{64}$')

    def test_existing_unambiguous_host_identifiers_remain_readable(self):
        result = self.parse('vendor', 'security-rule')
        self.assertTrue(result['complete'])
        self.assertEqual('SONAR/vendor/security-rule', result['findings'][0]['ruleId'])

    def test_unicode_long_values_and_colliding_slugs_keep_distinct_stable_ids(self):
        pairs = [('a b', 'rule'), ('a?b', 'rule'), ('审查器', '安全规则'), ('x' * 500, 'y' * 500),
                 ('a/b', 'c'), ('a', 'b/c')]
        findings = [self.parse(engine, rule)['findings'][0] for engine, rule in pairs]
        identifiers = [finding['ruleId'] for finding in findings]
        self.assertEqual(len(pairs), len(set(identifiers)))
        self.assertTrue(all(len(value) <= 120 and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.:/-]*', value) for value in identifiers))
        self.assertEqual(identifiers, [self.parse(engine, rule)['findings'][0]['ruleId'] for engine, rule in pairs])

    def test_fallback_display_never_unmasks_credentials_or_overflows_message(self):
        finding = self.parse('vendor token=never-print-this', 'rule space', 'x' * 4090)['findings'][0]
        self.assertNotIn('never-print-this', str(finding))
        self.assertLessEqual(len(finding['message']), 4096)
        self.assertIn('Producer:', finding['message'])
        malformed = self.parse('vendor', 'rule', '')
        self.assertFalse(malformed['complete'])
        self.assertFalse(malformed['findings'])


if __name__ == '__main__':
    unittest.main()
