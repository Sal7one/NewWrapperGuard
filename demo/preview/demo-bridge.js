// Demo-only replacement for the VS Code host. It reads no files, starts no SDKs, and writes no approvals.
(function () {
  'use strict';
  const seed=window.__WRAPPERGUARD_DEMO_DATA__;
  const clone=value=>JSON.parse(JSON.stringify(value));
  const initial=clone(seed.report);
  let current=clone(initial);
  let baselines=new Set(initial.findings.filter(f=>f.status==='SUPPRESSED').map(f=>f.fingerprint));
  let permissions=Object.fromEntries(initial.providerRuns.map(run=>[run.descriptor.id,clone(run.permissions)]));
  let flags=clone(seed.settings.methodFlags||{});
  let connections={};
  let toastTimer;
  const actions=['WARNING','MAJOR_WARNING','ALERT','BLOCK_BUILD','BLOCK_SYNC'];
  const actionLabels={WARNING:'Show warnings',MAJOR_WARNING:'Show major warnings',ALERT:'Raise alerts',BLOCK_BUILD:'Stop builds',BLOCK_SYNC:'Stop sync'};
  function toast(message) {
    const el=document.getElementById('demo-toast');el.textContent=message;el.hidden=false;
    clearTimeout(toastTimer);toastTimer=setTimeout(()=>{el.hidden=true;},3500);
  }
  function send(state) { window.dispatchEvent(new MessageEvent('message',{data:{type:'state',state}})); }
  function methodViews() { return seed.methods.map(method=>{
    const builtin=method.category==='BUILT_IN';
    const configured=builtin||!!connections[method.id]||initial.providerRuns.some(run=>run.descriptor.id===method.providerId);
    const enabled=flags[method.id]??(builtin?method.defaultEnabled:configured&&permissions[method.providerId]?.enabled||false);
    return {...method,enabled,configured,status:!enabled?'Disabled':builtin?'Ready locally':configured?'DEMO · simulated connection':'Setup needed'};
  }); }
  function uiState(kind,report) { return {kind,report,settings:{...seed.settings,methodFlags:clone(flags)},methods:methodViews()}; }
  function enabledBuiltin(finding) {
    const rule=finding.ruleId||'';
    const method=rule.startsWith('WG-WRAPPER')?'builtin.wrapper-integrity':rule.startsWith('WG-SCRIPT')?'builtin.script-rules':rule.startsWith('WG-BUILD')?'builtin.build-rules':rule.startsWith('WG-CATALOG')?'builtin.catalog-rules':rule.startsWith('WG-MAVEN')?'builtin.maven-rules':null;
    return !method||flags[method]!==false;
  }
  function rebuild() {
    current=clone(initial);
    for(const [methodId,connection] of Object.entries(connections)) {
      const method=seed.methods.find(item=>item.id===methodId);
      if(!current.providerRuns.some(run=>run.descriptor.id===method.providerId)) current.providerRuns.push({descriptor:{id:method.providerId,displayName:'DEMO · '+method.title,version:'0.2.0-demo',apiVersion:1},permissions:clone(permissions[method.providerId]),status:'COMPLETED',durationMs:28,findingCount:0,message:'Simulated connection. No scanner, model or network requests were run.'});
    }
    current.providerRuns=current.providerRuns.map(run=>{
      run.permissions=clone(permissions[run.descriptor.id]);
      const method=seed.methods.find(item=>item.category!=='BUILT_IN'&&item.providerId===run.descriptor.id);
      if(method&&flags[method.id]===false)run.permissions.enabled=false;
      if (!run.permissions.enabled) { run.status='DISABLED';run.findingCount=0;run.durationMs=0; }
      return run;
    });
    current.findings=current.findings.filter(f=>{
      const id=f.providerId||'wrapperguard';const method=seed.methods.find(item=>item.category!=='BUILT_IN'&&item.providerId===id);
      return permissions[id]?.enabled!==false&&(!method||flags[method.id]!==false)&&(id!=='wrapperguard'||enabledBuiltin(f));
    }).map(f=>{
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
    const builtinActive=seed.methods.some(method=>method.category==='BUILT_IN'&&(flags[method.id]??method.defaultEnabled))&&permissions.wrapperguard?.enabled!==false;
    const externalActive=current.providerRuns.some(run=>run.descriptor.id!=='wrapperguard'&&run.permissions.enabled&&!seed.methods.some(method=>method.providerId===run.descriptor.id&&['RESEARCH','AI'].includes(method.category)));
    current.complete=(builtinActive||externalActive)&&current.providerRuns.some(run=>run.status==='COMPLETED')&&current.providerRuns.every(run=>run.status==='COMPLETED'||run.status==='DISABLED');
    if(!builtinActive&&!externalActive)current.notices.push({code:'WG-NO-ANALYSIS-PROVIDERS',message:'No security analysis providers are enabled; advisory AI and research methods cannot establish scan coverage.'});
    if(flags['builtin.script-graph']===false)current.edges=[];
    const disabled=seed.methods.filter(method=>method.category==='BUILT_IN'&&flags[method.id]===false);
    for(const method of disabled)current.notices.push({code:'WG-METHOD-DISABLED',message:'Security method disabled: '+method.id+'. Coverage is reduced.'});
    const active=current.findings.filter(f=>f.status==='ACTIVE');
    current.verdict=active.some(f=>f.policyAction==='BLOCK')?'EXECUTION_BLOCKED':!current.complete?'INCOMPLETE':active.some(f=>f.policyAction==='REVIEW')?'REVIEW_REQUIRED':'CLEAN';
    for (const run of current.providerRuns) run.findingCount=current.findings.filter(f=>f.providerId===run.descriptor.id).length;
    send(uiState('scanned',current));
    return current;
  }
  function field(form,label,value,choices) {
    const row=document.createElement('label');row.className='demo-field';row.textContent=label;
    const input=document.createElement(choices?'select':'input');
    if(choices)for(const [optionValue,title]of choices){const option=document.createElement('option');option.value=optionValue;option.textContent=title;input.append(option);}
    input.value=value;row.append(input);form.append(row);return input;
  }
  function setupMethod(id) {
    const method=seed.methods.find(item=>item.id===id);if(!method||method.category==='BUILT_IN')return;
    const form=document.createElement('form');form.id='demo-method-setup';
    if(method.category==='AI') {
      field(form,'Local model endpoint — simulated','http://127.0.0.1:1234/v1');field(form,'Loaded model name — simulated','atlas-demo-model');field(form,'Evidence','current',[['current','Current scan · finding summaries only']]);
    } else {
      const runner=!!method.runner;
      field(form,'Integration mode',runner?'runner':'report',runner?[['runner','Local runner · simulated'],['report','Report import · simulated']]:[['report','Report import · simulated']]);
      field(form,'SDK environment','managed',[['managed','Bundled SDK 0.2.0 · managed demo environment']]);
      field(form,runner?'Installed analyzer — example path':'JSON report — example path',runner?'/demo/user-tools/bin/'+method.runner:'/demo/reports/'+method.format+'.json');
      if(method.runner==='semgrep')field(form,'Analysis rules','bundled',[['bundled','Bundled Android/Ktor Java/Kotlin review rules'],['custom','Trusted custom rules · simulated']]);
      if(method.format==='psscriptanalyzer')field(form,'Trusted analyzer module — example path','/demo/modules/PSScriptAnalyzer/PSScriptAnalyzer.psd1');
      if(method.category==='DAST')field(form,'Exact target origin and path prefix','https://api.atlas.example/v1');
    }
    const summary=document.createElement('p');summary.className='demo-summary';
    summary.textContent=method.category==='AI'?'DEMO: advisory review of up to five finding summaries. No model request or code is sent. Real connections receive no action grants.':method.runner==='trivy'?'DEMO: no databases are downloaded. Real Trivy connections may download vulnerability databases and indexes. Starts optional, SCAN only and warning only.':method.runner==='osv'?'DEMO: no dependency data is sent. Real OSV connections send dependency names and versions, with resolution/build/install disabled. Starts warning only.':method.category==='DAST'?'DEMO: simulated scoped report import. No active scanner or target requests. Starts optional, SCAN only and warning only.':'DEMO: this button simulates installing and connecting the selected method. No tools are installed or run. Starts optional, SCAN only and warning only.';
    form.append(summary);
    dialog('Set up '+method.title,form,[['Cancel',()=>rebuild()],['Connect in demo',()=>{
      connections[id]={demo:true};flags[id]=true;
      permissions[method.providerId]={enabled:true,required:false,actions:method.category==='AI'?[]:['WARNING'],timeoutMs:method.category==='SUPPLY_CHAIN'?150000:45000};
      rebuild();toast('DEMO connection added. No software was installed, and no SDK or model ran.');
    }]]);
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
    const allowedActions=id==='ai.external-model'?[]:actions;
    for(const [key,label,checked] of [['enabled','Enabled',permissions[id].enabled],...(id==='ai.external-model'?[]:[['required','Required',permissions[id].required]]),...allowedActions.map(action=>[action,actionLabels[action],permissions[id].actions.includes(action)])]) {
      const row=document.createElement('label'),input=document.createElement('input');input.type='checkbox';input.checked=checked;input.name=key;inputs[key]=input;
      row.append(input,document.createTextNode(label));form.append(row);
    }
    dialog(run.descriptor.displayName+' permissions',form,[['Cancel'],['Apply in demo',()=>{
      permissions[id]={...permissions[id],enabled:inputs.enabled.checked,required:inputs.required?.checked||false,actions:allowedActions.filter(action=>inputs[action].checked)};
      const method=seed.methods.find(item=>item.category!=='BUILT_IN'&&item.providerId===id);if(method)flags[method.id]=permissions[id].enabled;
      rebuild();toast('Demo permissions updated. No User Settings or provider configuration files were changed.');
    }]]);
  }
  function onHostMessage(message) {
    if(message.type==='webview-error') { window.__WRAPPERGUARD_DEMO_ERRORS__.push(message.message);console.error(message.message);return; }
    if(message.type==='scan') { send(uiState('scanning'));setTimeout(()=>{rebuild();toast('Fictional demo result reloaded. No scripts or SDKs were run.');},450);return; }
    if(message.type==='configure-method') {setupMethod(message.methodId);return;}
    if(message.type==='toggle-method') {const method=seed.methods.find(item=>item.id===message.methodId);if(!method||typeof message.enabled!=='boolean')return;if(message.enabled&&method.category!=='BUILT_IN'&&!methodViews().find(item=>item.id===method.id).configured){setupMethod(method.id);return;}flags[method.id]=message.enabled;if(message.enabled&&method.providerId&&method.category!=='BUILT_IN'&&permissions[method.providerId])permissions[method.providerId].enabled=true;rebuild();toast('DEMO method flag changed. Real scans and User Settings are unchanged.');return;}
    if(message.type==='disconnect-method') {const method=seed.methods.find(item=>item.id===message.methodId);if(!method||method.category==='BUILT_IN')return;delete connections[method.id];flags[method.id]=false;if(permissions[method.providerId])permissions[method.providerId].enabled=false;rebuild();toast('DEMO connection removed. No real files were changed.');return;}
    if(message.type==='method-docs') {const method=seed.methods.find(item=>item.id===message.methodId);if(!method)return;const body=document.createElement('p');body.textContent=method.docsUrl||'Provider API v1 · user-selected method.';dialog('Method reference — demo',body,[['Close']]);return;}
    if(message.type==='baseline'||message.type==='suppress') { baselines.add(message.finding.fingerprint);rebuild();toast('Approved in the demo baseline only. No source files or trusted approvals were written.');return; }
    if(message.type==='unbaseline') { baselines.delete(message.finding.fingerprint);rebuild();toast('Demo approval removed. The finding is active again.');return; }
    if(message.type==='configure-provider') { configure(message.providerId);return; }
    if(message.type==='settings') { const content=document.createElement('pre');content.textContent=JSON.stringify(seed.settings,null,2);dialog('Atlas Payments — demo settings',content,[['Close']]);return; }
    if(message.type==='open') { const content=document.createElement('pre');content.textContent=message.finding.location.path+':'+(message.finding.location.line||1)+'\n\n'+(message.finding.evidence?.text||message.finding.message);dialog('Fictional file preview',content,[['Close']]);return; }
    if(message.type==='copy') { navigator.clipboard?.writeText(message.finding.evidence?.text||message.finding.message).catch(()=>{});toast('Demo evidence selected for copying.');return; }
    if(message.type==='export') { download('atlas-payments-demo-report.json',{...current,demo:true});return; }
    if(message.type==='sarif') { download('atlas-payments-demo.sarif.json',{$schema:'https://json.schemastore.org/sarif-2.1.0.json',version:'2.1.0',runs:[{tool:{driver:{name:'WrapperGuard simulated demo',version:'0.5.0'}},results:current.findings.map(f=>({ruleId:f.ruleId,message:{text:f.message},level:f.severity==='ERROR'?'error':f.severity==='WARN'?'warning':'note',properties:{providerId:f.providerId,demo:true}}))}]}); }
  }
  window.__WRAPPERGUARD_DEMO_ERRORS__=[];
  window.acquireVsCodeApi=()=>({postMessage:onHostMessage,getState:()=>null,setState:()=>{}});
  function theme(light) {
    document.body.classList.toggle('vscode-light',light);document.body.classList.toggle('vscode-dark',!light);
    document.getElementById('demo-theme').textContent=light?'Dark theme':'Light theme';
  }
  window.wrapperGuardDemo={getReport:()=>clone(current),getMethods:()=>clone(methodViews()),getFlags:()=>clone(flags),getBaseline:()=>[...baselines],setTheme:theme,reset:()=>{
    baselines=new Set(initial.findings.filter(f=>f.status==='SUPPRESSED').map(f=>f.fingerprint));permissions=Object.fromEntries(initial.providerRuns.map(run=>[run.descriptor.id,clone(run.permissions)]));flags=clone(seed.settings.methodFlags||{});connections={};rebuild();
  }};
  document.addEventListener('DOMContentLoaded',()=>{
    const version=document.querySelector('.brand-ver');if(version)version.textContent+=' · demo';
    const params=new URLSearchParams(location.search);theme(params.get('theme')==='light');
    document.getElementById('demo-theme').addEventListener('click',()=>theme(!document.body.classList.contains('vscode-light')));
    document.getElementById('demo-reset').addEventListener('click',()=>{window.wrapperGuardDemo.reset();toast('Demo restored to its original fictional baseline.');});
    rebuild();
    const tab=params.get('tab');if(['overview','findings','graph','policy','tools','research'].includes(tab))document.querySelector('[data-tab="'+tab+'"]').click();
    document.documentElement.dataset.demoReady='true';
  });
})();
