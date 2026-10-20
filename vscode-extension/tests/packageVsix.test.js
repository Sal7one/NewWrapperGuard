'use strict';
const assert=require('node:assert/strict');
const test=require('node:test');
const {packageArguments}=require('../scripts/package-vsix');

test('packaged monorepo README links resolve inside the exact source commit',()=>{
  const commit='76312ace4306c906c329e408346a59d2e893f3c7';
  const args=packageArguments(commit,'0.7.0');
  const content=args[args.indexOf('--baseContentUrl')+1]+'/';
  const images=args[args.indexOf('--baseImagesUrl')+1]+'/';
  assert.equal(args[args.indexOf('--out')+1],'wrapperguard-0.7.0.vsix');
  for(const relative of ['../docs/integrations.md','../examples/provider-conformance/README.md',
    '../demo/screenshots/flexible-provider-platform/vscode/README.md#six-final-ci-host-jobs']){
    const resolved=new URL(relative,content);
    assert.equal(resolved.origin,'https://github.com');
    assert.ok(resolved.pathname.startsWith('/Sal7one/NewWrapperGaurd/blob/'+commit+'/'));
    assert.ok(!resolved.pathname.includes('/vscode-extension/'));
  }
  assert.equal(new URL('resources/shield.svg',images).href,
    'https://raw.githubusercontent.com/Sal7one/NewWrapperGaurd/'+commit+'/vscode-extension/resources/shield.svg');
});

test('package link identity rejects floating or malformed source revisions',()=>{
  for(const revision of ['HEAD','master','25bec78','../master','x'.repeat(40),'76312ac\n'])
    assert.throws(()=>packageArguments(revision,'0.7.0'),/exact Git source commit/);
});
