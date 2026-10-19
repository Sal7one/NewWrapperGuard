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

test('masks every quoted assignment in a multiline credentials block', () => {
  const input = 'credentials {\n  username = "demo-service-account"\n  password = "DEMO_NOT_A_REAL_SECRET"\n}';
  const { masked, changed } = maskSecretValues(input);
  assert.strictEqual(masked, 'credentials {\n  username = "[redacted]"\n  password = "[redacted]"\n}');
  assert.strictEqual(changed, true);
  assert.strictEqual(maskSecretValues(masked).masked, masked);
});

test('shrinking a credential block does not skip the next block or its nested assignments', () => {
  const input = 'credentials { username = "' + 'a'.repeat(100) + '" password = "FIRST_DEMO_PASSWORD" }\n' +
    'credentials { identity { accountName = "second-demo-user" } password = "SECOND_DEMO_PASSWORD" }';
  const expected = 'credentials { username = "[redacted]" password = "[redacted]" }\n' +
    'credentials { identity { accountName = "[redacted]" } password = "[redacted]" }';
  const { masked, changed } = maskSecretValues(input);
  assert.strictEqual(masked, expected);
  assert.strictEqual(changed, true);
  assert.strictEqual(maskSecretValues(masked).masked, expected);
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
