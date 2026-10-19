// Demo-only replacement for the VS Code host. It reads no files, starts no SDKs, and writes no approvals.
(function () {
  'use strict';
  const seed=window.__WRAPPERGUARD_DEMO_DATA__;
  const clone=value=>JSON.parse(JSON.stringify(value));
  const initial=clone(seed.report);
  let current=clone(initial);
  let baselines=new Set(initial.findings.filter(f=>f.status==='SUPPRESSED').map(f=>f.fingerprint));
  let permissions=Object.fromEntries(initial.providerRuns.map(run=>[run.descriptor.id,clone(run.permissions)]));
  let toastTimer;
  const actions=['WARNING','MAJOR_WARNING','ALERT','BLOCK_BUILD','BLOCK_SYNC'];
  const actionLabels={WARNING:'Show warnings',MAJOR_WARNING:'Show major warnings',ALERT:'Raise alerts',BLOCK_BUILD:'Stop builds',BLOCK_SYNC:'Stop sync'};
  function toast(message) {
    const el=document.getElementById('demo-toast');el.textContent=message;el.hidden=false;
    clearTimeout(toastTimer);toastTimer=setTimeout(()=>{el.hidden=true;},3500);
  }
  function send(state) { window.dispatchEvent(new MessageEvent('message',{data:{type:'state',state}})); }
  function rebuild() {
    current=clone(initial);
    current.providerRuns=current.providerRuns.map(run=>{
      run.permissions=clone(permissions[run.descriptor.id]);
      if (!run.permissions.enabled) { run.status='DISABLED';run.findingCount=0;run.durationMs=0; }
      return run;
    });
    current.findings=current.findings.filter(f=>permissions[f.providerId||'wrapperguard']?.enabled!==false).map(f=>{
      const grants=permissions[f.providerId||'wrapperguard']?.actions||[];
      const wasSuppressed=f.status==='SUPPRESSED';
      f.status=baselines.has(f.fingerprint)?'SUPPRESSED':wasSuppressed?'ACTIVE':f.status;
      const canBlock=f.kind==='ACTIONABLE'&&f.severity!=='INFO'&&f.reachable!==false;
      const requests=f.actions&&f.actions.length?f.actions:(canBlock&&f.severity==='ERROR'?['MAJOR_WARNING','BLOCK_BUILD','BLOCK_SYNC']:f.severity==='WARN'?['WARNING']:[]);
      f.actions=requests.filter(action=>grants.includes(action));
      const blocking=canBlock&&f.actions.some(action=>action==='BLOCK_BUILD'||action==='BLOCK_SYNC');
      f.policyAction=blocking?'BLOCK':f.kind==='ACTIONABLE'&&f.severity!=='INFO'&&f.actions.length?'REVIEW':'ALLOW';
      return f;
    });
    current.complete=current.providerRuns.every(run=>run.status==='COMPLETED'||run.status==='DISABLED');
    const active=current.findings.filter(f=>f.status==='ACTIVE');
    current.verdict=active.some(f=>f.policyAction==='BLOCK')?'EXECUTION_BLOCKED':!current.complete?'INCOMPLETE':active.some(f=>f.policyAction==='REVIEW')?'REVIEW_REQUIRED':'CLEAN';
    for (const run of current.providerRuns) run.findingCount=current.findings.filter(f=>f.providerId===run.descriptor.id).length;
    send({kind:'scanned',report:current,settings:seed.settings});
    return current;
  }
  function download(name,value) {
    const url=URL.createObjectURL(new Blob([JSON.stringify(value,null,2)],{type:'application/json'}));
    const anchor=document.createElement('a');anchor.href=url;anchor.download=name;anchor.click();
    setTimeout(()=>URL.revokeObjectURL(url),1000);
    toast('Downloaded fictional demo data. No project files were changed.');
  }
  function dialog(title,body,buttons) {
    const el=document.getElementById('demo-dialog');el.replaceChildren();
    const heading=document.createElement('h2');heading.textContent=title;el.append(heading);
    const note=document.createElement('p');note.textContent='Simulated demo only. Changes stay in this browser page.';el.append(note);
    el.append(body);
    const row=document.createElement('div');row.className='demo-dialog-actions';
    for (const [label,handler] of buttons) {
      const button=document.createElement('button');button.className='btn btn-ghost';button.textContent=label;button.type='button';
      button.addEventListener('click',()=>{handler?.();el.close();});row.append(button);
    }
    el.append(row);el.showModal();
  }
  function configure(id) {
    const run=current.providerRuns.find(item=>item.descriptor.id===id);if(!run)return;
    const form=document.createElement('form');const inputs={};
    for(const [key,label,checked] of [['enabled','Enabled',permissions[id].enabled],['required','Required',permissions[id].required],...actions.map(action=>[action,actionLabels[action],permissions[id].actions.includes(action)])]) {
      const row=document.createElement('label'),input=document.createElement('input');input.type='checkbox';input.checked=checked;input.name=key;inputs[key]=input;
      row.append(input,document.createTextNode(label));form.append(row);
    }
    dialog(run.descriptor.displayName+' permissions',form,[['Cancel'],['Apply in demo',()=>{
      permissions[id]={...permissions[id],enabled:inputs.enabled.checked,required:inputs.required.checked,actions:actions.filter(action=>inputs[action].checked)};
      rebuild();toast('Demo permissions updated. No User Settings or provider configuration files were changed.');
    }]]);
  }
  function onHostMessage(message) {
    if(message.type==='webview-error') { window.__WRAPPERGUARD_DEMO_ERRORS__.push(message.message);console.error(message.message);return; }
    if(message.type==='scan') { send({kind:'scanning',settings:seed.settings});setTimeout(()=>{rebuild();toast('Fictional demo result reloaded. No scripts or SDKs were run.');},450);return; }
    if(message.type==='baseline'||message.type==='suppress') { baselines.add(message.finding.fingerprint);rebuild();toast('Approved in the demo baseline only. No source files or trusted approvals were written.');return; }
    if(message.type==='unbaseline') { baselines.delete(message.finding.fingerprint);rebuild();toast('Demo approval removed. The finding is active again.');return; }
    if(message.type==='configure-provider') { configure(message.providerId);return; }
    if(message.type==='settings') { const content=document.createElement('pre');content.textContent=JSON.stringify(seed.settings,null,2);dialog('Atlas Payments — demo settings',content,[['Close']]);return; }
    if(message.type==='open') { const content=document.createElement('pre');content.textContent=message.finding.location.path+':'+(message.finding.location.line||1)+'\n\n'+(message.finding.evidence?.text||message.finding.message);dialog('Fictional file preview',content,[['Close']]);return; }
    if(message.type==='copy') { navigator.clipboard?.writeText(message.finding.evidence?.text||message.finding.message).catch(()=>{});toast('Demo evidence selected for copying.');return; }
    if(message.type==='export') { download('atlas-payments-demo-report.json',{...current,demo:true});return; }
    if(message.type==='sarif') { download('atlas-payments-demo.sarif.json',{$schema:'https://json.schemastore.org/sarif-2.1.0.json',version:'2.1.0',runs:[{tool:{driver:{name:'WrapperGuard simulated demo',version:'0.4.0'}},results:current.findings.map(f=>({ruleId:f.ruleId,message:{text:f.message},level:f.severity==='ERROR'?'error':f.severity==='WARN'?'warning':'note',properties:{providerId:f.providerId,demo:true}}))}]}); }
  }
  window.__WRAPPERGUARD_DEMO_ERRORS__=[];
  window.acquireVsCodeApi=()=>({postMessage:onHostMessage,getState:()=>null,setState:()=>{}});
  function theme(light) {
    document.body.classList.toggle('vscode-light',light);document.body.classList.toggle('vscode-dark',!light);
    document.getElementById('demo-theme').textContent=light?'Dark theme':'Light theme';
  }
  window.wrapperGuardDemo={getReport:()=>clone(current),getBaseline:()=>[...baselines],setTheme:theme,reset:()=>{
    baselines=new Set(initial.findings.filter(f=>f.status==='SUPPRESSED').map(f=>f.fingerprint));permissions=Object.fromEntries(initial.providerRuns.map(run=>[run.descriptor.id,clone(run.permissions)]));rebuild();
  }};
  document.addEventListener('DOMContentLoaded',()=>{
    const version=document.querySelector('.brand-ver');if(version)version.textContent+=' · demo';
    const params=new URLSearchParams(location.search);theme(params.get('theme')==='light');
    document.getElementById('demo-theme').addEventListener('click',()=>theme(!document.body.classList.contains('vscode-light')));
    document.getElementById('demo-reset').addEventListener('click',()=>{window.wrapperGuardDemo.reset();toast('Demo restored to its original fictional baseline.');});
    rebuild();
    const tab=params.get('tab');if(['overview','findings','graph','policy'].includes(tab))document.querySelector('[data-tab="'+tab+'"]').click();
    document.documentElement.dataset.demoReady='true';
  });
})();
