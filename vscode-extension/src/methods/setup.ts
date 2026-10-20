import * as vscode from 'vscode';
import * as path from 'node:path';
import { access, mkdir, readFile, realpath, rename, stat, writeFile } from 'node:fs/promises';
import { constants } from 'node:fs';
import { CommandProviderConfig, validateCommandConfiguration } from '../providers/commandProvider';
import { SecurityMethod } from './catalog';
import { ScanReport } from '../models';
import { ensureManagedSdk, installManagedPythonTool, pythonVersion, PYTHON_TOOLS } from './bootstrap';
import { HOST_SOURCE_GLOBS } from '../providers/hostInputs';
import { GENERATED_EXCLUDES } from '../providers/inputScope';

export const SETUP_GRANTS = Object.freeze({enabled:true,required:false,actions:['WARNING'] as const,timeoutMs:45_000});
export const SEMGREP_RULE_PACKS=[{id:'legacy-android-ktor',title:'Android & Ktor baseline',summary:'Original six Java/Kotlin review rules',defaultEnabled:true},{id:'android-manifest',title:'Android manifest',summary:'Exported components, backup and transport declarations',defaultEnabled:false},{id:'android-webview',title:'Android WebView',summary:'Risky WebView configuration and bridges',defaultEnabled:false},{id:'jvm-crypto',title:'JVM cryptography',summary:'Review weak algorithms and static encryption inputs',defaultEnabled:false},{id:'ktor-runtime',title:'Ktor runtime',summary:'Review server and authentication configuration',defaultEnabled:false}];
export function integrationProfile(method: SecurityMethod, source: {reportPath?:string;executable?:string;modulePath?:string;rulesPath?:string;rulePacks?:string[];local?:boolean;targetScope?:unknown}): Record<string,unknown> {
  if (!method.providerId || !method.format) throw new Error('This method requires an external provider registration.');
  const tool = method.runner ?? method.format;
  if(source.rulePacks && (source.rulesPath || tool!=='semgrep' || !source.rulePacks.length || new Set(source.rulePacks).size!==source.rulePacks.length || source.rulePacks.some(id=>!SEMGREP_RULE_PACKS.some(pack=>pack.id===id))))throw new Error('Choose supported bundled Semgrep packs or one trusted rule file.');
  return {
    id:method.providerId,displayName:method.title,version:'0.3.0',analysisKind:method.category === 'SUPPLY_CHAIN' ? 'SCA' : method.category === 'DAST' ? 'DAST' : 'SAST',
    format:method.format,...(source.local ? {runner:tool === 'script-semantics' ? {tool} : {tool,executable:source.executable,...(tool === 'psscriptanalyzer' ? {modulePath:source.modulePath} : {}),...(source.rulesPath ? {rulesPath:source.rulesPath} : {}),...(source.rulePacks ? {rulePacks:[...source.rulePacks]} : {})}} : {reportPath:source.reportPath}),
    allowedOperations:['SCAN'],requestedActions:['WARNING'],...(source.targetScope ? {targetScope:source.targetScope} : {})
  };
}
function contained(file: string, root: string): boolean {
  const relative=path.relative(root,file); return relative === '' || relative !== '..' && !relative.startsWith('..' + path.sep) && !path.isAbsolute(relative);
}
export async function validateExecutable(file: string, projectRoots: string[]): Promise<string> {
  if (!path.isAbsolute(file)) throw new Error('Choose an absolute executable path.');
  const original=path.resolve(file);
  const anchored=path.join(await realpath(path.dirname(original)),path.basename(original));
  const resolved=await realpath(file);
  if (!(await stat(resolved)).isFile()) throw new Error('Choose an executable file.');
  await access(resolved,constants.X_OK);
  for (const root of projectRoots) {
    const actualRoot=await realpath(root);
    if (contained(resolved,actualRoot) || contained(anchored,actualRoot)) throw new Error('Tool executables must be installed outside the workspace.');
  }
  // A venv interpreter is commonly a symlink to base Python. Keep its selected path
  // so Python discovers the chosen environment's pyvenv.cfg and installed SDK.
  return original;
}
async function choosePython(context:vscode.ExtensionContext,roots:string[],managed:boolean):Promise<string|undefined> {
  const known=process.platform === 'win32' ? ['314','313','312','311','310'].map(version=>path.join(process.env.LOCALAPPDATA ?? '', 'Programs','Python','Python'+version,'python.exe')).filter(file=>path.isAbsolute(file)) : ['/opt/homebrew/bin/python3.12','/opt/homebrew/bin/python3.13','/opt/homebrew/bin/python3.14','/opt/homebrew/bin/python3','/usr/local/bin/python3','/usr/bin/python3'];
  const managedPython=path.join(context.globalStorageUri.fsPath,'sdk-environment',process.platform === 'win32' ? 'Scripts/python.exe' : 'bin/python');
  const candidates=await Promise.all([managedPython,...known].map(async file=>{try {const executable=await validateExecutable(file,roots);const version=await pythonVersion(executable,context.globalStorageUri.fsPath,!managed);return version ? {label:'Python '+version,description:executable,executable,browse:false} : undefined;}catch{return undefined;}}));
  const distinct=candidates.filter((item,index,all):item is NonNullable<typeof item>=>!!item && all.findIndex(other=>other?.description === item.description)===index);
  const choice=await vscode.window.showQuickPick([...distinct,{label:'Browse for Python…',description:managed ? 'Choose Python 3.10+ with venv support' : 'Choose Python from an existing SDK environment',executable:'',browse:true}],{title:managed ? 'Prepare the bundled SDK — choose Python' : 'Connect an existing SDK — choose Python',ignoreFocusOut:true});
  if (!choice) return undefined;
  if (!choice.browse && choice.executable) return choice.executable;
  const selected=await vscode.window.showOpenDialog({canSelectFiles:true,canSelectFolders:false,canSelectMany:false,title:managed ? 'Choose Python 3.10+ to prepare the managed SDK' : 'Choose Python with wrapperguard-sdk 0.3.0+',openLabel:'Use Python'});
  return selected?.[0] ? validateExecutable(selected[0].fsPath,roots) : undefined;
}
export function targetScopeFromUrl(value: string): {origins:string[];pathPrefixes:string[]} {
  const url=new URL(value);
  if (!['https:','http:'].includes(url.protocol) || url.username || url.password || url.search || url.hash || !url.hostname || /[\\\s]/.test(value) || /%|(?:^|\/)\.{1,2}(?:\/|$)/.test(value)) throw new Error('Use an exact HTTP(S) origin and optional path prefix without credentials, queries or fragments.');
  return {origins:[url.origin],pathPrefixes:[url.pathname.replace(/\/$/,'') || '/']};
}
async function atomicJson(file: string, value: unknown): Promise<void> {
  const temp=file + '.' + process.pid + '.tmp';
  await writeFile(temp,JSON.stringify(value,null,2) + '\n',{encoding:'utf8',mode:0o600});
  await rename(temp,file);
}
export function validateLocalEndpoint(value:string): void {
  const url=new URL(value);
  if (!/^http:\/\/(?:127\.0\.0\.1|\[::1\]):[0-9]+\/v1\/?$/.test(value) || url.username || url.password || url.search || url.hash || Number(value.match(/:([0-9]+)\/v1/)?.[1]) < 1) throw new Error('Use a numeric loopback HTTP endpoint with an explicit port and /v1.');
}
export function minimizedAiInput(report:ScanReport): Record<string,unknown> {
  return {schemaVersion:1,findings:report.findings.filter(finding=>finding.status === 'ACTIVE' && finding.providerId !== 'ai.external-model' && finding.kind !== 'AUDIT_EVIDENCE').slice(0,1000).map(finding=>({ruleId:finding.ruleId.slice(0,200),title:finding.title.slice(0,512),message:'Review the original finding in WrapperGuard.',severity:finding.severity,category:finding.category.slice(0,80),location:finding.location,policyAction:finding.policyAction,status:finding.status,providerId:finding.providerId,actions:[]}))};
}
export async function configureIntegration(context: vscode.ExtensionContext, method: SecurityMethod, managedPath: string, currentReport?: ScanReport,selectedProject?:string): Promise<boolean> {
  if (!vscode.workspace.isTrusted) { void vscode.window.showWarningMessage('Trust this workspace before connecting an external provider.'); return false; }
  const roots=(vscode.workspace.workspaceFolders ?? []).filter(folder=>folder.uri.scheme === 'file').map(folder=>folder.uri.fsPath);
  if (!roots.length) throw new Error('Open a local workspace folder first.');
  const projectRoot=await realpath(selectedProject??roots[0]);
  if(!await Promise.all(roots.map(root=>realpath(root))).then(values=>values.includes(projectRoot)))throw new Error('Choose an open project for this connection.');
  await mkdir(context.globalStorageUri.fsPath,{recursive:true,mode:0o700});
  const storage=await realpath(context.globalStorageUri.fsPath);
  for (const root of roots) if (contained(storage,await realpath(root))) throw new Error('SDK storage must be outside every workspace folder.');
  if (method.category === 'AI') {
    const endpoint=await vscode.window.showInputBox({title:'Connect a local AI model server',value:'http://127.0.0.1:1234/v1',prompt:'OpenAI-compatible HTTP endpoint on numeric loopback with an explicit port and /v1. No source files are sent.',ignoreFocusOut:true,validateInput:value=>{try {validateLocalEndpoint(value);return undefined;} catch {return 'Use http://127.0.0.1:PORT/v1 or http://[::1]:PORT/v1.';}}});
    if (endpoint === undefined) return false;
    validateLocalEndpoint(endpoint);
    const model=await vscode.window.showInputBox({title:'Local model name',prompt:'Enter the exact model ID loaded in your local server, e.g. the ID shown by LM Studio or Ollama.',ignoreFocusOut:true,validateInput:value=>!/^[A-Za-z0-9][A-Za-z0-9._:/@+-]{0,159}$/.test(value) ? 'Enter the exact model ID (letters, digits, . _ : / @ + -).' : undefined});
    if (model === undefined) return false;
    const input=currentReport ? await vscode.window.showQuickPick([{label:'Review the current scan findings',description:'Copies only finding summaries; excludes source evidence and call chains',current:true},{label:'Choose a WrapperGuard JSON report',description:'Use a separately exported report',current:false}],{title:'Choose evidence for the model',ignoreFocusOut:true}) : {current:false};
    if (!input) return false;
    const outputFormat=await vscode.window.showQuickPick([{label:'Structured JSON schema',description:'Recommended for compatible LM Studio and Ollama servers',responseFormat:'json_schema'},{label:'JSON object compatibility mode',description:'For servers without schema support; malformed output remains a failed review',responseFormat:'json_object'}],{title:'Local model output format',ignoreFocusOut:true});
    if(!outputFormat)return false;
    let inputReportPath:string;
    await mkdir(path.dirname(managedPath),{recursive:true,mode:0o700});
    if (input.current) {
      inputReportPath=path.join(path.dirname(managedPath),'ai-input.json');
      await atomicJson(inputReportPath,minimizedAiInput(currentReport!));
    } else {
      const selected=await vscode.window.showOpenDialog({canSelectFiles:true,canSelectFolders:false,canSelectMany:false,title:'Choose a WrapperGuard JSON report outside the workspace',openLabel:'Use finding report',filters:{'WrapperGuard JSON':['json']}});
      if (!selected?.[0]) return false;
      inputReportPath=await realpath(selected[0].fsPath);
      for (const root of roots) if (contained(inputReportPath,await realpath(root))) throw new Error('AI input reports must be outside the workspace.');
    }
    const environment=await vscode.window.showQuickPick([{label:'Create a managed SDK environment',description:'Recommended · installs the bundled SDK locally',managed:true},{label:'Use an existing SDK environment',description:'Python with wrapperguard-sdk 0.3.0 or later',managed:false}],{title:'Local AI review — SDK environment',ignoreFocusOut:true});
    if (!environment) return false;
    const selectedPython=await choosePython(context,roots,environment.managed);
    if (!selectedPython) return false;
    const python=environment.managed ? await ensureManagedSdk(context,selectedPython) : selectedPython;
    if (!await reviewConnection(method,'Reviews up to five finding summaries on your selected loopback model server. No source evidence, code execution, build or sync permissions.')) return false;
    const profilePath=path.join(path.dirname(managedPath),method.id + '.profile.json');
    await atomicJson(profilePath,{id:method.providerId,displayName:method.title,version:'0.3.0',analysisKind:'AI',format:'ai-review',projectRoot,runner:{tool:'local-ai',endpoint,model:model.trim(),inputReportPath,responseFormat:outputFormat.responseFormat,timeoutMs:30000},allowedOperations:['SCAN'],requestedActions:[]});
    await saveConnection(managedPath,roots,{id:method.providerId!,displayName:method.title,version:'0.3.0',command:[python,'-I','-m','wrapperguard_sdk','--profile',profilePath],permissions:{...SETUP_GRANTS,actions:[]},projectRoots:[projectRoot]});
    return true;
  }
  if (!method.format) throw new Error('This method does not yet declare a supported integration format.');
  const tool=method.runner ?? method.format;
  if (process.platform === 'win32' && tool === 'script-semantics') {void vscode.window.showInformationMessage('Script behavior analysis currently requires the POSIX source snapshot runner. Use WrapperGuard in a Linux/WSL environment for this optional method.');return false;}
  const localTools=new Set(['bandit','semgrep','psscriptanalyzer','trivy','osv','script-semantics']);
  const localSupported=localTools.has(tool) && process.platform !== 'win32';
  const choice=method.format === 'script-semantics' ? {local:true} : await vscode.window.showQuickPick([
    ...(localSupported ? [{label:'Run installed tool locally',description:'Fixed SDK runner · no repository commands · SCAN only',local:true}] : []),
    {label:'Import an existing JSON report',description:'Read findings only; does not run the scanner',local:false}
  ],{title:method.title + ' — choose an integration',ignoreFocusOut:true});
  if (!choice) return false;
  const environment=await vscode.window.showQuickPick([{label:'Create a managed SDK environment',description:'Recommended · installs the bundled SDK locally, without a download',managed:true},{label:'Use an existing SDK environment',description:'Select Python with wrapperguard-sdk already installed',managed:false}],{title:'Connect ' + method.title + ' — SDK environment',ignoreFocusOut:true});
  if (!environment) return false;
  const selectedPython=await choosePython(context,roots,environment.managed);
  if (!selectedPython) return false;
  const python=environment.managed ? await ensureManagedSdk(context,selectedPython) : selectedPython;
  let executable:string|undefined,modulePath:string|undefined,rulesPath:string|undefined,rulePacks:string[]|undefined,reportPath:string|undefined,targetScope:unknown;
  if (choice.local && method.format !== 'script-semantics') {
    const pythonTool=tool === 'bandit' || tool === 'semgrep' ? tool : undefined;
    const spec=pythonTool ? PYTHON_TOOLS[pythonTool] : undefined;
    const install=pythonTool && spec && environment.managed ? await vscode.window.showQuickPick([{label:'Install '+spec.name+' in the managed environment',description:'Downloads '+spec.name+' '+spec.version+' and its dependencies from PyPI',install:true},{label:'Choose an installed '+spec.name,description:'Use an existing executable outside the workspace',install:false}],{title:'Connect '+method.title,ignoreFocusOut:true}) : {install:false};
    if (!install) return false;
    if (install.install && pythonTool) executable=await installManagedPythonTool(context,python,pythonTool);
    else {
      const selected=await vscode.window.showOpenDialog({canSelectFiles:true,canSelectFolders:false,canSelectMany:false,title:'Choose installed ' + (method.format === 'psscriptanalyzer' ? 'PowerShell (pwsh) with PSScriptAnalyzer' : method.title) + ' executable',openLabel:'Use scanner'});
      if (!selected?.[0]) return false;
      executable=await validateExecutable(selected[0].fsPath,roots);
    }
    if (method.format === 'psscriptanalyzer') {
      const selected=await vscode.window.showOpenDialog({canSelectFiles:true,canSelectFolders:true,canSelectMany:false,title:'Choose the installed PSScriptAnalyzer module or its .psd1 manifest',openLabel:'Use analyzer module'});
      if (!selected?.[0]) return false;
      modulePath=await realpath(selected[0].fsPath);
      for (const root of roots) if (contained(modulePath,await realpath(root))) throw new Error('PSScriptAnalyzer modules must be installed outside the workspace.');
    }
    if (tool === 'semgrep') {
      const selectedRules=await vscode.window.showQuickPick([{label:'Choose bundled review packs',description:'Independent Android, JVM and Ktor packs · no rule download',custom:false},{label:'Choose a trusted rule file',description:'An absolute YAML file outside the workspace',custom:true}],{title:'Choose Semgrep rules',ignoreFocusOut:true});
      if (!selectedRules) return false;
      if (selectedRules.custom) {
        const selected=await vscode.window.showOpenDialog({canSelectFiles:true,canSelectFolders:false,canSelectMany:false,title:'Choose a trusted Semgrep YAML rule file outside the workspace',openLabel:'Use rule file',filters:{'Semgrep YAML':['yml','yaml']}});
        if (!selected?.[0]) return false;
        rulesPath=await realpath(selected[0].fsPath);
        for (const root of roots) if (contained(rulesPath,await realpath(root))) throw new Error('Semgrep rules must be selected outside the workspace.');
      }else{
        const packs=await vscode.window.showQuickPick(SEMGREP_RULE_PACKS.map(pack=>({label:pack.title,description:pack.summary,id:pack.id,picked:pack.defaultEnabled})),{title:'Select bundled Semgrep rule packs',canPickMany:true,placeHolder:'Each pack is optional. These focused checks provide review evidence, not complete security coverage.',ignoreFocusOut:true});
        if(!packs)return false;if(!packs.length)throw new Error('Select at least one Semgrep rule pack.');rulePacks=packs.map(pack=>pack.id);
      }
    }
  } else if (!choice.local) {
    const selected=await vscode.window.showOpenDialog({canSelectFiles:true,canSelectFolders:false,canSelectMany:false,title:'Choose a JSON report outside every workspace folder',openLabel:'Use report',filters:{'JSON report':['json','sarif']}});
    if (!selected?.[0]) return false;
    const original=selected[0].fsPath;reportPath=await realpath(original);const anchored=path.join(await realpath(path.dirname(original)),path.basename(original));
    for(const root of roots){const actual=await realpath(root);if(contained(reportPath,actual)||contained(anchored,actual))throw new Error('Imported reports must be outside every workspace folder.');}
  }
  if (method.category === 'DAST') {
    const target=await vscode.window.showInputBox({title:'DAST report scope',prompt:'Exact authorized origin and optional path prefix, e.g. https://api.example.com/v1. Import only; no active scan.',ignoreFocusOut:true,validateInput:value=>{try {targetScopeFromUrl(value);return undefined;} catch {return 'Enter an exact HTTP(S) origin and optional path prefix.';}}});
    if (target === undefined) return false;
    targetScope=targetScopeFromUrl(target);
  }
  const directory=path.dirname(managedPath);
  await mkdir(directory,{recursive:true,mode:0o700});
  const actualDirectory=await realpath(directory);
  for (const root of roots) if (contained(actualDirectory,await realpath(root))) throw new Error('Integration profiles must be stored outside every workspace folder.');
  const profilePath=path.join(actualDirectory,method.id + '.profile.json');
  const profile=integrationProfile(method,{local:choice.local,executable,modulePath,rulesPath,rulePacks,reportPath,targetScope});
  if(!choice.local)profile.projectRoot=projectRoot;
  if (choice.local && profile.runner && typeof profile.runner === 'object') {
    Object.assign(profile.runner,{timeoutMs:method.category === 'SUPPLY_CHAIN' ? 120000 : 30000});
    if (method.format === 'trivy') Object.assign(profile.runner,{cachePath:path.join(actualDirectory,'cache','trivy')});
  }
  const network= !choice.local ? 'Imports the selected report only; no scanner or network requests. Report age and coverage depend on its producer.' : method.format === 'trivy' ? 'Trivy may download vulnerability databases and indexes. It receives only a bounded source snapshot; no build or dependency install is run.' : method.format === 'osv' ? 'OSV-Scanner sends dependency names and versions to OSV. Dependency resolution, builds and package installation are disabled.' : 'Runs the selected fixed analyzer on a bounded snapshot. Project scripts and build commands are not executed.';
  if (!await reviewConnection(method,network)) return false;
  const registered={id:method.providerId!,displayName:method.title,version:'0.3.0',command:[python,'-I','-m','wrapperguard_sdk','--profile',profilePath],permissions:{...SETUP_GRANTS,timeoutMs:method.category === 'SUPPLY_CHAIN' && choice.local ? 150000 : SETUP_GRANTS.timeoutMs,actions:[...SETUP_GRANTS.actions]},...(!choice.local?{projectRoots:[projectRoot]}:{})};
  await atomicJson(profilePath,profile);
  await saveConnection(managedPath,roots,registered);
  return true;
}
async function reviewConnection(method: SecurityMethod, description: string): Promise<boolean> {
  const choice=await vscode.window.showQuickPick([{label:'Connect and run a scan',description,detail:method.category === 'AI' ? 'Advisory evidence only · no actions granted' : 'Starts with warnings only · optional provider · no build or sync blocking',connect:true},{label:'Cancel setup',description:'Keep the current connection unchanged',connect:false}],{title:method.title + ' — connection summary',ignoreFocusOut:true});
  return choice?.connect === true;
}
async function saveConnection(managedPath: string, roots: string[], registered: CommandProviderConfig['providers'][number]): Promise<void> {
  await mkdir(path.dirname(managedPath),{recursive:true,mode:0o700});
  const actualDirectory=await realpath(path.dirname(managedPath));
  for (const root of roots) if (contained(actualDirectory,await realpath(root))) throw new Error('Provider configuration must be outside the workspace.');
  let config:CommandProviderConfig={apiVersion:1,providers:[]};
  try { config=validateCommandConfiguration(JSON.parse(await readFile(managedPath,'utf8'))); }
  catch (error) { if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error; }
  const method=(await import('./catalog')).SECURITY_METHODS.find(item=>item.providerId===registered.id && item.category!=='BUILT_IN');
  const profileFile=registered.command[registered.command.length-1];
  const profile=JSON.parse(await readFile(profileFile,'utf8'));
  const externalFiles=[profileFile,profile.reportPath,profile.runner?.rulesPath,profile.runner?.modulePath,profile.runner?.inputReportPath].filter((file):file is string=>typeof file==='string');
  const canonicalFiles=await Promise.all(externalFiles.map(file=>realpath(file)));
  const connection={...registered,...(method?{category:method.category,summary:method.summary,tags:['first-party-adapter']}:{}),inputScope:{includes:profile.runner && profile.runner.tool!=='local-ai'?[...HOST_SOURCE_GLOBS]:[],excludes:[...GENERATED_EXCLUDES],externalFiles:[...new Set(canonicalFiles)]}};
  const next=validateCommandConfiguration({...config,providers:[...config.providers.filter(item=>item.id !== registered.id),connection]});
  await atomicJson(managedPath,next);
}
