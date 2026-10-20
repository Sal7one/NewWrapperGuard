const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { analyzeScript } = require('../dist/scanner/scriptRules.js');
const { scanProject } = require('../dist/scanner/repositoryScanner.js');
const { DEFAULT_SETTINGS, DEFAULT_ENFORCE_POLICY } = require('../dist/scanner/defaults.js');
function networkFindings(content) {
  return analyzeScript({relativePath:'tools/setup.sh',content}, 'UNIX', DEFAULT_SETTINGS)
    .filter(f => f.ruleId === 'WG-SCRIPT-003');
}

test('color assignments, quoted prose, arguments and comments are not network commands', () => {
  for (const line of [
    "NC='\\033[0m' # No Color", "export NC='\\033[0m'", 'readonly NC=plain',
    "MESSAGE='nc host 80'", 'MESSAGE="nc host 80"', "printf '%s' 'nc'",
    'echo nc', "printf 'network nc curl wget'", 'echo ok # nc host 80', '# nc host 80',
    "printf '%s' '# nc'", "printf '%s' '$(nc host 80)'", 'cat < nc', 'echo foo#nc'
  ]) assert.equal(networkFindings(line).length, 0, line);
});

test('real command tokens, wrappers, separators and substitutions keep evidence', () => {
  for (const line of [
    'nc host 80', "'nc' host 80", '"nc" host 80', 'n\\c host 80', 'NC=plain nc host 80',
    'echo ok; nc host 80', 'echo ok && nc host 80', 'echo data | nc host 80',
    'if true; then nc host 80; fi', 'env MODE=test nc host 80', 'command -- nc host 80',
    'sudo nc host 80', '2>/dev/null nc host 80', 'value=$(nc host 80)',
    'printf \'%s\' "$(nc host 80)"', 'echo `nc host 80`', '(nc host 80)'
  ]) {
    const findings = networkFindings(line);
    assert.equal(findings.length, 1, line);
    assert.equal(findings[0].evidence.text, line, line);
    assert.equal(findings[0].location.line, 1, line);
  }
});

test('deep substitution is bounded and conservatively reports network code', () => {
  assert.equal(networkFindings('$('.repeat(40) + 'nc host 80' + ')'.repeat(40)).length, 1);
});

test('external nightly artifact path stays unresolved and incomplete', t => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'wg-nightly-external-'));
  t.after(() => fs.rmSync(root, {recursive:true, force:true}));
  fs.writeFileSync(path.join(root,'nightly.sh'), 'bash $KOKORO_ARTIFACTS_DIR/git/nowinandroid/kokoro/build.sh "walleye" "27"\n');
  const report = scanProject(root, DEFAULT_ENFORCE_POLICY, DEFAULT_SETTINGS, []);
  assert.equal(report.complete, false);
  assert.ok(report.findings.some(f => f.ruleId === 'WG-SCAN-UNRESOLVED'));
  assert.ok(report.notices.some(n => n.code === 'WG-FILE-SKIPPED'));
  assert.equal(report.edges.length, 0);
});
