const test=require('node:test');const assert=require('node:assert/strict');
const fs=require('node:fs/promises');const path=require('node:path');const os=require('node:os');const Module=require('node:module');
const env={settings:{},trusted:true,changed:undefined};
const disposable=()=>({dispose(){}});
const uri=file=>({scheme:'file',fsPath:file});
const vscode={
  Uri:{file:uri,joinPath:(root,...parts)=>uri(path.join(root.fsPath,...parts))},
  EventEmitter:class {event=()=>disposable();fire(){}},ThemeColor:class {},
  StatusBarAlignment:{Left:1},ProgressLocation:{Notification:1},ConfigurationTarget:{Global:1},
  commands:{registerCommand:()=>disposable(),executeCommand:async()=>{}},
  workspace:{
    get isTrusted(){return env.trusted;},get workspaceFolders(){return [{uri:uri(env.project)}];},
    getConfiguration(){return {
      get:(key,fallback)=>key==='scanOnOpen'?false:(env.settings[key]??fallback),
      inspect:key=>env.overrides?.[key]??{globalValue:env.settings[key]},
      update:async(key,value)=>{env.settings[key]=value;}
    };},
    onDidChangeConfiguration:callback=>{env.changed=callback;return disposable();},onDidGrantWorkspaceTrust:()=>disposable()
  },
  window:{createStatusBarItem:()=>({show(){},dispose(){}}),createOutputChannel:()=>({appendLine(){},dispose(){}}),
    registerWebviewViewProvider:()=>disposable(),withProgress:async(_options,callback)=>callback(),
    showErrorMessage:async()=>{},showWarningMessage:async()=>{},showInformationMessage:async()=>{}
  }
};
const original=Module._load;
let activate;
try {Module._load=function(id,...args){return id==='vscode'?vscode:original.call(this,id,...args);};({activate}=require('../dist/extension'));}
finally {Module._load=original;}
const finding=()=>({fingerprint:'untrusted',ruleId:'EXAMPLE',title:'Example',message:'Review this build',severity:'ERROR',confidence:'HIGH',category:'EXECUTION',kind:'ACTIONABLE',location:{path:'gradlew'},reachable:true,policyAction:'BLOCK',status:'ACTIVE',actions:['BLOCK_BUILD']});
const provider=scan=>({descriptor:{id:'example',displayName:'Example',version:'1',apiVersion:1},scan});
const grant={enabled:true,actions:['BLOCK_BUILD'],required:true,timeoutMs:30000};
async function fixture(t,trusted=true,settings={}) {
  const dir=await fs.mkdtemp(path.join(os.tmpdir(),'wg-api-'));env.project=path.join(dir,'project');await fs.mkdir(env.project);await fs.writeFile(path.join(env.project,'gradlew'),'#!/bin/sh\necho safe\n');
  env.settings=settings;env.trusted=trusted;env.overrides=undefined;env.changed=undefined;
  const context={globalStorageUri:uri(path.join(dir,'storage')),subscriptions:[]};const api=activate(context);
  t.after(async()=>{for(const subscription of context.subscriptions)subscription.dispose?.();await fs.rm(dir,{recursive:true,force:true});});return api;
}
test('exported API discovers disabled SDK providers without invoking them',async t=>{
  const api=await fixture(t);let calls=0;api.registerProvider(provider(()=>{calls++;return {findings:[finding()]};}));
  const report=await api.evaluate('SCAN');assert.equal(api.apiVersion,1);assert.equal(calls,0);assert.equal(report.providerRuns.find(r=>r.descriptor.id==='example').status,'DISABLED');
});
test('Safe Mode pauses globally enabled SDKs and command config while allowing built-in static analysis',async t=>{
  const api=await fixture(t,false,{providerPermissions:{example:grant},providerConfigPath:'/does/not/exist/providers.json'});let calls=0;
  api.registerProvider(provider(()=>{calls++;return {findings:[]};}));const report=await api.evaluate('SCAN');
  assert.equal(calls,0);assert.equal(report.providerRuns.find(r=>r.descriptor.id==='wrapperguard').status,'COMPLETED');assert.equal(report.providerRuns.find(r=>r.descriptor.id==='example').status,'DISABLED');
  assert.equal(report.complete,false);assert.ok(report.notices.some(n=>n.code==='WG-PROVIDERS-PAUSED'));
  await assert.rejects(api.gate('BUILD'),/trusted workspace/);await assert.rejects(api.gate('SYNC'),/trusted workspace/);
});
test('cooperating extensions receive operation-specific gate decisions through activate exports',async t=>{
  const api=await fixture(t,true,{providerPermissions:{example:grant,wrapperguard:{...grant,enabled:false}}});api.registerProvider(provider(()=>({findings:[finding()]})));
  assert.equal((await api.gate('BUILD')).allowed,false);assert.equal((await api.gate('SYNC')).allowed,true);await assert.rejects(api.gate('SCAN'),/BUILD or SYNC/);
});
test('revoking provider grants during a gate rejects the stale decision',async t=>{
  const api=await fixture(t,true,{providerPermissions:{example:grant,wrapperguard:{...grant,enabled:false}}});let release,entered;
  const started=new Promise(resolve=>{entered=resolve;});
  api.registerProvider(provider(request=>request.operation==='BUILD'?new Promise(resolve=>{release=resolve;entered();}):{findings:[]}));
  const pending=api.gate('BUILD');await started;
  env.settings.providerPermissions.example={...grant,actions:[]};env.changed({affectsConfiguration:()=>true});release({findings:[finding()]});
  await assert.rejects(pending,/permissions changed/);await api.evaluate('SCAN');
});
