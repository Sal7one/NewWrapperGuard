#!/usr/bin/env node
'use strict';
const assert=require('node:assert/strict');const fs=require('node:fs/promises');const os=require('node:os');const path=require('node:path');
const{loadCommandProviders}=require('../dist/providers/commandProvider');const{evaluateProviders}=require('../dist/providers/coordinator');const{DEFAULT_SETTINGS,DEFAULT_ENFORCE_POLICY}=require('../dist/scanner/defaults');
async function runConformance(python,adapter){
 if(!path.isAbsolute(python))throw new Error('Choose an absolute trusted Python interpreter.');
 const temporary=await fs.mkdtemp(path.join(os.tmpdir(),'wg-provider-conformance-'));const base=await fs.realpath(temporary);const cases=[];
 try{
  const project=path.join(base,'project');await fs.mkdir(project);await fs.writeFile(path.join(project,'fixture.txt'),'Inert transport fixture; never executed.\n');
  const installed=path.join(base,'installed-adapter.py');await fs.copyFile(adapter??path.resolve(__dirname,'../../examples/provider-conformance/adapter.py'),installed);
  const configFile=path.join(base,'providers.json');
  const evaluate=async(mode,operation='SCAN',actions=['WARNING'],required=false,signal=new AbortController().signal,marker)=>{
   const permissions={enabled:true,actions,required,timeoutMs:mode==='timeout'?150:5000};
   await fs.writeFile(configFile,JSON.stringify({apiVersion:1,providers:[{id:'example.conformance',displayName:'Inert conformance adapter',version:'1.0.0',category:'OTHER',inputScope:{includes:['fixture.txt']},command:[python,'-I',installed,'--mode',mode,...(marker?['--started-file',marker]:[])],permissions}]}));
   const loaded=await loadCommandProviders(configFile,project,true);
   return evaluateProviders({projectRoot:project,operation,signal,settings:DEFAULT_SETTINGS,policy:DEFAULT_ENFORCE_POLICY,baseline:[]},{providers:loaded.providers,permissions:loaded.permissions});
  };
  const healthy=await evaluate('healthy');const finding=healthy.findings[0];assert.equal(healthy.complete,true);assert.equal(finding.providerId,'example.conformance');assert.equal(finding.status,'ACTIVE');assert.notEqual(finding.fingerprint,'forged-fingerprint');assert.deepEqual(finding.actions,['WARNING']);assert.equal(finding.policyAction,'REVIEW');assert.ok(!JSON.stringify(healthy).includes('CONFORMANCE_PRIVATE_VALUE'));cases.push({id:'attribution-redaction-action-clipping',passed:true});
  const build=await evaluate('healthy','BUILD',['BLOCK_BUILD']);assert.ok(build.findings.some(item=>item.policyAction==='BLOCK'));const sync=await evaluate('healthy','SYNC',['BLOCK_BUILD']);assert.ok(!sync.findings.some(item=>item.policyAction==='BLOCK'));cases.push({id:'independent-build-sync-grants',passed:true});
  const malformed=await evaluate('malformed');assert.equal(malformed.complete,false);assert.equal(malformed.providerRuns[0].status,'FAILED');cases.push({id:'malformed-response',passed:true});
  const partial=await evaluate('partial');assert.equal(partial.complete,false);assert.equal(partial.findings.length,1);assert.equal(partial.findings[0].status,'ACTIVE');cases.push({id:'partial-result-retains-findings',passed:true});
  const timeout=await evaluate('timeout','BUILD',['BLOCK_BUILD'],true);assert.equal(timeout.complete,false);assert.equal(timeout.providerRuns[0].status,'TIMED_OUT');assert.ok(timeout.findings.some(item=>item.ruleId==='WG-PROVIDER-UNAVAILABLE'&&item.policyAction==='BLOCK'));cases.push({id:'required-timeout-fail-closed',passed:true});
  const abort=new AbortController();const marker=path.join(base,'started');const pending=evaluate('cancellation','SCAN',[],false,abort.signal,marker);let launched=false;
  for(let attempt=0;attempt<200;attempt++){try{await fs.access(marker);launched=true;break;}catch{}await new Promise(resolve=>setTimeout(resolve,10));}assert.ok(launched,'Cancellation case must launch the adapter before aborting.');const pid=Number(await fs.readFile(marker,'utf8'));assert.ok(Number.isSafeInteger(pid)&&pid>0,'Fixture must record its own PID.');abort.abort();const cancelled=await pending;assert.equal(cancelled.complete,false);assert.notEqual(cancelled.providerRuns[0].status,'COMPLETED');let alive=true;for(let attempt=0;attempt<100;attempt++){try{process.kill(pid,0);}catch(error){if(error.code==='ESRCH'){alive=false;break;}throw error;}await new Promise(resolve=>setTimeout(resolve,10));}assert.equal(alive,false,'Cancelled adapter process must actually exit.');cases.push({id:'in-flight-cancellation',passed:true});
  return{schemaVersion:1,host:'TypeScript command bridge',apiVersion:1,fixtureOnly:true,cases};
 }finally{await fs.rm(base,{recursive:true,force:true});}
}
module.exports={runConformance};
if(require.main===module){const args=process.argv.slice(2);if(!args.length||args[0]!=='--python'||!args[1]){console.error('Usage: node scripts/provider-conformance.js --python /absolute/python [--adapter /absolute/adapter.py]');process.exitCode=1;}else{const adapter=args[2]==='--adapter'?args[3]:undefined;runConformance(args[1],adapter).then(result=>console.log(JSON.stringify(result,null,2)),error=>{console.error('Provider conformance failed: '+error.message);process.exitCode=1;});}}
