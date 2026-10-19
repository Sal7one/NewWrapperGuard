#!/usr/bin/env node
'use strict';

// Exercise the installed Python SDK through the actual TypeScript command bridge.
// Reports and source files below are inert protocol fixtures, not vendor scan results.
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { loadCommandProviders } = require('../dist/providers/commandProvider');
const { evaluateProviders } = require('../dist/providers/coordinator');
const { DEFAULT_SETTINGS, DEFAULT_ENFORCE_POLICY } = require('../dist/scanner/defaults');

async function main() {
  const args = process.argv.slice(2);
  if (args.length !== 2 || args[0] !== '--sdk-python' || !path.isAbsolute(args[1])) {
    throw new Error('Usage: node scripts/sdk-smoke.js --sdk-python /absolute/installed/python');
  }
  const python = args[1];
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'wg-ts-sdk-'));
  try {
    const project = path.join(directory, 'project');
    await fs.mkdir(path.join(project, 'src'), { recursive: true });
    await fs.writeFile(path.join(project, 'src/sample.py'), '# Static protocol fixture; never executed.\n');
    const reportPath = path.join(directory, 'bandit.json');
    const report = {
      errors: [],
      metrics: { 'src/sample.py': { loc: 1 }, _totals: { loc: 1 } },
      results: [{ filename: 'src/sample.py', line_number: 1, test_id: 'B602',
        test_name: 'subprocess_popen_with_shell_equals_true',
        issue_text: 'Fixture finding for host authorization checks.',
        issue_severity: 'HIGH', issue_confidence: 'HIGH',
        code: 'token = "SDK_TEST_SECRET"\n' }]
    };
    await fs.writeFile(reportPath, JSON.stringify(report));
    const firstProfile = path.join(directory, 'warning.json');
    const secondProfile = path.join(directory, 'blocking.json');
    const profile = id => ({ id, displayName: id, version: '1.0.0', analysisKind: 'SAST',
      format: 'bandit', reportPath, allowedOperations: ['SCAN', 'BUILD'],
      requestedActions: ['WARNING', 'BLOCK_BUILD'] });
    await fs.writeFile(firstProfile, JSON.stringify(profile('sdk.node.warning')));
    await fs.writeFile(secondProfile, JSON.stringify(profile('sdk.node.blocking')));
    const configuration = path.join(directory, 'providers.json');
    const provider = (id, profilePath, actions) => ({ id, displayName: id, version: '1.0.0',
      command: [python, '-I', '-m', 'wrapperguard_sdk', '--profile', profilePath],
      permissions: { enabled: true, required: true, timeoutMs: 10000, actions } });
    const config = { apiVersion: 1, builtIn: { enabled: false }, providers: [
      provider('sdk.node.warning', firstProfile, ['WARNING']),
      provider('sdk.node.blocking', secondProfile, ['BLOCK_BUILD'])
    ] };
    const evaluate = async operation => {
      await fs.writeFile(configuration, JSON.stringify(config));
      const loaded = await loadCommandProviders(configuration, project, true);
      return evaluateProviders({ projectRoot: await fs.realpath(project),
        operation, signal: new AbortController().signal, settings: DEFAULT_SETTINGS,
        policy: DEFAULT_ENFORCE_POLICY, baseline: [] },
      { providers: loaded.providers, permissions: loaded.permissions, concurrency: 2 });
    };
    const build = await evaluate('BUILD');
    assert.equal(build.complete, true);
    assert.equal(build.findings.length, 2);
    assert.deepEqual(build.providerRuns.map(run => run.status), ['COMPLETED', 'COMPLETED']);
    const warning = build.findings.find(f => f.providerId === 'sdk.node.warning');
    const blocking = build.findings.find(f => f.providerId === 'sdk.node.blocking');
    assert.deepEqual(warning.actions, ['WARNING']);
    assert.equal(warning.policyAction, 'REVIEW');
    assert.deepEqual(blocking.actions, ['BLOCK_BUILD']);
    assert.equal(blocking.policyAction, 'BLOCK');
    assert.notEqual(warning.fingerprint, blocking.fingerprint);
    assert.ok(!JSON.stringify(build).includes('SDK_TEST_SECRET'), 'Raw code must not leak through report adaptation.');
    const sync = await evaluate('SYNC');
    assert.equal(sync.complete, false);
    assert.equal(sync.verdict, 'INCOMPLETE');
    assert.equal(sync.findings.length, 0, 'An unauthorized operation must not import reports.');
    await fs.writeFile(reportPath, '{invalid JSON');
    const malformed = await evaluate('BUILD');
    assert.equal(malformed.complete, false);
    assert.ok(malformed.providerRuns.every(run => run.status === 'FAILED'));
    const inside = path.join(project, 'untrusted-profile.json');
    await fs.writeFile(inside, JSON.stringify(profile('sdk.node.warning')));
    config.providers = [provider('sdk.node.warning', inside, ['WARNING'])];
    const untrusted = await evaluate('BUILD');
    assert.equal(untrusted.complete, false);
    assert.equal(untrusted.providerRuns[0].status, 'FAILED');
    console.log('Installed SDK / TypeScript host: concurrent providers, action grants, operation denial, malformed reports, redaction and profile trust passed.');
  } finally {
    await fs.rm(directory, { recursive: true, force: true });
  }
}

main().catch(error => { console.error(error.message); process.exitCode = 1; });
