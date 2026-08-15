const test = require('node:test');
const assert = require('node:assert');
const { maskSecretValues } = require('../dist/scanner/redaction.js');

test('masks quoted secret assignments', () => {
  const { masked, changed } = maskSecretValues('password = "hunter2"');
  assert.strictEqual(masked, 'password = "[redacted]"');
  assert.strictEqual(changed, true);
});

test('masks bare secret assignments', () => {
  const { masked } = maskSecretValues('token=abc123def456');
  assert.strictEqual(masked, 'token=[redacted]');
});

test('masks authorization bearer tokens', () => {
  const { masked } = maskSecretValues('Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.abc');
  assert.strictEqual(masked, 'Authorization: Bearer [redacted]');
});

test('masks url userinfo passwords', () => {
  const { masked } = maskSecretValues('https://deploy:hunter2@evil.example.com/repo');
  assert.strictEqual(masked, 'https://deploy:[redacted]@evil.example.com/repo');
});

test('leaves benign text untouched', () => {
  const input = 'implementation("com.android.tools.build:gradle:8.7.0")';
  const { masked, changed } = maskSecretValues(input);
  assert.strictEqual(masked, input);
  assert.strictEqual(changed, false);
});

test('is idempotent', () => {
  const once = maskSecretValues('token = "abc123"').masked;
  assert.strictEqual(maskSecretValues(once).masked, once);
});
