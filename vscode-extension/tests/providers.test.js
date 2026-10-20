const test = require('node:test');
const assert = require('node:assert/strict');
const {ProviderRegistry,DEFAULT_PERMISSIONS,EXTERNAL_PERMISSIONS,validatePermissions} = require('../dist/providers/sdk');
const {evaluateProviders} = require('../dist/providers/coordinator');
const {userSetting} = require('../dist/providers/userSettings');
const {toSarif} = require('../dist/scanner/sarif');
const {DEFAULT_SETTINGS,DEFAULT_ENFORCE_POLICY} = require('../dist/scanner/defaults');

const finding = overrides => ({fingerprint:'forged',providerId:'wrapperguard',ruleId:'TEST',title:'Test',message:'A finding',severity:'ERROR',confidence:'HIGH',category:'EXECUTION',kind:'ACTIONABLE',location:{path:'gradlew',line:1},reachable:true,policyAction:'BLOCK',status:'SUPPRESSED',actions:['WARNING','BLOCK_BUILD','BLOCK_SYNC'],...overrides});
const provider = (id,scan) => ({descriptor:{id,displayName:id,version:'1',apiVersion:1},scan});
const request = overrides => ({projectRoot:'/tmp/project',policy:DEFAULT_ENFORCE_POLICY,settings:DEFAULT_SETTINGS,baseline:[],operation:'SCAN',signal:new AbortController().signal,...overrides});
const permissions = overrides => ({...DEFAULT_PERMISSIONS,actions:[...DEFAULT_PERMISSIONS.actions],...overrides});
const run = (p,grant,overrides) => evaluateProviders(request(overrides),{providers:[p],permissions:grant ? {[p.descriptor.id]:grant} : {}});

test('external providers are disabled until the user opts in and remain visible in provider runs',async()=>{
  let called=false;
  const report=await run(provider('disabled',()=>{called=true;return {findings:[finding()]};}));
  assert.equal(called,false);assert.equal(report.providerRuns[0].status,'DISABLED');assert.equal(report.providerRuns[0].descriptor.id,'disabled');assert.deepEqual(report.providerRuns[0].permissions.actions,[]);
});
test('enabled external providers have no implicit action grants; forged status and provenance are ignored',async () => {
  const report=await run(provider('third.party',() => ({findings:[finding()]})),{...EXTERNAL_PERMISSIONS,enabled:true});
  const f=report.findings[0];
  assert.equal(f.providerId,'third.party');assert.equal(f.status,'ACTIVE');assert.equal(f.ruleId,'TEST');
  assert.equal(f.policyAction,'ALLOW');assert.deepEqual(f.actions,[]);assert.notEqual(f.fingerprint,'forged');
});
test('minimal SDK findings receive host defaults without invented report state',async()=>{
  const p=provider('minimal',()=>({findings:[{ruleId:'MINIMAL',title:'Minimal finding',message:'Inspect this file',severity:'ERROR',confidence:'HIGH',category:'EXECUTION',location:{path:'gradlew'},actions:['BLOCK_BUILD']}]}));
  const report=await run(p,permissions(),{operation:'BUILD'});const f=report.findings[0];
  assert.equal(report.verdict,'EXECUTION_BLOCKED');assert.equal(f.providerId,'minimal');assert.equal(f.status,'ACTIVE');assert.equal(f.kind,'ACTIONABLE');assert.equal(f.reachable,true);assert.deepEqual(f.callChain,[]);assert.match(f.fingerprint,/^[a-f0-9]{24}$/);assert.deepEqual(f.actions,['BLOCK_BUILD']);
});
test('grants clamp requested actions and respect build versus sync',async () => {
  const p=provider('custom',() => ({findings:[finding()]}));
  const build=await run(p,permissions({actions:['BLOCK_BUILD']}),{operation:'BUILD'});
  assert.equal(build.verdict,'EXECUTION_BLOCKED');assert.deepEqual(build.findings[0].actions,['BLOCK_BUILD']);
  const sync=await run(p,permissions({actions:['BLOCK_BUILD']}),{operation:'SYNC'});
  assert.notEqual(sync.verdict,'EXECUTION_BLOCKED');
  const scan=await run(p,permissions({actions:['BLOCK_SYNC']}));assert.equal(scan.verdict,'EXECUTION_BLOCKED');
});
test('INFO, audit evidence, ML shadow, and host WARN policy never block',async () => {
  for(const f of [finding({severity:'INFO'}),finding({kind:'AUDIT_EVIDENCE'}),finding({category:'ML_SHADOW'}),finding({mlSignal:{score:1,modelId:'test',labels:[]}})]) {
    const r=await run(provider('custom',() => ({findings:[f]})),permissions(),{policy:{...DEFAULT_ENFORCE_POLICY,minimumBlockingSeverity:'INFO',blockedRuleIds:['custom:TEST']}});
    assert.notEqual(r.verdict,'EXECUTION_BLOCKED');assert.ok(!r.findings[0].actions.some(a=>a.startsWith('BLOCK')));
  }
  const r=await run(provider('custom',()=>({findings:[finding()]})),permissions(),{policy:{...DEFAULT_ENFORCE_POLICY,mode:'WARN'}});
  assert.notEqual(r.verdict,'EXECUTION_BLOCKED');
});
test('host baseline applies only after external fingerprint is namespaced',async () => {
  const p=provider('custom',()=>({findings:[finding()]}));
  const r=await run(p,permissions());const f=r.findings[0];
  const approved=await run(p,permissions(),{baseline:[{fingerprint:f.fingerprint,ruleId:f.ruleId}]});
  assert.equal(approved.findings[0].status,'SUPPRESSED');assert.equal(approved.findings[0].policyAction,'ALLOW');
  const forged=await run(p,permissions(),{baseline:[{fingerprint:'forged'}]});assert.equal(forged.verdict,'EXECUTION_BLOCKED');
});
test('external identity normalizes in-root absolute paths and fingerprints unredacted evidence',async()=>{
  const p=provider('custom',()=>({findings:[finding({location:{path:'/tmp/project/sub/../gradlew'},evidence:{text:'token=PRIVATE',redacted:false}})]}));
  const report=await run(p,permissions());const f=report.findings[0];
  assert.equal(f.location.path,'gradlew');assert.equal(f.ruleId,'TEST');
  assert.equal(f.fingerprint,'1bab69b5101fce7efaf9bdfd');assert.ok(!f.evidence.text.includes('PRIVATE'));assert.equal(f.evidence.redacted,true);
});
test('required failures close only granted operation gates and optional failures remain incomplete',async () => {
  const p=provider('broken',()=>{throw new Error('token=DO_NOT_LEAK');});
  const required=await run(p,permissions({actions:['BLOCK_BUILD']}),{operation:'BUILD'});
  assert.equal(required.verdict,'EXECUTION_BLOCKED');assert.equal(required.providerRuns[0].status,'FAILED');assert.ok(!JSON.stringify(required).includes('DO_NOT_LEAK'));
  const sync=await run(p,permissions({actions:['BLOCK_BUILD']}),{operation:'SYNC'});assert.equal(sync.verdict,'INCOMPLETE');
  const optional=await run(p,permissions({required:false}));assert.equal(optional.verdict,'INCOMPLETE');assert.equal(optional.findings.length,0);
  const partial=await run(provider('partial',()=>({findings:[],complete:false})),permissions());assert.equal(partial.verdict,'EXECUTION_BLOCKED');assert.equal(partial.findings[0].ruleId,'WG-PROVIDER-UNAVAILABLE');
  const noGrant=await run(p,permissions({actions:[]}));assert.equal(noGrant.verdict,'INCOMPLETE');
});
test('timeouts abort cooperative work, and completion timing preserves registration order',async () => {
  let aborted=false;
  const slow=provider('z-slow',req=>new Promise(resolve=>req.signal.addEventListener('abort',()=>{aborted=true;resolve({findings:[]});})));
  const fast=provider('a-fast',async()=>({findings:[]}));
  const report=await evaluateProviders(request(),{providers:[slow,fast],permissions:{'z-slow':permissions({timeoutMs:100,required:false}),'a-fast':{...EXTERNAL_PERMISSIONS,enabled:true}},concurrency:2});
  assert.ok(aborted);assert.deepEqual(report.providerRuns.map(p=>p.descriptor.id),['z-slow','a-fast']);assert.equal(report.providerRuns[0].status,'TIMED_OUT');
});
test('provider concurrency is bounded and more than one provider runs at once',async () => {
  let active=0,max=0;
  const providers=Array.from({length:6},(_,i)=>provider('provider-'+i,async()=>{active++;max=Math.max(max,active);await new Promise(r=>setTimeout(r,10));active--;return {findings:[]};}));
  const r=await evaluateProviders(request(),{providers,permissions:Object.fromEntries(providers.map(p=>[p.descriptor.id,{...EXTERNAL_PERMISSIONS,enabled:true}])),concurrency:2});assert.equal(r.complete,true);assert.equal(max,2);
});
test('deadlines include queue time and expired queued providers are never invoked',async()=>{
  const called=[];
  const providers=[provider('first',async()=>{called.push('first');await new Promise(resolve=>setTimeout(resolve,140));return {findings:[]};}),provider('queued-one',()=>{called.push('one');return {findings:[]};}),provider('queued-two',()=>{called.push('two');return {findings:[]};})];
  const report=await evaluateProviders(request(),{providers,concurrency:1,permissions:{first:permissions({timeoutMs:300,required:false}),'queued-one':permissions({timeoutMs:100,required:false}),'queued-two':permissions({timeoutMs:100,required:false})}});
  assert.deepEqual(called,['first']);assert.deepEqual(report.providerRuns.map(r=>r.status),['COMPLETED','TIMED_OUT','TIMED_OUT']);
});
test('late synchronous results fail their deadline and unresolved timed-out providers are quarantined',async()=>{
  const sync=provider('sync-timeout',()=>{const until=Date.now()+130;while(Date.now()<until){}return {findings:[]};});
  assert.equal((await run(sync,permissions({timeoutMs:100,required:false}))).providerRuns[0].status,'TIMED_OUT');
  let calls=0,release;
  const p=provider('uncooperative',()=>{calls++;return new Promise(resolve=>{release=resolve;});});
  assert.equal((await run(p,permissions({timeoutMs:100,required:false}))).providerRuns[0].status,'TIMED_OUT');
  assert.equal((await run(p,permissions({timeoutMs:100,required:false}))).providerRuns[0].status,'FAILED');assert.equal(calls,1);
  release({findings:[]});await new Promise(resolve=>setImmediate(resolve));
});
test('external SDK requests never receive host approvals',async()=>{
  let received;
  await run(provider('no-approvals',req=>{received=req.baseline;return {findings:[]};}),{...EXTERNAL_PERMISSIONS,enabled:true},{baseline:[{fingerprint:'host-private'}]});
  assert.deepEqual(received,[]);
});
test('disabled or absent providers never yield a clean report',async () => {
  const p=provider('wrapperguard',()=>{throw new Error('must not execute');});
  assert.equal((await run(p,permissions({enabled:false}))).verdict,'INCOMPLETE');
  assert.equal((await evaluateProviders(request(),{providers:[]})).verdict,'INCOMPLETE');
});
test('malformed results and paths are isolated as provider failure',async () => {
  for(const f of [finding({location:{path:'../../secret'}}),finding({location:{path:'/etc/passwd'}}),finding({severity:'CRITICAL'}),finding({actions:['DELETE']})]) {
    const r=await run(provider('unsafe',()=>({findings:[f]})),permissions({required:false}));assert.equal(r.providerRuns[0].status,'FAILED');assert.equal(r.findings.length,0);
  }
});
test('registration rejects duplicates and reserved IDs, clones identity, and disposal cannot remove a replacement',()=>{
  const registry=new ProviderRegistry();const p=provider('custom',()=>({findings:[]}));const first=registry.registerProvider(p);
  p.descriptor.id='changed';assert.equal(registry.list()[0].descriptor.id,'custom');
  assert.throws(()=>registry.registerProvider(provider('custom',()=>({findings:[]}))),/already registered/);
  assert.throws(()=>registry.registerProvider(provider('wrapperguard',()=>({findings:[]}))),/Reserved/);
  first.dispose();registry.registerProvider(provider('custom',()=>({findings:[]})));first.dispose();assert.equal(registry.list().length,1);
});
test('invalid grants and workspace-owned security configuration are rejected',()=>{
  assert.throws(()=>validatePermissions(permissions({actions:['DELETE']})));
  assert.throws(()=>validatePermissions(permissions({timeoutMs:0})));
  assert.equal(userSetting({globalValue:7,defaultValue:3},1),7);
  assert.throws(()=>userSetting({globalValue:7,workspaceValue:9},1),/User Settings/);
  assert.throws(()=>userSetting({workspaceFolderLanguageValue:false},true),/User Settings/);
});
test('SARIF scopes third-party rule IDs and carries effective provenance/actions with redaction',()=>{
  const sarif=toSarif({findings:[finding({providerId:'first',evidence:{text:'token=PRIVATE',redacted:false}}),finding({providerId:'second'}),finding({providerId:'wrapperguard'})]});
  const run=sarif.runs[0];assert.equal(run.tool.driver.version,require('../package.json').version);assert.deepEqual(run.tool.driver.rules.map(r=>r.id),['first/TEST','second/TEST','TEST']);
  assert.equal(run.results[0].properties.providerId,'first');assert.deepEqual(run.results[0].properties.actions,['WARNING','BLOCK_BUILD','BLOCK_SYNC']);assert.ok(!run.results[0].properties.evidence.includes('PRIVATE'));
});
