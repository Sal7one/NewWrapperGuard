'use strict';
const assert=require('node:assert/strict');const fs=require('node:fs/promises');const path=require('node:path');const vscode=require('vscode');
const bounded=(promise,label)=>new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(new Error('Timed out waiting for '+label)),5000);promise.then(value=>{clearTimeout(timer);resolve(value);},error=>{clearTimeout(timer);reject(error);});});
// VS Code updates and file-system notifications are asynchronous. Sleeping 100ms
// can race macOS FSEvents and make the next independent case correctly fail stale.
async function updateSetting(config,key,value){
 let observed;const changed=new Promise(resolve=>{observed=resolve;});
 const subscription=vscode.workspace.onDidChangeConfiguration(event=>{if(event.affectsConfiguration('wrapperGuard.'+key))observed();});
 try{await config.update(key,value,vscode.ConfigurationTarget.Global);await bounded(changed,'configuration notification: '+key);}finally{subscription.dispose();}
}
exports.run=async function(){
 const project=process.env.WRAPPERGUARD_HOST_PROJECT,second=process.env.WRAPPERGUARD_HOST_SECOND;assert.ok(project&&second);const cases=[];
 const extension=vscode.extensions.getExtension('sal7one.wrapperguard');assert.ok(extension,'Development extension must be installed.');const api=await extension.activate();assert.equal(api.apiVersion,1);assert.equal(typeof api.registerProvider,'function');assert.equal(vscode.workspace.workspaceFolders.length,2);cases.push('activate-api1-two-roots');
 const config=vscode.workspace.getConfiguration('wrapperGuard');const id='example.real-host';let calls=0,mutate=false;
 const evidence=(passed,error)=>({schemaVersion:1,vscodeVersion:vscode.version,platform:process.platform,apiVersion:api.apiVersion,fixtureOnly:true,passed,cases,...(error?{error:{name:error.name,message:String(error.message).slice(0,1000)}}:{})});
 const registration=api.registerProvider({descriptor:{id,displayName:'Inert host conformance fixture',version:'1.0.0',apiVersion:1,category:'SAST',inputScope:{includes:['fixture.txt']}},async scan(request){calls++;if(mutate&&request.operation==='BUILD'){mutate=false;await fs.appendFile(path.join(request.projectRoot,'fixture.txt'),'Changed during gate.\n');}return{complete:true,findings:[{ruleId:'HOST-001',title:'Inert host fixture',message:'No source was analyzed.',severity:'ERROR',confidence:'HIGH',category:'EXECUTION',location:{path:'fixture.txt',line:1},reachable:true,providerId:'forged',status:'SUPPRESSED',fingerprint:'forged',actions:['WARNING','BLOCK_BUILD','BLOCK_SYNC']}]};}});
 try{
  await new Promise(resolve=>setTimeout(resolve,100));assert.equal(calls,0,'Registration alone must not execute a provider.');await api.evaluate('SCAN',project);assert.equal(calls,0,'Unapproved providers stay disabled.');
  const grant={enabled:true,actions:['WARNING'],required:false,timeoutMs:5000};await updateSetting(config,'providerPermissions',{[id]:grant});await new Promise(resolve=>setTimeout(resolve,100));assert.equal(calls,0,'Saving enablement does not scan.');
  const report=await api.evaluate('SCAN',project);const finding=report.findings.find(item=>item.ruleId==='HOST-001');assert.ok(finding);assert.equal(finding.providerId,id);assert.equal(finding.status,'ACTIVE');assert.notEqual(finding.fingerprint,'forged');assert.deepEqual(finding.actions,['WARNING']);assert.equal(finding.policyAction,'REVIEW');assert.equal(await fs.realpath(report.projectPath),await fs.realpath(project));cases.push('disabled-until-granted-no-implicit-execution');cases.push('actual-sdk-attribution-status-action-clipping');
  await updateSetting(config,'providerPermissions',{[id]:{...grant,actions:['BLOCK_BUILD']}});assert.equal((await api.gate('BUILD',project)).allowed,false);assert.equal((await api.gate('SYNC',project)).allowed,true);cases.push('independent-build-sync-authority');
  const other=await api.evaluate('SCAN',second);assert.equal(await fs.realpath(other.projectPath),await fs.realpath(second));await assert.rejects(api.evaluate('SCAN',path.dirname(project)),/open workspace/);cases.push('explicit-root-binding-outside-root-rejection');
  const file=path.join(path.dirname(project),'missing-provider.json');await fs.writeFile(file,JSON.stringify({apiVersion:1,providers:[{id:'example.missing-host',displayName:'Missing fixture',version:'1',command:[path.join(path.dirname(project),'does-not-exist')],permissions:{enabled:true,actions:['WARNING'],required:false,timeoutMs:1000}}]}));await updateSetting(config,'providerConfigPath',file);const failed=await api.evaluate('SCAN',project);assert.equal(failed.complete,false);assert.equal(failed.providerRuns.find(item=>item.descriptor.id==='example.missing-host').status,'FAILED');assert.equal(failed.providerRuns.find(item=>item.descriptor.id==='wrapperguard').status,'COMPLETED');cases.push('missing-tool-isolated-incomplete-repairable');
  // Keep the deliberately mutating case last: a later FSEvent must not make an
  // unrelated following case race a correctly rejected stale evaluation.
  mutate=true;await assert.rejects(api.gate('BUILD',project),/changed during evaluation/);cases.push('actual-before-after-declared-input-seal');
  await vscode.commands.executeCommand('workbench.view.extension.wrapperGuard');await vscode.commands.executeCommand('wrapperGuard.main.focus');cases.push('actual-contributed-webview-open');
  await fs.writeFile(process.env.WRAPPERGUARD_HOST_RESULT,JSON.stringify(evidence(true),null,2));
 }catch(error){await fs.writeFile(process.env.WRAPPERGUARD_HOST_RESULT,JSON.stringify(evidence(false,error),null,2));throw error;}finally{registration.dispose();}
};
