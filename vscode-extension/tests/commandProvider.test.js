const test=require('node:test');const assert=require('node:assert/strict');
const fs=require('node:fs/promises');const os=require('node:os');const path=require('node:path');
const {loadCommandProviders,validateCommandConfiguration}=require('../dist/providers/commandProvider');
const {DEFAULT_PERMISSIONS}=require('../dist/providers/sdk');
const {DEFAULT_SETTINGS,DEFAULT_ENFORCE_POLICY}=require('../dist/scanner/defaults');
const makeConfig=(command,overrides={})=>({apiVersion:1,providers:[{id:'script',displayName:'Script',version:'1',command,permissions:{...DEFAULT_PERMISSIONS,required:false},...overrides}]});
async function fixture(t){const dir=await fs.mkdtemp(path.join(os.tmpdir(),'wg-provider-'));t.after(()=>fs.rm(dir,{recursive:true,force:true}));const project=path.join(dir,'project');await fs.mkdir(project);return {dir,project,config:path.join(dir,'providers.json')};}
const request=project=>({projectRoot:project,operation:'BUILD',signal:new AbortController().signal,settings:DEFAULT_SETTINGS,policy:DEFAULT_ENFORCE_POLICY,baseline:[{fingerprint:'PRIVATE_APPROVAL'}]});
test('command configuration rejects unknown keys, invalid actions, duplicates, and nonabsolute executables',()=>{
  for(const config of [makeConfig(['echo']),{...makeConfig([process.execPath]),unexpected:true},makeConfig([process.execPath],{id:'wrapperguard'}),makeConfig([process.execPath],{permissions:{...DEFAULT_PERMISSIONS,actions:['DELETE']}}),{apiVersion:2,providers:[]}]) assert.throws(()=>validateCommandConfiguration(config));
  const duplicated=makeConfig([process.execPath]);duplicated.providers.push({...duplicated.providers[0]});assert.throws(()=>validateCommandConfiguration(duplicated));
});
test('configuration defaults preserve the built-in while omitted external permissions disable execution',()=>{
  const empty=validateCommandConfiguration({});assert.equal(empty.builtIn.enabled,true);assert.equal(empty.builtIn.required,true);assert.equal(empty.builtIn.actions.length,5);assert.deepEqual(empty.providers,[]);
  const config=makeConfig([process.execPath]);delete config.providers[0].permissions;
  const parsed=validateCommandConfiguration(config);assert.equal(parsed.providers[0].permissions.enabled,false);assert.deepEqual(parsed.providers[0].permissions.actions,[]);
  assert.throws(()=>validateCommandConfiguration({apiVersion:null}));assert.throws(()=>validateCommandConfiguration({providers:null}));
});
test('configuration requires trust and an external absolute file, including symlink resolution',async t=>{
  const {dir,project,config}=await fixture(t);await fs.writeFile(config,JSON.stringify(makeConfig([process.execPath])));
  await assert.rejects(loadCommandProviders(config,project,false),/trusted/);
  await assert.rejects(loadCommandProviders('providers.json',project,true),/absolute/);
  const internal=path.join(project,'providers.json');await fs.writeFile(internal,JSON.stringify({apiVersion:1,providers:[]}));
  await assert.rejects(loadCommandProviders(internal,project,true),/outside/);
  const link=path.join(dir,'link.json');await fs.symlink(internal,link);await assert.rejects(loadCommandProviders(link,project,true),/inside/);
});
test('command protocol sends metadata only and treats shell syntax as literal argv',async t=>{
  const {project,config}=await fixture(t);
  const script="let s='';process.stdin.on('data',c=>s+=c);process.stdin.on('end',()=>{const r=JSON.parse(s);process.stdout.write(JSON.stringify({findings:[],notices:[{code:'REQUEST',message:JSON.stringify({r,arg:process.argv[1]})}]}));});";
  await fs.writeFile(config,JSON.stringify(makeConfig([process.execPath,'-e',script,'$(touch /tmp/WRAPPERGUARD_MUST_NOT_RUN);$HOME'])));
  const loaded=await loadCommandProviders(config,project,true);const result=await loaded.providers[0].scan(request(project));
  const data=JSON.parse(result.notices[0].message);assert.deepEqual(data.r,{apiVersion:1,projectPath:await fs.realpath(project),operation:'BUILD'});assert.equal(data.arg,'$(touch /tmp/WRAPPERGUARD_MUST_NOT_RUN);$HOME');
  assert.ok(!JSON.stringify(data).includes('PRIVATE_APPROVAL'));
});
test('nonzero exits and malformed JSON expose generic failures without stderr secrets',async t=>{
  const {project,config}=await fixture(t);
  for(const script of ['process.stderr.write("token=PRIVATE_SECRET");process.exit(1)','process.stdout.write("not json token=PRIVATE_SECRET")']) {
    await fs.writeFile(config,JSON.stringify(makeConfig([process.execPath,'-e',script])));
    const {providers}=await loadCommandProviders(config,project,true);
    await assert.rejects(providers[0].scan(request(project)),error=>!error.message.includes('PRIVATE_SECRET'));
  }
});
test('provider output rejects malformed UTF-8 and configuration files obey the 64 KiB limit',async t=>{
  const {project,config}=await fixture(t);
  await fs.writeFile(config,JSON.stringify(makeConfig([process.execPath,'-e','process.stdout.write(Buffer.from([123,34,120,34,58,34,255,34,125]))'])));
  const {providers}=await loadCommandProviders(config,project,true);await assert.rejects(providers[0].scan(request(project)),/invalid JSON/);
  await fs.writeFile(config,' '.repeat(65537));await assert.rejects(loadCommandProviders(config,project,true),/limit/);
});
test('command output limits and abort terminate the provider',async t=>{
  const {project,config}=await fixture(t);
  await fs.writeFile(config,JSON.stringify(makeConfig([process.execPath,'-e','process.stdout.write("x".repeat(5*1024*1024));setInterval(()=>{},1000)'])));
  let {providers}=await loadCommandProviders(config,project,true);await assert.rejects(providers[0].scan(request(project)),/output limit/);
  await fs.writeFile(config,JSON.stringify(makeConfig([process.execPath,'-e','setInterval(()=>{},1000)'])));
  ({providers}=await loadCommandProviders(config,project,true));const controller=new AbortController();const pending=providers[0].scan({...request(project),signal:controller.signal});setTimeout(()=>controller.abort(),20);await assert.rejects(pending,/cancelled/);
});
