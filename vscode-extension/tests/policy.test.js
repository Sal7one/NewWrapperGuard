const test = require('node:test');
const assert = require('node:assert');
const { actionFor } = require('../dist/scanner/policy.js');
const { DEFAULT_ENFORCE_POLICY } = require('../dist/scanner/defaults.js');

function finding(overrides) {
  return {
    fingerprint: 'x'.repeat(24),
    ruleId: 'WG-TEST-001',
    title: 't',
    message: 't',
    severity: 'ERROR',
    confidence: 'HIGH',
    category: 'EXECUTION',
    kind: 'ACTIONABLE',
    location: { path: 'gradlew', line: 1 },
    reachable: true,
    callChain: [],
    policyAction: 'REVIEW',
    status: 'ACTIVE',
    ...overrides
  };
}

test('ENFORCE blocks actionable ERROR findings', () => {
  assert.strictEqual(actionFor(DEFAULT_ENFORCE_POLICY, finding({})), 'BLOCK');
});

test('ENFORCE only reviews WARN findings', () => {
  assert.strictEqual(actionFor(DEFAULT_ENFORCE_POLICY, finding({ severity: 'WARN' })), 'REVIEW');
});

test('INFO is always ALLOW', () => {
  assert.strictEqual(actionFor(DEFAULT_ENFORCE_POLICY, finding({ severity: 'INFO' })), 'ALLOW');
});

test('audit evidence never blocks', () => {
  assert.strictEqual(actionFor(DEFAULT_ENFORCE_POLICY, finding({ kind: 'AUDIT_EVIDENCE' })), 'ALLOW');
});

test('unreachable findings do not block', () => {
  assert.strictEqual(actionFor(DEFAULT_ENFORCE_POLICY, finding({ reachable: false })), 'REVIEW');
});

test('explicitly blocked rule ids block even at WARN', () => {
  const policy = { ...DEFAULT_ENFORCE_POLICY, blockedRuleIds: ['WG-TEST-001'] };
  assert.strictEqual(actionFor(policy, finding({ severity: 'WARN' })), 'BLOCK');
});
