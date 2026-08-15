// Cross-checks the TypeScript scanner against the Kotlin CLI on the given fixtures.
// Usage: node scripts/parity.js [dir1 dir2 ...]   (defaults to demo + test-samples + repo root)
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const { scanProject } = require('../dist/scanner/repositoryScanner.js');
const { DEFAULT_ENFORCE_POLICY, DEFAULT_SETTINGS } = require('../dist/scanner/defaults.js');

const repoRoot = path.resolve(__dirname, '..', '..');
const CLI = path.join(repoRoot, 'wrapperguard-cli', 'build', 'install', 'wrapperguard-cli', 'bin', 'wrapperguard-cli');

const fs = require('node:fs');
const dirs = process.argv.slice(2);
if (dirs.length === 0) {
  dirs.push(
    '/tmp/wg-demo',
    path.join(repoRoot, 'old_project', 'test-samples'),
    repoRoot
  );
}

function runKotlin(dir) {
  try {
    return JSON.parse(execFileSync(CLI, [dir, '--json', '--enforce'], { maxBuffer: 30 * 1024 * 1024 }));
  } catch (e) {
    if (e && e.stdout) return JSON.parse(e.stdout);
    throw e;
  }
}

let failed = false;
for (const dir of dirs) {
  if (!fs.existsSync(dir)) {
    console.log('SKIP (missing): ' + dir);
    continue;
  }
  const k = runKotlin(dir);
  const t = scanProject(dir, DEFAULT_ENFORCE_POLICY, DEFAULT_SETTINGS, []);
  const kFp = new Set(k.findings.map((f) => f.fingerprint));
  const tFp = new Set(t.findings.map((f) => f.fingerprint));
  const onlyK = [...kFp].filter((x) => !tFp.has(x));
  const onlyT = [...tFp].filter((x) => !kFp.has(x));
  const kB = k.findings.filter((f) => f.policyAction === 'BLOCK' && f.status === 'ACTIVE').length;
  const tB = t.findings.filter((f) => f.policyAction === 'BLOCK' && f.status === 'ACTIVE').length;
  const ok = k.verdict === t.verdict && k.findings.length === t.findings.length && kB === tB && onlyK.length === 0 && onlyT.length === 0;
  failed = failed || !ok;
  console.log(
    (ok ? 'PARITY OK   ' : 'PARITY DIFF ') + dir +
    '  verdict=' + t.verdict + ' findings=' + t.findings.length + '/' + k.findings.length +
    ' blockers=' + tB + '/' + kB + ' fp-kotlin-only=' + onlyK.length + ' fp-ts-only=' + onlyT.length
  );
  if (!ok) {
    for (const fp of onlyK.slice(0, 5)) {
      const f = k.findings.find((x) => x.fingerprint === fp);
      console.log('  kotlin-only: ' + f.ruleId + ' ' + f.location.path + ':' + f.location.line);
    }
    for (const fp of onlyT.slice(0, 5)) {
      const f = t.findings.find((x) => x.fingerprint === fp);
      console.log('  ts-only    : ' + f.ruleId + ' ' + f.location.path + ':' + f.location.line);
    }
  }
}
process.exit(failed ? 1 : 0);
