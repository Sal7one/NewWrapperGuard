// Cross-checks the TypeScript scanner against the Kotlin CLI on the given fixtures.
// Usage: node scripts/parity.js [dir1 dir2 ...]   (defaults to tracked scanner fixtures + repo root)
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const { evaluateProviders } = require('../dist/providers/coordinator.js');
const { scanProject } = require('../dist/scanner/repositoryScanner.js');
const { DEFAULT_ENFORCE_POLICY, DEFAULT_SETTINGS } = require('../dist/scanner/defaults.js');

const repoRoot = path.resolve(__dirname, '..', '..');
const CLI = path.join(repoRoot, 'wrapperguard-cli', 'build', 'install', 'wrapperguard-cli', 'bin', 'wrapperguard-cli');

const fs = require('node:fs');
const dirs = process.argv.slice(2);
if (dirs.length === 0) {
  dirs.push(
    path.join(repoRoot, 'test-fixtures', 'scanner'),
    repoRoot
  );
}

function runKotlin(dir) {
  try {
    return JSON.parse(execFileSync(CLI, [dir, '--json', '--enforce'], { maxBuffer: 30 * 1024 * 1024 }));
  } catch (e) {
    if (e && e.status === 2 && e.stdout) return JSON.parse(e.stdout);
    throw e;
  }
}

async function main() {
let failed = false;
let fixturesRun = 0;
for (const inputDir of dirs) {
  const dir = path.resolve(inputDir);
  if (!fs.existsSync(dir)) {
    console.error('FAIL (missing fixture): ' + dir);
    failed = true;
    continue;
  }
  fixturesRun++;
  const k = runKotlin(dir);
  const t = await evaluateProviders({projectRoot:dir,policy:DEFAULT_ENFORCE_POLICY,settings:DEFAULT_SETTINGS,baseline:[],operation:'SCAN',signal:new AbortController().signal},{providers:[{descriptor:{id:'wrapperguard',displayName:'WrapperGuard',version:'0.4.0',apiVersion:1},scan:request=>scanProject(request.projectRoot,request.policy,request.settings,request.baseline)}]});
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
if (fixturesRun === 0) { console.error('FAIL: no fixtures were scanned.'); failed = true; }
process.exitCode = failed ? 1 : 0;
}
main().catch(error => { console.error(error.message); process.exitCode = 1; });
