const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const Module = require('node:module');
const { execFileSync } = require('node:child_process');

const original = Module._load;
let pythonVersion;
try {
  Module._load = function(id, ...args) { return id === 'vscode' ? {} : original.call(this, id, ...args); };
  ({ pythonVersion } = require('../dist/methods/bootstrap'));
} finally { Module._load = original; }

test('guided SDK readiness rejects installed 0.2 and accepts 0.3 with isolated interpreter imports', async t => {
  const run = (executable, args) => execFileSync(executable, args, {
    encoding: 'utf8', timeout: 20000, stdio: ['ignore', 'pipe', 'ignore']
  }).trim();
  let systemPython;
  for (const candidate of [process.env.WRAPPERGUARD_TEST_PYTHON, process.env.WRAPPERGUARD_BUILD_PYTHON,
    'python3', 'python', '/opt/homebrew/bin/python3', '/usr/local/bin/python3'].filter(Boolean)) {
    try {
      systemPython = run(candidate, ['-I', '-c', 'import sys; assert sys.version_info >= (3,10); print(sys.executable)']);
      break;
    } catch { /* Try the next trusted test interpreter. */ }
  }
  assert.ok(systemPython, 'This interpreter-level regression requires Python 3.10+ (WRAPPERGUARD_TEST_PYTHON can select it).');
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'wg-bootstrap-'));
  t.after(() => fs.rm(directory, { recursive: true, force: true }));
  const project = path.join(directory, 'workspace');
  await fs.mkdir(project);
  const marker = path.join(directory, 'workspace-module-executed');
  await fs.writeFile(path.join(project, 'wrapperguard_sdk.py'),
    `from pathlib import Path\nPath(${JSON.stringify(marker)}).touch()\nraise RuntimeError("Workspace module must never be imported")\n`);
  const previousPythonPath = process.env.PYTHONPATH;
  process.env.PYTHONPATH = project;
  t.after(() => {
    if (previousPythonPath === undefined) delete process.env.PYTHONPATH;
    else process.env.PYTHONPATH = previousPythonPath;
  });
  const environment = path.join(directory, 'selected-environment');
  run(systemPython, ['-I', '-m', 'venv', '--without-pip', environment]);
  const selectedPython = path.join(environment, process.platform === 'win32' ? 'Scripts/python.exe' : 'bin/python');
  const version = run(selectedPython, ['-I', '-c', 'import sys; print(".".join(map(str,sys.version_info[:3])))']);
  const sitePackages = run(selectedPython, ['-I', '-c', 'import sysconfig; print(sysconfig.get_path("purelib"))']);
  const sdk = path.join(sitePackages, 'wrapperguard_sdk');
  await fs.mkdir(sdk, { recursive: true });
  await fs.writeFile(path.join(sdk, '__init__.py'), '__version__ = "0.2.0"\n');
  assert.equal(await pythonVersion(selectedPython, project), version, 'The selected Python itself remains supported.');
  assert.equal(await pythonVersion(selectedPython, project, true), undefined, 'SDK 0.2 cannot support guided 0.3 profiles.');
  await fs.rm(path.join(sdk, '__pycache__'), { recursive: true, force: true });
  await fs.writeFile(path.join(sdk, '__init__.py'), '__version__ = "0.3.0"\n');
  assert.equal(await pythonVersion(selectedPython, project, true), version, 'SDK 0.3 is accepted from the selected environment.');
  await assert.rejects(fs.access(marker), { code: 'ENOENT' });
});
