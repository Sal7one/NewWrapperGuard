// Verify the delivered JavaScript, including template escaping and the initial/provider render paths.
const assert = require('node:assert/strict');
const vm = require('node:vm');
const Module = require('node:module');
const {spawnSync} = require('node:child_process');
const original = Module._load;
let WrapperGuardWebviewProvider;
try {
  Module._load = function(id,...args) { return id === 'vscode' ? {} : original.call(this,id,...args); };
  ({WrapperGuardWebviewProvider} = require('../dist/webviewUi'));
} finally { Module._load = original; }
const html = new WrapperGuardWebviewProvider().buildHtml();
assert.ok(html.includes('v'+require('../package.json').version),'Webview version must match the package.');
const script = html.match(/<script[^>]*>([\s\S]*?)<\/script>/)[1];
const syntax = spawnSync(process.execPath,['--check'],{input:script,encoding:'utf8'});
assert.equal(syntax.status,0,syntax.stderr);
const elements = new Map();
const listeners = {};
const messages = [];
const element = () => ({addEventListener(){},querySelectorAll(){return [];},classList:{add(){},remove(){},toggle(){}},style:{}});
const document = {
  getElementById(id) { if (!elements.has(id)) elements.set(id,element()); return elements.get(id); },
  querySelectorAll() { return []; }
};
const context = {
  document,
  window: {addEventListener(type,callback){listeners[type]=callback;},matchMedia(){return {matches:true,addEventListener(){}};}},
  acquireVsCodeApi(){return {postMessage(message){messages.push(message);},getState(){return null;},setState(){}};},
  console
};
vm.createContext(context);
vm.runInContext(script,context,{timeout:2000});
assert.match(elements.get('panel-overview').innerHTML,/No scan yet/);
const methods=require('../dist/methods/catalog').SECURITY_METHODS.map(m=>({...m,enabled:m.defaultEnabled,configured:m.category==='BUILT_IN',status:m.category==='BUILT_IN'?'Ready locally':'Setup needed'}));
listeners.message({data:{type:'state',state:{kind:'not-scanned',methods}}});
assert.match(elements.get('panel-tools').innerHTML,/Supply chain/);assert.match(elements.get('panel-tools').innerHTML,/PowerShell/);assert.match(elements.get('panel-tools').innerHTML,/Enable Wrapper integrity/);assert.match(elements.get('panel-research').innerHTML,/Experimental/);assert.match(elements.get('panel-research').innerHTML,/No trained model is bundled|no trained model is bundled/);
const report = {
  findings:[],edges:[],complete:true,stale:false,verdict:'CLEAN',policyName:'Test',scannedFiles:1,durationMs:2,notices:[],
  providerRuns:[{descriptor:{id:'wrapperguard',displayName:'Built-in',version:'0.4.0',apiVersion:1},permissions:{enabled:true,required:true,actions:['WARNING'],timeoutMs:30000},status:'COMPLETED',durationMs:2,findingCount:0}]
};
listeners.message({data:{type:'state',state:{kind:'scanned',report}}});
assert.match(elements.get('panel-policy').innerHTML,/Configure permissions/);
assert.match(elements.get('panel-policy').innerHTML,/COMPLETED/);
assert.match(elements.get('panel-policy').innerHTML,/WARNING/);
const finding=(fingerprint,status,severity,policyAction,file)=>({fingerprint,status,severity,policyAction,ruleId:fingerprint,title:fingerprint,message:'Example',confidence:'HIGH',category:'EXECUTION',kind:'ACTIONABLE',location:{path:file,line:1},reachable:true,callChain:[],providerId:'wrapperguard',actions:policyAction==='BLOCK'?['BLOCK_BUILD']:['WARNING']});
report.findings=[finding('Active blocker','ACTIVE','ERROR','BLOCK','scripts/live.sh'),finding('Baselined blocker','SUPPRESSED','ERROR','BLOCK','scripts/approved.sh'),finding('Active warning','ACTIVE','WARN','REVIEW','build.gradle'),finding('Accepted warning','ACCEPTED','WARN','REVIEW','settings.gradle')];
report.edges=[{from:{path:'gradlew'},to:{path:'scripts/live.sh'},viaCommand:'sh scripts/live.sh'},{from:{path:'gradlew'},to:{path:'scripts/approved.sh'},viaCommand:'sh scripts/approved.sh'}];
report.verdict='EXECUTION_BLOCKED';
listeners.message({data:{type:'state',state:{kind:'scanned',report}}});
assert.equal(elements.get('sumBlockers').textContent,'1');assert.equal(elements.get('sumWarnings').textContent,'1');
assert.match(elements.get('panel-overview').innerHTML,/Blocking findings[\s\S]*?class="value err">1<\/span>/);
assert.match(elements.get('panel-overview').innerHTML,/Warnings[\s\S]*?class="value warn">1<\/span>/);
assert.match(elements.get('panel-graph').innerHTML,/Paths to blockers \(1\)/);
assert.ok(!elements.get('panel-graph').innerHTML.includes('Baselined blocker'));
const approvedDetail=vm.runInContext('detailHtmlFor(state.report.findings[1],false)',context);
assert.match(approvedDetail,/Approved baseline/);assert.ok(!approvedDetail.includes('Blocks execution'));
listeners.message({data:{type:'state',state:{kind:'failed',error:'<script>alert(1)</script>'}}});
assert.match(elements.get('panel-overview').innerHTML,/&lt;script&gt;/);
// Native dialogs can open before the host sends another render. Capture the
// invoking preset control synchronously, then restore its replacement afterward.
const originalQuery=document.querySelectorAll;
for(const kind of ['apply-preset','import-preset','export-preset']){
  const control=()=>({id:'',classList:{contains:value=>value===kind},closest:()=>null,getAttribute:()=>null,focus(){document.activeElement=this;}});
  document.activeElement=control();vm.runInContext('post('+JSON.stringify(kind)+')',context);
  const replacement=control();document.activeElement={};document.hasFocus=()=>false;document.querySelectorAll=()=>[replacement];
  listeners.message({data:{type:'restore-focus'}});assert.equal(document.activeElement,replacement,'Dialog must return focus to '+kind+'.');
}
document.querySelectorAll=originalQuery;
assert.equal(messages.filter(m=>m.type==='webview-error').length,0);
console.log('Embedded webview syntax, version, render states and active-only counts/graph checks passed.');
