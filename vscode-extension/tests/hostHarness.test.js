const test=require('node:test');const assert=require('node:assert/strict');const fs=require('node:fs/promises');const os=require('node:os');const path=require('node:path');const{spawnSync}=require('node:child_process');
test('host fixture waits for its actual configuration notification, including delivery after update resolves',async()=>{
 let notification,disposed=0,completed=false;const vscode={ConfigurationTarget:{Global:1},workspace:{onDidChangeConfiguration(listener){notification=listener;return{dispose(){disposed++;}};}}};
 const context={exports:{},setTimeout,clearTimeout,require:id=>id==='vscode'?vscode:require(id)};const vm=require('node:vm');vm.createContext(context);vm.runInContext(await fs.readFile(path.resolve(__dirname,'../host-tests/index.js'),'utf8'),context);
 const config={async update(key,value,target){assert.equal(key,'providerPermissions');assert.equal(target,1);}};
 const pending=context.updateSetting(config,'providerPermissions',{}).then(()=>{completed=true;});await new Promise(setImmediate);assert.equal(completed,false,'Resolving config.update does not prove notification delivery.');
 notification({affectsConfiguration:()=>false});await new Promise(setImmediate);assert.equal(completed,false,'An unrelated notification cannot release the next scan.');
 notification({affectsConfiguration:key=>key==='wrapperGuard.providerPermissions'});await pending;assert.equal(completed,true);assert.equal(disposed,1);
});
// The official harness is replaced here only to exercise its runner's failure
// bookkeeping. Actual editor API acceptance remains in the separate host matrix.
for(const mode of ['partial-evidence-and-failure','launch-failure','reported-failure'])test('host runner preserves failed evidence and cleanup: '+mode,async t=>{
 const base=await fs.mkdtemp(path.join(os.tmpdir(),'wg-host-harness-'));t.after(()=>fs.rm(base,{recursive:true,force:true}));const evidence=path.join(base,'evidence.json'),marker=path.join(base,'profile.json');
 const script=path.resolve(__dirname,'../scripts/test-host.js');
 const wrapper=`const Module=require('node:module'),fs=require('node:fs/promises'),path=require('node:path');const original=Module._load;Module._load=function(id,...args){if(id==='@vscode/test-electron')return{runTests:async options=>{const env=options.extensionTestsEnv;await fs.writeFile(${JSON.stringify(marker)},JSON.stringify({base:path.dirname(env.WRAPPERGUARD_HOST_PROJECT)}));if(${JSON.stringify(mode)}!=='launch-failure')await fs.writeFile(env.WRAPPERGUARD_HOST_RESULT,JSON.stringify({schemaVersion:1,passed:false,cases:['activation'],error:{message:'controlled failure'}}));if(${JSON.stringify(mode)}!=='reported-failure')throw new Error('Controlled host failure');}};return original.call(this,id,...args);};require(${JSON.stringify(script)});`;
 const result=spawnSync(process.execPath,['-e',wrapper],{encoding:'utf8',timeout:10000,env:{...process.env,VSCODE_VERSION:'1.95.0',VSCODE_EXECUTABLE_PATH:process.execPath,WRAPPERGUARD_HOST_EVIDENCE:evidence}});
 assert.equal(result.status,1,result.stderr);const saved=JSON.parse(await fs.readFile(evidence,'utf8'));assert.equal(saved.passed,false);
 if(mode==='launch-failure'){assert.equal(saved.evidenceUnavailable,true);assert.equal(saved.requestedVscodeVersion,'1.95.0');assert.deepEqual(saved.cases,[]);}else assert.deepEqual(saved.cases,['activation']);
 const profile=JSON.parse(await fs.readFile(marker,'utf8'));await assert.rejects(fs.access(profile.base),error=>error.code==='ENOENT');
});
