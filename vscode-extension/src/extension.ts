import * as vscode from 'vscode';
import { buildSettings, scanWorkspaceNative } from './scanner/entry';
import { toSarif } from './scanner/sarif';
import { WrapperGuardWebviewProvider, openFindingInEditor, findingFileUri } from './webviewUi';
import { BaselineEntry, Finding, ScanReport, ScanState } from './models';
import { WrapperGuardState } from './state';
import { mkdir, readFile, writeFile, rename, unlink, lstat } from 'node:fs/promises';
import * as path from 'node:path';
import { SECURITY_METHODS, methodById, providerMethodDisabled, validateMethodFlags, MethodView } from './methods/catalog';
import { configureIntegration } from './methods/setup';
import { EXTERNAL_PERMISSIONS, ProviderPermissions, ProviderRegistry, VettingApi, VettingOperation, validatePermissions } from './providers/sdk';
import { evaluateProviders } from './providers/coordinator';
import { loadCommandProviders, validateCommandConfiguration } from './providers/commandProvider';
import { userSetting } from './providers/userSettings';
import { DEFAULT_ENFORCE_POLICY } from './scanner/defaults';
import { configureCustomProvider, writeProviderConfiguration, readProviderConfiguration, isWithin } from './providers/customSetup';
import { captureInputSeal, sealsEqual } from './providers/inputSeal';
import { hostInputScopes, ObservedReadSet } from './providers/hostInputs';
import { observeProjectReads, readProjectFile } from './scanner/safePath';
import { realpath } from 'node:fs/promises';
import { storageDialog } from './methods/storage';
import { SECURITY_PRESETS } from './methods/presets';
import { HistoryStore, HistoryView, HistoryFinding, historyListItem, compareHistory as compareHistoryEntries } from './methods/history';
import { historyScope } from './methods/historyScope';
import { AutomationController, DEFAULT_AUTOMATION, validateAutomationPreferences } from './methods/automation';
import { configureAutomation } from './methods/automationSetup';
import { importPreset, exportPreset } from './methods/presetSharing';
import { createHash } from 'node:crypto';
import { maintainSdk } from './methods/maintenance';
import { editEvidenceSettings } from './methods/evidenceSettings';
export type { VettingApi, VettingProvider, VettingRequest, ProviderFinding, ProviderResult, ProviderDescriptor, ProviderPermissions, ProviderRun, VettingAction, VettingOperation } from './providers/sdk';
export type { ProviderInputScope } from './providers/inputScope';

export function activate(context: vscode.ExtensionContext): VettingApi {
  const state = new WrapperGuardState();
  const provider = new WrapperGuardWebviewProvider();
  const status = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 80);

  // The trusted baseline lives in the extension's global storage — OUTSIDE any repository.
  const baselineUri = vscode.Uri.joinPath(context.globalStorageUri, 'baseline.json');
  const managedConfigPath=path.join(context.globalStorageUri.fsPath,'integrations','providers.json');
  let configurationGeneration = 0;
  let latestEvaluation = 0;
  let changingConnections = false;
  let historyError:string|undefined;
  let historyView:HistoryView={enabled:false,limit:20,entries:[]};
  let selectedProject=vscode.workspace.workspaceFolders?.find(folder=>folder.uri.scheme==='file')?.uri.fsPath;
  const localProjects=()=> (vscode.workspace.workspaceFolders??[]).filter(folder=>folder.uri.scheme==='file');
  const workspaceRoots=()=>localProjects().map(folder=>folder.uri.fsPath);
  const selectedRoot=()=>localProjects().find(folder=>folder.uri.fsPath===selectedProject)?.uri??localProjects()[0]?.uri;
  const resolveProject=async(target?:string):Promise<vscode.Uri>=>{
    const chosen=target??selectedRoot()?.fsPath;if(!chosen||!path.isAbsolute(chosen))throw new Error('Open a local workspace folder before scanning.');
    const canonical=await realpath(chosen);
    for(const folder of localProjects())if(await realpath(folder.uri.fsPath)===canonical)return folder.uri;
    throw new Error('Choose an open workspace folder for this evaluation.');
  };
  const registry = new ProviderRegistry({
    descriptor: { id:'wrapperguard', displayName:'WrapperGuard built-in scanner', version:'0.6.0', apiVersion:1 },
    scan: async request => {
      const {report} = await scanWorkspaceNative(vscode.Uri.file(request.projectRoot),undefined,request);
      return {findings:report.findings,edges:report.edges,scannedFiles:report.scannedFiles,complete:report.complete,notices:report.notices};
    }
  });
  let automationReviewedIdentity:string|undefined;
  const automationIdentity=async(root:string):Promise<string>=>{
    const config=vscode.workspace.getConfiguration('wrapperGuard');const commands=await loadConfigured(root,userSetting(config.inspect('providerConfigPath'),''));
    return createHash('sha256').update(JSON.stringify({root:await realpath(root),settings:buildSettings(),permissions:userSetting(config.inspect('providerPermissions'),{}),sources:commands.sources.map(source=>[source.file,source.contents]),providers:registry.list().map(item=>item.descriptor),automation:automationPreferences()})).digest('hex');
  };
  const automation=new AutomationController(async(root,ids,signal)=>{
    if(!vscode.workspace.isTrusted||changingConnections||state.get().kind==='scanning')throw new Error('Automatic scanning is paused while the workspace is unavailable or busy.');
    if(automationReviewedIdentity!==await automationIdentity(root))throw new Error('Provider settings changed since automation review.');
    await evaluate('SCAN',root,{ids,signal});
  },view=>{provider.setAutomation(view);provider.setState(state.get());});
  const automationPreferences=()=>validateAutomationPreferences(userSetting(vscode.workspace.getConfiguration('wrapperGuard').inspect('automation'),DEFAULT_AUTOMATION));

  const loadConfigured = async (root: string, configuredPath: string) => {
    const sources:Array<{file:string;contents:string}>=[];
    const providers:import('./providers/sdk').VettingProvider[]=[];
    const permissions:Record<string,ProviderPermissions>=Object.create(null);
    const projectBindings:Record<string,string[]>=Object.create(null);const notApplicableIds:string[]=[];
    for (const file of [...new Set([configuredPath,managedConfigPath].filter(Boolean))]) {
      let loaded;
      try { loaded=await loadCommandProviders(file,root,true,workspaceRoots()); }
      catch (error) { if (file === managedConfigPath && (error as NodeJS.ErrnoException).code === 'ENOENT') continue; throw error; }
      for (const item of loaded.providers) {
        if (providers.some(existing=>existing.descriptor.id === item.descriptor.id)) throw new Error('A provider is connected twice. Remove its duplicate connection before scanning.');
        providers.push(item);
      }
      const grants={...loaded.permissions};
      if (file === managedConfigPath) delete grants.wrapperguard;
      Object.assign(permissions,grants); sources.push({file,contents:loaded.configContents});
      Object.assign(projectBindings,loaded.projectBindings);notApplicableIds.push(...loaded.notApplicableIds);
    }
    return {providers,permissions,sources,projectBindings,notApplicableIds};
  };
  const updateMethodViews = (flags: Record<string,boolean>, permissions: Record<string,ProviderPermissions>, ids: Set<string>,notApplicableIds:readonly string[]=[]): void => {
    const current=state.get();
    const views:MethodView[]=SECURITY_METHODS.map(method=>{
      const builtin=method.category === 'BUILT_IN';
      const granted=method.providerId ? permissions[method.providerId] : undefined;
      const enabled=flags[method.id] ?? (builtin ? method.defaultEnabled : granted?.enabled ?? false);
      const configured=builtin || !!method.providerId && ids.has(method.providerId);
      const run=current.kind === 'scanned' ? current.report.providerRuns?.find(item=>item.descriptor.id === method.providerId) : undefined;
      const status=notApplicableIds.includes(method.providerId??'')?'Bound to another project':!enabled ? 'Disabled' : builtin ? 'Ready locally' : !vscode.workspace.isTrusted ? 'Paused · Workspace Trust required' : !configured ? 'Setup needed' : granted?.enabled === false ? 'Paused · enable in Permissions' : run?.status === 'FAILED' ? 'Connection failed · configure to repair' : run?.status === 'TIMED_OUT' ? 'Timed out · configure to repair' : run?.status === 'COMPLETED' ? 'Connected · last run completed' : 'Connected · ready to scan';
      return {...method,enabled,configured,status};
    });
    provider.setMethods(views);
  };
  const refreshMethodViews = async (): Promise<void> => {
    try {
      const config=vscode.workspace.getConfiguration('wrapperGuard');
      const flags=validateMethodFlags(userSetting(config.inspect<Record<string,boolean>>('methodFlags'),{}));
      const saved=userSetting(config.inspect<Record<string,ProviderPermissions>>('providerPermissions'),{});
      const root=selectedRoot();
      const commands=root && vscode.workspace.isTrusted ? await loadConfigured(root.fsPath,userSetting(config.inspect<string>('providerConfigPath'),'')) : {providers:[],permissions:{} as Record<string,ProviderPermissions>,projectBindings:{} as Record<string,string[]>,notApplicableIds:[] as string[]};
      updateMethodViews(flags,{...saved,...commands.permissions},new Set([...registry.list(),...commands.providers].map(item=>item.descriptor.id)),commands.notApplicableIds);
      const current=state.get();
      const configuredPermissions=Object.fromEntries(('sources' in commands?commands.sources:[]).flatMap(source=>validateCommandConfiguration(JSON.parse(source.contents)).providers.map(item=>[item.id,item.permissions])));
      provider.setPlatform({projects:localProjects().map(folder=>({root:folder.uri.fsPath,name:folder.name??path.basename(folder.uri.fsPath)})),selectedProject:selectedRoot()?.fsPath,connections:[...registry.list(),...commands.providers].map(item=>({descriptor:item.descriptor,permissions:configuredPermissions[item.descriptor.id]??saved[item.descriptor.id]??EXTERNAL_PERMISSIONS,editable:commands.providers.some(command=>command.descriptor.id===item.descriptor.id),projectRoots:commands.projectBindings[item.descriptor.id],status:commands.notApplicableIds.includes(item.descriptor.id)?'Bound to another project':current.kind==='scanned'?current.report.providerRuns?.find(run=>run.descriptor.id===item.descriptor.id)?.status??'Connected':'Connected'}))});
    } catch { updateMethodViews({}, {}, new Set(registry.list().map(item=>item.descriptor.id))); }
    provider.setState(state.get());
  };
  const invalidate = (): void => {
    if(changingConnections)automation.pause('Paused · security configuration is being changed');
    configurationGeneration++;
    const current=state.get();
    if (current.kind === 'scanned') { const report={...current.report,stale:true,verdict:'STALE' as const};state.set({kind:'scanned',report,rawJson:JSON.stringify(report,null,2)}); }
  };
  const renderAll = (s: ScanState): void => {
    provider.setState(s);
    renderStatus(status, s);
  };
  const historyPreferences=():{enabled:boolean;limit:number}=>{
    const config=vscode.workspace.getConfiguration('wrapperGuard');const enabled=userSetting(config.inspect<boolean>('historyEnabled'),false),limit=userSetting(config.inspect<number>('historyLimit'),20);
    if(typeof enabled!=='boolean'||!Number.isInteger(limit)||limit<1||limit>100)throw new Error('History requires User Settings with a retention limit from 1 to 100.');return{enabled,limit};
  };
  const historyStore=()=>new HistoryStore(path.join(context.globalStorageUri.fsPath,'history'),workspaceRoots());
  const refreshHistory=async():Promise<void>=>{
    try{const preferences=historyPreferences(),root=selectedRoot();historyView={...preferences,entries:preferences.enabled&&root?historyStore().list(root.fsPath).map(historyListItem):[],error:historyError};}
    catch{historyView={enabled:false,limit:20,entries:[],error:'History could not be read safely. Review its User Settings or clear this project’s owned history files.'};}
    provider.setHistory(historyView);provider.setState(state.get());
  };

  const evaluate = async (operation: VettingOperation = 'SCAN', projectRoot?:string,automatic?:{ids:readonly string[];signal:AbortSignal}): Promise<ScanReport> => {
    const evaluation = ++latestEvaluation;
    const generation = configurationGeneration;
    if (changingConnections) throw new Error('A security connection is being configured. Finish setup before evaluating a gate.');
    if (!['SCAN','BUILD','SYNC'].includes(operation)) throw new Error('Invalid vetting operation.');
    const root = await resolveProject(projectRoot);
    selectedProject=root.fsPath;
    state.set({ kind: 'scanning' });
    renderAll(state.get());
    try {
      if (operation !== 'SCAN' && !vscode.workspace.isTrusted) throw new Error('Build and sync gates require a trusted workspace.');
      const settings = buildSettings();
      if(automatic)settings.methodFlags={...settings.methodFlags,...Object.fromEntries(SECURITY_METHODS.filter(method=>!automatic.ids.includes(method.providerId??'wrapperguard')).map(method=>[method.id,false]))};
      const config = vscode.workspace.getConfiguration('wrapperGuard');
      const configPath = userSetting(config.inspect<string>('providerConfigPath'), '');
      const saved = userSetting(config.inspect<Record<string,ProviderPermissions>>('providerPermissions'), {});
      const permissions: Record<string,ProviderPermissions> = Object.create(null);
      for (const [id,value] of Object.entries(saved)) permissions[id] = validatePermissions(value);
      const commands = vscode.workspace.isTrusted ? await loadConfigured(root.fsPath,configPath) : {providers:[],permissions:{},sources:[],projectBindings:{} as Record<string,string[]>,notApplicableIds:[] as string[]};
      // The user-owned file is authoritative for built-in/command grants. SDK grants live in User Settings.
      const effectivePermissions:Record<string,ProviderPermissions> = {...permissions,...commands.permissions};
      const registered = registry.list();
      if(automatic)for(const item of [...registered,...commands.providers])if(!automatic.ids.includes(item.descriptor.id))effectivePermissions[item.descriptor.id]={...(effectivePermissions[item.descriptor.id]??EXTERNAL_PERMISSIONS),enabled:false};
      for (const id of Object.keys(effectivePermissions)) if (providerMethodDisabled(settings.methodFlags,id)) effectivePermissions[id]={...effectivePermissions[id],enabled:false};
      for (const item of registered) if (providerMethodDisabled(settings.methodFlags,item.descriptor.id)) effectivePermissions[item.descriptor.id]={...(effectivePermissions[item.descriptor.id] ?? EXTERNAL_PERMISSIONS),enabled:false};
      const pausedSdk = new Set<string>();
      if (!vscode.workspace.isTrusted) {
        for (const registeredProvider of registered) {
          const id = registeredProvider.descriptor.id;
          if (id === 'wrapperguard') continue;
          const granted = effectivePermissions[id] ?? EXTERNAL_PERMISSIONS;
          if (granted.enabled) pausedSdk.add(id);
          effectivePermissions[id] = {...granted,enabled:false};
        }
      }
      let baseline: BaselineEntry[] = [];
      try {
        const data: unknown = JSON.parse(await readFile(baselineUri.fsPath,'utf8'));
        if (Array.isArray(data)) baseline = data.filter(e => e && typeof e.fingerprint === 'string' && (e.ruleId === undefined || typeof e.ruleId === 'string'));
      } catch { /* An absent baseline grants no approvals. */ }
      const allProviders=[...registered,...commands.providers];
      let historyBefore:ReturnType<typeof historyScope>|undefined;let historyLimit=20;
      try{const preferences=historyPreferences();historyLimit=preferences.limit;if(preferences.enabled)historyBefore=historyScope(settings,allProviders,effectivePermissions,commands.sources,path.dirname(managedConfigPath));}catch{historyError='History preferences are invalid. This scan still uses the configured security policy.';}
      const scopes=()=>hostInputScopes(allProviders,effectivePermissions,commands.sources.map(source=>source.file),path.dirname(managedConfigPath),baselineUri.fsPath);
      const before=operation==='SCAN'?undefined:captureInputSeal(root.fsPath,scopes(),workspaceRoots());
      const observed=new ObservedReadSet(root.fsPath);
      const report = await observeProjectReads(observed.observe,()=>vscode.window.withProgress(
        { location: vscode.ProgressLocation.Notification, title: 'WrapperGuard: Scanning workspace' },
        () => evaluateProviders({projectRoot:root.fsPath,policy:DEFAULT_ENFORCE_POLICY,settings,baseline,operation,signal:automatic?.signal??new AbortController().signal},
          {providers:[...registered,...commands.providers],permissions:effectivePermissions,concurrency:4,notApplicableProviders:commands.notApplicableIds})
      ));
      if(automatic)report.notices.push({code:'WG-AUTOMATED-SCAN',message:'Automatic SCAN scope includes only the reviewed providers: '+automatic.ids.join(', ')+'. No build or sync was launched.'});
      const connectedIds=new Set([...registered,...commands.providers].map(item=>item.descriptor.id));
      if (!vscode.workspace.isTrusted && (configPath || pausedSdk.size)) {
        report.notices.push({code:'WG-PROVIDERS-PAUSED',message:'External providers are paused until this workspace is trusted. Only built-in static analysis ran.'});
        report.complete=false;
        if (report.verdict !== 'EXECUTION_BLOCKED') report.verdict='INCOMPLETE';
        for (const run of report.providerRuns ?? []) if (pausedSdk.has(run.descriptor.id)) run.message='Paused: Workspace Trust is required.';
      }
      let changed=generation !== configurationGeneration;
      for (const source of commands.sources) if (source.contents !== await readFile(source.file,'utf8')) changed=true;
      if(before&&(!sealsEqual(before,captureInputSeal(root.fsPath,scopes(),workspaceRoots()))||!observed.verify()))changed=true;
      if (changed) {
        throw new Error('Security inputs or provider permissions changed during evaluation. Run the gate again.');
      }
      if(historyBefore){
        try{const after=historyScope(settings,allProviders,effectivePermissions,commands.sources,path.dirname(managedConfigPath));historyStore().record(root.fsPath,report,operation,{scopeId:after.scopeId,known:historyBefore.known&&after.known&&historyBefore.scopeId===after.scopeId},historyLimit);historyError=undefined;}
        catch{historyError='This scan completed, but its local history summary could not be saved safely. Enforcement and baseline approvals are unchanged.';}
      }
      provider.setSettings(settings);
      if (evaluation === latestEvaluation) state.set({ kind: 'scanned', report, rawJson:JSON.stringify(report,null,2) });
      updateMethodViews(settings.methodFlags ?? {},effectivePermissions,connectedIds);
      await refreshMethodViews();
      await refreshHistory();
      renderAll(state.get());
      return report;
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      if (evaluation === latestEvaluation) {
        state.set({ kind: 'failed', error: message });
        void vscode.window.showErrorMessage(`WrapperGuard scan failed: ${message}`);
      }
      renderAll(state.get());
      throw error;
    }
  };
  const scan = async (): Promise<void> => { try { await evaluate('SCAN'); } catch { /* Failure is shown in state and notification. */ } };
  const configureMethod = async (id: string): Promise<void> => {
    const method=methodById(id); if (!method || method.category === 'BUILT_IN') return;
    if (changingConnections) return;
    changingConnections=true;invalidate();
    try {
      if (await configureIntegration(context,method,managedConfigPath,state.get().kind === 'scanned' ? (state.get() as Extract<ScanState,{kind:'scanned'}>).report : undefined,selectedRoot()?.fsPath)) {
        const config=vscode.workspace.getConfiguration('wrapperGuard');
        const flags=validateMethodFlags(userSetting(config.inspect<Record<string,boolean>>('methodFlags'),{}));
        invalidate(); await config.update('methodFlags',{...flags,[id]:true},vscode.ConfigurationTarget.Global);
        await refreshMethodViews();
      }
    } catch (error) { void vscode.window.showErrorMessage('WrapperGuard: ' + (error instanceof Error ? error.message : String(error))); }
    finally { changingConnections=false;invalidate(); }
    await refreshMethodViews(); await scan();
  };
  const toggleMethod = async (id: string, enabled: boolean): Promise<void> => {
    if (changingConnections) { await refreshMethodViews();return; }
    const method=methodById(id); if (!method) return;
    try {
      if (enabled && method.category !== 'BUILT_IN') {
        const root=selectedRoot();
        if (!root) throw new Error('Open a local workspace first.');
        const config=vscode.workspace.getConfiguration('wrapperGuard');
        const commands=vscode.workspace.isTrusted ? await loadConfigured(root.fsPath,userSetting(config.inspect<string>('providerConfigPath'),'')) : {providers:[],sources:[]};
        if (![...registry.list(),...commands.providers].some(item=>item.descriptor.id === method.providerId)) { await configureMethod(id); return; }
        invalidate();
        if (commands.providers.some(item=>item.descriptor.id === method.providerId)) {
          for (const source of commands.sources) {
            const raw=JSON.parse(source.contents);const item=raw.providers?.find((entry:{id:string})=>entry.id === method.providerId);
            if (!item) continue;
            item.permissions={...EXTERNAL_PERMISSIONS,...item.permissions,enabled:true};validateCommandConfiguration(raw);
            const temporary=source.file+'.tmp';await writeFile(temporary,JSON.stringify(raw,null,2)+'\n',{encoding:'utf8',mode:0o600});await rename(temporary,source.file);
          }
        } else if (method.providerId) {
          const saved=userSetting(config.inspect<Record<string,ProviderPermissions>>('providerPermissions'),{});
          await config.update('providerPermissions',{...saved,[method.providerId]:{...(saved[method.providerId] ?? EXTERNAL_PERMISSIONS),enabled:true}},vscode.ConfigurationTarget.Global);
        }
      }
      const config=vscode.workspace.getConfiguration('wrapperGuard');
      const flags=validateMethodFlags(userSetting(config.inspect<Record<string,boolean>>('methodFlags'),{}));
      invalidate(); await config.update('methodFlags',{...flags,[id]:enabled},vscode.ConfigurationTarget.Global);
      await refreshMethodViews();
    } catch (error) { void vscode.window.showErrorMessage('WrapperGuard: ' + (error instanceof Error ? error.message : String(error))); await refreshMethodViews(); }
  };
  const disconnectMethod = async (id: string): Promise<void> => {
    const method=methodById(id);if (!method?.providerId || method.category === 'BUILT_IN' || changingConnections) return;
    changingConnections=true;invalidate();
    try {
      const root=selectedRoot();
      if (!root || !vscode.workspace.isTrusted) throw new Error('Trust a local workspace before changing provider connections.');
      const config=vscode.workspace.getConfiguration('wrapperGuard');
      const configuredPath=userSetting(config.inspect<string>('providerConfigPath'),'');
      for (const file of [...new Set([configuredPath,managedConfigPath].filter(Boolean))]) {
        let loaded;
        try { loaded=await loadCommandProviders(file,root.fsPath,true,workspaceRoots()); }
        catch(error){if(file === managedConfigPath && (error as NodeJS.ErrnoException).code === 'ENOENT')continue;throw error;}
        const raw=JSON.parse(loaded.configContents);
        if (!(raw.providers ?? []).some((item:{id:string})=>item.id === method.providerId)) continue;
        raw.providers=raw.providers.filter((item:{id:string})=>item.id !== method.providerId);validateCommandConfiguration(raw);
        const temp=file+'.tmp';await writeFile(temp,JSON.stringify(raw,null,2)+'\n',{encoding:'utf8',mode:0o600});await rename(temp,file);
      }
      const ownedProfile=path.join(path.dirname(managedConfigPath),id+'.profile.json');
      try {await unlink(ownedProfile);}catch(error){if((error as NodeJS.ErrnoException).code!=='ENOENT')throw error;}
      const flags=validateMethodFlags(userSetting(config.inspect<Record<string,boolean>>('methodFlags'),{}));
      const saved=userSetting(config.inspect<Record<string,ProviderPermissions>>('providerPermissions'),{});
      await config.update('providerPermissions',{...saved,[method.providerId]:{...(saved[method.providerId] ?? EXTERNAL_PERMISSIONS),enabled:false}},vscode.ConfigurationTarget.Global);
      await config.update('methodFlags',{...flags,[id]:false},vscode.ConfigurationTarget.Global);
    } catch(error){void vscode.window.showErrorMessage('WrapperGuard: '+(error instanceof Error?error.message:String(error)));}
    finally {changingConnections=false;invalidate();await refreshMethodViews();}
  };
  const configureProvider = async (id: string): Promise<void> => {
    if (changingConnections) return;
    changingConnections=true;invalidate();
    try {
      const current=state.get();
      let run=current.kind === 'scanned' ? current.report.providerRuns?.find(r=>r.descriptor.id === id) : undefined;
      if (!run) {
        const root=selectedRoot();
        if (!root || !vscode.workspace.isTrusted) return;
        const config=vscode.workspace.getConfiguration('wrapperGuard');
        const loaded=await loadConfigured(root.fsPath,userSetting(config.inspect<string>('providerConfigPath'),''));
        const registered=[...registry.list(),...loaded.providers].find(item=>item.descriptor.id === id);
        if (!registered) return;
        const saved=userSetting(config.inspect<Record<string,ProviderPermissions>>('providerPermissions'),{});
        run={descriptor:registered.descriptor,permissions:loaded.permissions[id] ?? saved[id] ?? EXTERNAL_PERMISSIONS,status:'DISABLED',durationMs:0,findingCount:0};
      }
      const connectedMethod=SECURITY_METHODS.find(method=>method.category !== 'BUILT_IN' && method.providerId === id);
      const advisoryOnly=connectedMethod?.category==='RESEARCH'||connectedMethod?.category==='AI';
      // Edit saved authority rather than project-filtered run permissions; a binding
      // must not silently revoke a connection's grant when reviewing another root.
      const activeRoot=selectedRoot();
      if(activeRoot&&vscode.workspace.isTrusted){const config=vscode.workspace.getConfiguration('wrapperGuard');const loaded=await loadConfigured(activeRoot.fsPath,userSetting(config.inspect<string>('providerConfigPath'),''));for(const source of loaded.sources){const item=validateCommandConfiguration(JSON.parse(source.contents)).providers.find(item=>item.id===id);if(item)run={...run,permissions:item.permissions};}}
      let managedOperations:string[]|undefined;
      if (connectedMethod && id !== 'ai.external-model') {
        try {const profile=JSON.parse(await readFile(path.join(path.dirname(managedConfigPath),connectedMethod.id+'.profile.json'),'utf8'));if(Array.isArray(profile.allowedOperations))managedOperations=profile.allowedOperations;}catch{/* An external provider owns its own operation support. */}
      }
      const choices=await vscode.window.showQuickPick([
        {label:'Enabled',value:'enabled',picked:run.permissions.enabled},
        ...(managedOperations ? [{label:'Run on build requests (also enabled by Block build)',value:'operation_BUILD',picked:managedOperations.includes('BUILD')},{label:'Run on sync requests (also enabled by Block sync)',value:'operation_SYNC',picked:managedOperations.includes('SYNC')}] : []),
        ...(advisoryOnly ? [] : [{label:'Required (fail closed within granted block actions)',value:'required',picked:run.permissions.required}]),
        ...(advisoryOnly ? [] : ['WARNING','MAJOR_WARNING','ALERT','BLOCK_BUILD','BLOCK_SYNC'] as const).map(action=>({label:action,value:action,picked:run.permissions.actions.includes(action)}))
      ],{canPickMany:true,title:'Permissions for ' + run.descriptor.displayName,ignoreFocusOut:true});
      if (!choices) return;
      const selected=choices.map(c=>c.value);
      const permissions=validatePermissions({...run.permissions,enabled:selected.includes('enabled'),required:selected.includes('required'),actions:selected.filter(a=>['WARNING','MAJOR_WARNING','ALERT','BLOCK_BUILD','BLOCK_SYNC'].includes(a)) as ProviderPermissions['actions']});
      const config=vscode.workspace.getConfiguration('wrapperGuard');
      const configuredPath=userSetting(config.inspect<string>('providerConfigPath'),'');
      const root=selectedRoot();
      let savedToFile=false;
      if (root && vscode.workspace.isTrusted) for (const file of [...new Set([configuredPath,managedConfigPath].filter(Boolean))]) {
        let loaded;
        try { loaded=await loadCommandProviders(file,root.fsPath,true,workspaceRoots()); }
        catch (error) { if (file === managedConfigPath && (error as NodeJS.ErrnoException).code === 'ENOENT') continue; throw error; }
        if (id === 'wrapperguard' ? file !== managedConfigPath : loaded.providers.some(item=>item.descriptor.id === id)) {
          const raw=JSON.parse(loaded.configContents);
          if (id === 'wrapperguard') raw.builtIn=permissions;
          else raw.providers=raw.providers.map((item:{id:string})=>item.id === id ? {...item,permissions} : item);
          validateCommandConfiguration(raw);
          // Update only our generated SDK profile when grants change. Third-party providers own their own requests.
          if (file === managedConfigPath && id !== 'ai.external-model') {
            const item=raw.providers.find((entry:{id:string})=>entry.id === id);
            const profilePath=item?.command?.[item.command.length-1];
            if (typeof profilePath === 'string' && item.command[1] === '-I' && item.command[2] === '-m' && item.command[3] === 'wrapperguard_sdk' && item.command[4] === '--profile' && path.dirname(profilePath) === path.dirname(managedConfigPath)) {
              const profile=JSON.parse(await readFile(profilePath,'utf8'));
              profile.requestedActions=permissions.actions;
              profile.allowedOperations=['SCAN',...(permissions.actions.includes('BLOCK_BUILD') || selected.includes('operation_BUILD') ? ['BUILD'] : []),...(permissions.actions.includes('BLOCK_SYNC') || selected.includes('operation_SYNC') ? ['SYNC'] : [])];
              await writeFile(profilePath,JSON.stringify(profile,null,2) + '\n','utf8');
            }
          }
          invalidate();const temp=file + '.tmp'; await writeFile(temp,JSON.stringify(raw,null,2) + '\n',{encoding:'utf8',mode:0o600});await rename(temp,file);savedToFile=true;break;
        }
      }
      if (!savedToFile) {
        const saved=userSetting(config.inspect<Record<string,ProviderPermissions>>('providerPermissions'),{});
        invalidate();await config.update('providerPermissions',{...saved,[id]:permissions},vscode.ConfigurationTarget.Global);
      }
      if (connectedMethod) {
        const flags=validateMethodFlags(userSetting(config.inspect<Record<string,boolean>>('methodFlags'),{}));
        await config.update('methodFlags',{...flags,[connectedMethod.id]:permissions.enabled},vscode.ConfigurationTarget.Global);
      }
      await refreshMethodViews();
    } catch (error) { void vscode.window.showErrorMessage('WrapperGuard: ' + (error instanceof Error ? error.message : String(error))); }
    finally { changingConnections=false;invalidate();await refreshMethodViews(); }
  };

  const mutateConnection=async(id:string|undefined,action:'edit'|'remove'|'toggle',enabled?:boolean):Promise<void>=>{
    if(changingConnections)return;changingConnections=true;invalidate();
    try{
      if(!vscode.workspace.isTrusted)throw new Error('Trust this workspace before changing SDK connections.');
      const root=await resolveProject();const config=vscode.workspace.getConfiguration('wrapperGuard');
      const commands=await loadConfigured(root.fsPath,userSetting(config.inspect<string>('providerConfigPath'),''));
      const source=id?commands.sources.find(item=>validateCommandConfiguration(JSON.parse(item.contents)).providers.some(provider=>provider.id===id)):undefined;
      const file=source?.file??managedConfigPath;
      const current=source?validateCommandConfiguration(JSON.parse(source.contents)):await readProviderConfiguration(file);
      const existing=current.providers.find(item=>item.id===id);
      if(id&&!existing){
        if(action==='toggle'&&registry.list().some(provider=>provider.descriptor.id===id)){
          const saved=userSetting(config.inspect<Record<string,ProviderPermissions>>('providerPermissions'),{});
          await config.update('providerPermissions',{...saved,[id]:{...(saved[id]??EXTERNAL_PERMISSIONS),enabled:!!enabled}},vscode.ConfigurationTarget.Global);return;
        }
        throw new Error('This provider connection no longer exists.');
      }
      if(action==='remove'){
        const confirm=await vscode.window.showQuickPick([{label:'Remove connection',remove:true},{label:'Cancel',remove:false}],{title:'Remove '+existing!.displayName+'?',placeHolder:'Only its registration is removed. Executables, scripts, reports and rule files are preserved.',ignoreFocusOut:true});
        if(!confirm?.remove)return;
        await writeProviderConfiguration(file,{...current,providers:current.providers.filter(item=>item.id!==id)},workspaceRoots());
      }else{
        const next=action==='toggle'?{...existing!,permissions:{...existing!.permissions,enabled:!!enabled}}:await configureCustomProvider(workspaceRoots(),existing,[...registry.list(),...commands.providers].map(item=>item.descriptor.id),root.fsPath);
        if(!next)return;
        await writeProviderConfiguration(file,{...current,providers:[...current.providers.filter(item=>item.id!==next.id),next]},workspaceRoots());
      }
    }catch(error){void vscode.window.showErrorMessage('WrapperGuard: '+(error instanceof Error?error.message:String(error)));}
    finally{changingConnections=false;invalidate();await refreshMethodViews();}
  };
  const manageStorage=async():Promise<void>=>{
    if(changingConnections)return;changingConnections=true;invalidate();
    try{
      const root=await resolveProject();const config=vscode.workspace.getConfiguration('wrapperGuard');
      const commands=await loadConfigured(root.fsPath,userSetting(config.inspect<string>('providerConfigPath'),''));
      // Every connected provider's inputs are protected, including disabled registrations.
      const enabled=Object.fromEntries(commands.providers.map(item=>[item.descriptor.id,{...EXTERNAL_PERMISSIONS,enabled:true}]));
      const scopes=hostInputScopes(commands.providers,enabled,commands.sources.map(item=>item.file),path.dirname(managedConfigPath),baselineUri.fsPath);
      await storageDialog(context.globalStorageUri.fsPath,workspaceRoots(),scopes.flatMap(scope=>scope.externalFiles??[]));
    }catch(error){void vscode.window.showErrorMessage('WrapperGuard: '+(error instanceof Error?error.message:String(error)));}
    finally{changingConnections=false;invalidate();await refreshMethodViews();}
  };
  const sdkMaintenance=async():Promise<void>=>{
    if(changingConnections)return;
    if(state.get().kind==='scanning'){void vscode.window.showWarningMessage('Wait for the current scan to finish before maintaining its SDK.');return;}
    changingConnections=true;invalidate();
    try{
      if(!vscode.workspace.isTrusted)throw new Error('Trust this workspace before SDK maintenance.');const root=await resolveProject();
      await maintainSdk(context,workspaceRoots(),async()=>{
        const config=vscode.workspace.getConfiguration('wrapperGuard');const loaded=await loadConfigured(root.fsPath,userSetting(config.inspect('providerConfigPath'),''));
        const references:string[]=[];let inspected=0;
        const collect=(value:unknown,depth=0):void=>{if(depth>32||++inspected>10000)throw new Error('Connection references exceed their inspection budget.');if(typeof value==='string'&&path.isAbsolute(value))references.push(value);else if(Array.isArray(value))value.forEach(item=>collect(item,depth+1));else if(value&&typeof value==='object')Object.values(value).forEach(item=>collect(item,depth+1));};
        for(const source of loaded.sources)collect(JSON.parse(source.contents));
        for(const method of SECURITY_METHODS){try{const file=path.join(path.dirname(managedConfigPath),method.id+'.profile.json');await lstat(file);const base=await realpath(context.globalStorageUri.fsPath);collect(JSON.parse(readProjectFile(base,path.join(base,'integrations',path.basename(file)),65536).toString('utf8')));}catch(error){if((error as NodeJS.ErrnoException).code!=='ENOENT')throw error;}}
        return references;
      });
    }catch(error){void vscode.window.showErrorMessage('WrapperGuard SDK maintenance: '+(error instanceof Error?error.message:String(error)));}
    finally{changingConnections=false;invalidate();await refreshMethodViews();}
  };
  const evidenceSettings=async(id:string):Promise<void>=>{
    const method=methodById(id);if(!method||method.category==='BUILT_IN'||changingConnections)return;changingConnections=true;invalidate();
    try{if(!vscode.workspace.isTrusted)throw new Error('Trust this workspace before editing evidence requirements.');await editEvidenceSettings(context.globalStorageUri.fsPath,method,workspaceRoots());}
    catch(error){void vscode.window.showErrorMessage('WrapperGuard evidence settings: '+(error instanceof Error?error.message:String(error)));}
    finally{changingConnections=false;invalidate();await refreshMethodViews();}
  };
  const applyPreset=async():Promise<void>=>{
    if(changingConnections)return;changingConnections=true;
    try{
      const selected=await vscode.window.showQuickPick(SECURITY_PRESETS.map(preset=>({label:preset.title,description:preset.summary,preset})),{title:'Choose a coverage preset',placeHolder:'Presets change method selection only. Tool connections, custom providers and grants are separate.',ignoreFocusOut:true});
      if(!selected)return;const preset=selected.preset;
      const enabled=SECURITY_METHODS.filter(method=>preset.methodFlags[method.id]);
      const confirm=await vscode.window.showQuickPick([{label:'Apply method selection',description:enabled.map(method=>method.title).join(', '),apply:true},{label:'Cancel',description:'Keep current method selection',apply:false}],{title:preset.title+' · '+enabled.length+' enabled methods',placeHolder:preset.notice+' Review setup and run a scan afterward.',ignoreFocusOut:true});
      if(!confirm?.apply)return;invalidate();await vscode.workspace.getConfiguration('wrapperGuard').update('methodFlags',{...preset.methodFlags},vscode.ConfigurationTarget.Global);await refreshMethodViews();
      await vscode.window.showInformationMessage(preset.title+' selected. Review connection readiness, then run a scan. No new permissions or installations were added.');
    }catch(error){void vscode.window.showErrorMessage('WrapperGuard: '+(error instanceof Error?error.message:String(error)));}
    finally{changingConnections=false;await refreshMethodViews();}
  };
  const sharePreset=async(action:'import'|'export'):Promise<void>=>{
    if(changingConnections)return;changingConnections=true;
    try{
      const config=vscode.workspace.getConfiguration('wrapperGuard');const current=validateMethodFlags(userSetting(config.inspect('methodFlags'),{}));
      const flags=Object.fromEntries(SECURITY_METHODS.map(method=>[method.id,current[method.id]??method.defaultEnabled]));
      if(action==='export')await exportPreset(flags);
      else{const preset=await importPreset(flags);if(preset){invalidate();await config.update('methodFlags',{...current,...preset.methodFlags},vscode.ConfigurationTarget.Global);await vscode.window.showInformationMessage(preset.title+' applied. Review connection readiness and scan explicitly. No permissions or tools changed.');}}
    }catch(error){void vscode.window.showErrorMessage('WrapperGuard preset: '+(error instanceof Error?error.message:String(error)));}
    finally{changingConnections=false;await refreshMethodViews();}
  };
  const automationAction=async(action:'configure'|'start'|'pause'):Promise<void>=>{
    if(action==='pause'){automation.pause();return;}
    try{
      if(changingConnections||!vscode.workspace.isTrusted)throw new Error('Finish setup and trust this workspace before configuring automatic scans.');
      const root=await resolveProject();const config=vscode.workspace.getConfiguration('wrapperGuard');const loaded=await loadConfigured(root.fsPath,userSetting(config.inspect('providerConfigPath'),''));
      const saved=userSetting(config.inspect<Record<string,ProviderPermissions>>('providerPermissions'),{});const flags=validateMethodFlags(userSetting(config.inspect('methodFlags'),{}));
      const candidates=[...registry.list(),...loaded.providers].map(item=>({id:item.descriptor.id,title:item.descriptor.displayName,enabled:!providerMethodDisabled(flags,item.descriptor.id)&&(loaded.permissions[item.descriptor.id]??saved[item.descriptor.id]??(item.descriptor.id==='wrapperguard'?{enabled:true}:EXTERNAL_PERMISSIONS)).enabled}));
      if(action==='configure'){
        automation.pause('Paused · reviewing automation preferences');const next=await configureAutomation(automationPreferences(),candidates);if(!next)return;await config.update('automation',next,vscode.ConfigurationTarget.Global);automation.configure(next);return;
      }
      const preferences=automationPreferences();automation.configure(preferences);
      const review=await vscode.window.showQuickPick([{label:'Start automatic SCAN for '+path.basename(root.fsPath),start:true,description:Object.entries(preferences.providers).map(([id,policy])=>id+' · '+policy.network+' / '+policy.cost).join('; ')},{label:'Keep paused',start:false}],{title:'Review this editor session’s automation',placeHolder:'Only enabled selected providers run after future triggers. Network and cost declarations do not sandbox processes. Closing/reloading pauses this session.',ignoreFocusOut:true});
      if(review?.start){automationReviewedIdentity=await automationIdentity(root.fsPath);automation.start(root.fsPath,candidates.filter(item=>item.enabled).map(item=>item.id));}
    }catch(error){automation.pause('Paused · review settings and connection readiness');void vscode.window.showErrorMessage('WrapperGuard automation: '+(error instanceof Error?error.message:String(error)));}
  };
  const historyAction=async(action:'enabled'|'retention'|'clear'|'compare',value?:boolean|string):Promise<void>=>{
    try{
      const root=await resolveProject();const config=vscode.workspace.getConfiguration('wrapperGuard');
      if(action==='enabled')await config.update('historyEnabled',!!value,vscode.ConfigurationTarget.Global);
      else if(action==='retention'){
        const limit=await vscode.window.showInputBox({title:'Local history retention per project',prompt:'Keep 1–100 summaries. Older owned summaries are removed after the next saved scan.',value:String(historyPreferences().limit),ignoreFocusOut:true,validateInput:value=>/^\d+$/.test(value)&&Number(value)>=1&&Number(value)<=100?undefined:'Enter an integer from 1 to 100.'});
        if(limit===undefined)return;if(!/^\d+$/.test(limit)||Number(limit)<1||Number(limit)>100)throw new Error('Choose a history retention limit from 1 to 100.');await config.update('historyLimit',Number(limit),vscode.ConfigurationTarget.Global);
      }else if(action==='clear'){
        const confirm=await vscode.window.showQuickPick([{label:'Clear this project’s local history',clear:true},{label:'Cancel',clear:false}],{title:'Clear history for '+path.basename(root.fsPath)+'?',placeHolder:'Only owned history summaries are removed. Baseline approvals, reports, source and enforcement settings are preserved.',ignoreFocusOut:true});
        if(!confirm?.clear)return;historyStore().clear(root.fsPath);
      }else{
        const entries=historyStore().list(root.fsPath),older=entries.find(entry=>entry.id===value),newer=entries[0];if(!older||!newer)throw new Error('Select an available summary for this project.');
        historyView={...historyView,comparison:compareHistoryEntries(older,newer),comparisonLabel:new Date(older.createdAt).toLocaleString()+' → '+new Date(newer.createdAt).toLocaleString()};provider.setHistory(historyView);provider.setState(state.get());return;
      }
      historyError=undefined;await refreshHistory();
    }catch{historyError='Local history could not be read or changed safely. Scan policy and baseline approvals are unchanged.';await refreshHistory();}
  };
  const openHistoryFinding=async(finding:HistoryFinding):Promise<void>=>{
    try{const root=await resolveProject();await openFindingInEditor({location:{path:finding.path}} as Finding,root.fsPath);}catch{void vscode.window.showInformationMessage('That current file is unavailable or outside the selected project. Historical summaries contain no source copy.');}
  };

  const exportReport = async (): Promise<void> => {
    const current = state.get();
    if (current.kind !== 'scanned') {
      void vscode.window.showInformationMessage('Run a scan before exporting a report.');
      return;
    }
    const destination = await vscode.window.showSaveDialog({
      defaultUri: vscode.Uri.file('wrapperguard-report.json'),
      filters: { 'JSON report': ['json'] }
    });
    if (!destination) {
      return;
    }
    await writeFile(destination.fsPath, current.rawJson, 'utf8');
    void vscode.window.showInformationMessage(`WrapperGuard report exported to ${destination.fsPath}`);
  };

  const exportSarif = async (): Promise<void> => {
    const current = state.get();
    if (current.kind !== 'scanned') {
      void vscode.window.showInformationMessage('Run a scan before exporting a report.');
      return;
    }
    const destination = await vscode.window.showSaveDialog({
      defaultUri: vscode.Uri.file('wrapperguard.sarif.json'),
      filters: { 'SARIF report': ['sarif', 'json'] }
    });
    if (!destination) {
      return;
    }
    await writeFile(destination.fsPath, JSON.stringify(toSarif(current.report), null, 2), 'utf8');
    void vscode.window.showInformationMessage('WrapperGuard SARIF exported to ' + destination.fsPath);
  };

  const copyEvidence = async (finding: Finding): Promise<void> => {
    await vscode.env.clipboard.writeText(finding.evidence?.text ?? finding.message);
    void vscode.window.showInformationMessage('WrapperGuard: evidence copied to the clipboard.');
  };

  /** Inline suppression: appends a wrapperguard:ignore marker at the finding's line, then rescans. */
  const suppress = async (finding: Finding): Promise<void> => {
    if (finding.providerId && finding.providerId !== 'wrapperguard') { await addBaseline(finding); return; }
    const root = selectedRoot();
    if (!root) {
      void vscode.window.showWarningMessage('Open a workspace folder first.');
      return;
    }
    const reason = await vscode.window.showInputBox({
      title: 'WrapperGuard: Suppress finding',
      prompt: 'Reason (written next to the suppression marker)',
      placeHolder: 'e.g. reviewed and approved'
    });
    if (reason === undefined) {
      return; // cancelled
    }
    const fileUri = await findingFileUri(finding,state.get().kind==='scanned'?(state.get() as Extract<ScanState,{kind:'scanned'}>).report.projectPath:undefined);
    const doc = await vscode.workspace.openTextDocument(fileUri);
    const line = Math.max(0, (finding.location.line ?? 1) - 1);
    const text = doc.lineAt(line).text;
    const name = finding.location.path.toLowerCase();
    const marker = name.endsWith('.gradle') || name.endsWith('.kts') || name.endsWith('.groovy')
      ? ' // wrapperguard:ignore ' + reason
      : name.endsWith('.bat') || name.endsWith('.cmd')
        ? ' & rem wrapperguard:ignore ' + reason
        : ' # wrapperguard:ignore ' + reason;
    const edit = new vscode.WorkspaceEdit();
    edit.insert(fileUri, new vscode.Position(line, text.length), marker);
    await vscode.workspace.applyEdit(edit);
    await doc.save();
    // #5: the inline marker alone is UNTRUSTED — approving the suppression means adding
    // the fingerprint to the trusted external baseline too. The scanner strips the marker
    // from evidence, so the fingerprint stays stable and the baseline matches after rescan.
    await mkdir(context.globalStorageUri.fsPath, { recursive: true });
    let entries: Array<{ fingerprint: string; ruleId?: string; reason?: string }> = [];
    try {
      entries = JSON.parse(await readFile(baselineUri.fsPath, 'utf8')) as Array<{ fingerprint: string; ruleId?: string; reason?: string }>;
    } catch {
      entries = [];
    }
    if (!entries.some((e) => e.fingerprint === finding.fingerprint)) {
      entries.push({ fingerprint: finding.fingerprint, ruleId: finding.ruleId, reason });
      await writeFile(baselineUri.fsPath, JSON.stringify(entries, null, 2), 'utf8');
    }
    void vscode.window.showInformationMessage('WrapperGuard: suppressed ' + finding.ruleId + ' — rescanning.');
    await scan();
  };

  /** Baseline: fingerprints stored outside the repo are trusted; matching findings stop blocking. */
  const addBaseline = async (finding: Finding): Promise<void> => {
    const reason = await vscode.window.showInputBox({
      title: 'WrapperGuard: Add to baseline',
      prompt: 'Reason (optional)',
      placeHolder: 'e.g. approved by security review'
    });
    if (reason === undefined) {
      return;
    }
    await mkdir(context.globalStorageUri.fsPath, { recursive: true });
    let entries: Array<{ fingerprint: string; ruleId?: string; reason?: string }> = [];
    try {
      entries = JSON.parse(await readFile(baselineUri.fsPath, 'utf8')) as Array<{ fingerprint: string; ruleId?: string; reason?: string }>;
    } catch {
      entries = [];
    }
    if (!entries.some((e) => e.fingerprint === finding.fingerprint)) {
      entries.push({ fingerprint: finding.fingerprint, ruleId: finding.ruleId, reason: reason || undefined });
      await writeFile(baselineUri.fsPath, JSON.stringify(entries, null, 2), 'utf8');
    }
    void vscode.window.showInformationMessage('WrapperGuard: added ' + finding.ruleId + ' to the baseline — rescanning.');
    await scan();
  };

  /** Restore: remove the fingerprint from the trusted baseline and rescan. */
  const removeBaseline = async (finding: Finding): Promise<void> => {
    let entries: Array<{ fingerprint: string; ruleId?: string; reason?: string }> = [];
    try {
      entries = JSON.parse(await readFile(baselineUri.fsPath, 'utf8')) as Array<{ fingerprint: string; ruleId?: string; reason?: string }>;
    } catch {
      entries = [];
    }
    const next = entries.filter((e) => e.fingerprint !== finding.fingerprint);
    await writeFile(baselineUri.fsPath, JSON.stringify(next, null, 2), 'utf8');
    void vscode.window.showInformationMessage('WrapperGuard: removed ' + finding.ruleId + ' from the baseline — rescanning.');
    await scan();
  };

  const withUiFocus=(action:()=>Promise<void>):void=>{void action().catch(()=>vscode.window.showErrorMessage('WrapperGuard could not complete this action. Review the connection and try again.')).finally(()=>provider.restoreControlFocus());};
  provider.handlers = {
    scan: () => void scan(),
    exportReport: () => void exportReport(),
    exportSarif: () => void exportSarif(),
    openFinding: (finding: Finding) => void openFindingInEditor(finding,state.get().kind==='scanned'?(state.get() as Extract<ScanState,{kind:'scanned'}>).report.projectPath:undefined),
    copyEvidence: (finding: Finding) => void copyEvidence(finding),
    suppress: (finding: Finding) => void suppress(finding),
    baseline: (finding: Finding) => void addBaseline(finding),
    unbaseline: (finding: Finding) => void removeBaseline(finding),
    configureProvider: id => withUiFocus(()=>configureProvider(id)),
    configureMethod: id => withUiFocus(()=>configureMethod(id)),
    toggleMethod: (id,enabled) => withUiFocus(()=>toggleMethod(id,enabled)),
    disconnectMethod: id => withUiFocus(()=>disconnectMethod(id)),
    openMethodDocs: id => { const url=methodById(id)?.docsUrl;if (url && /^https:\/\//.test(url)) void vscode.env.openExternal(vscode.Uri.parse(url)); },
    openSettings: () => void vscode.commands.executeCommand('workbench.action.openSettings','@ext:sal7one.wrapperguard')
    ,addProvider:()=>withUiFocus(()=>mutateConnection(undefined,'edit'))
    ,editProvider:id=>withUiFocus(()=>mutateConnection(id,'edit'))
    ,removeProvider:id=>withUiFocus(()=>mutateConnection(id,'remove'))
    ,toggleProvider:(id,enabled)=>withUiFocus(()=>mutateConnection(id,'toggle',enabled))
    ,manageStorage:()=>withUiFocus(manageStorage)
    ,sdkMaintenance:()=>withUiFocus(sdkMaintenance)
    ,evidenceSettings:id=>withUiFocus(()=>evidenceSettings(id))
    ,applyPreset:()=>withUiFocus(applyPreset)
    ,importPreset:()=>withUiFocus(()=>sharePreset('import'))
    ,exportPreset:()=>withUiFocus(()=>sharePreset('export'))
    ,automation:action=>withUiFocus(()=>automationAction(action))
    ,historyEnabled:enabled=>void historyAction('enabled',enabled)
    ,historyRetention:()=>void historyAction('retention')
    ,clearHistory:()=>void historyAction('clear')
    ,compareHistory:id=>void historyAction('compare',id)
    ,openHistoryFinding:finding=>void openHistoryFinding(finding)
    ,selectProject:root=>{if(!workspaceRoots().includes(root))return;automation.pause('Paused · selected project changed');selectedProject=root;invalidate();state.set({kind:'not-scanned'});void refreshMethodViews();void refreshHistory();}
  };

  const log = vscode.window.createOutputChannel('WrapperGuard');

  // A displayed verdict describes saved source at one point in time. File watchers
  // also invalidate evaluations in flight; cooperating gate clients still own the
  // final execution/input boundary after a decision is returned.
  const inputsChanged = (uri:vscode.Uri):void => {
    if(uri.scheme!=='file'||!workspaceRoots().some(root=>isWithin(path.resolve(uri.fsPath),path.resolve(root))))return;
    invalidate();
    const root=selectedRoot();if(root&&isWithin(path.resolve(uri.fsPath),path.resolve(root.fsPath)))automation.inputChanged(root.fsPath);
  };
  const sourceWatcher=vscode.workspace.createFileSystemWatcher('**/*');

  // Surface webview script errors in the Output channel (WrapperGuard) for debugging.
  provider.onWebviewError = (message: string) => {
    log.appendLine('Webview error: ' + message);
  };

  // Scan on open so a freshly opened workspace is never silently unprotected.
  const scanOnOpen = vscode.workspace.getConfiguration('wrapperGuard').get<boolean>('scanOnOpen', true);
  if (scanOnOpen && vscode.workspace.workspaceFolders && vscode.workspace.workspaceFolders.length > 0) {
    void scan();
  }

  context.subscriptions.push(
    status,
    log,
    sourceWatcher,
    automation,
    sourceWatcher.onDidCreate(inputsChanged),
    sourceWatcher.onDidChange(inputsChanged),
    sourceWatcher.onDidDelete(inputsChanged),
    vscode.workspace.onDidChangeTextDocument(event=>inputsChanged(event.document.uri)),
    vscode.workspace.onDidSaveTextDocument(document=>inputsChanged(document.uri)),
    state.onDidChange(renderAll),
    vscode.window.registerWebviewViewProvider(WrapperGuardWebviewProvider.viewType, provider, {
      webviewOptions: { retainContextWhenHidden: true }
    }),
    vscode.commands.registerCommand('wrapperGuard.scanWorkspace', scan),
    vscode.commands.registerCommand('wrapperGuard.exportReport', exportReport),
    vscode.commands.registerCommand('wrapperGuard.exportSarif', exportSarif)
    ,vscode.commands.registerCommand('wrapperGuard.evaluate', evaluate)
    ,vscode.workspace.onDidGrantWorkspaceTrust(() => { configurationGeneration++; void scan(); })
    ,vscode.workspace.onDidChangeConfiguration(event => {
      if(event.affectsConfiguration('wrapperGuard'))automation.pause('Paused · settings changed; review and Start again');
      if (event.affectsConfiguration('wrapperGuard')) { if(['allowedHosts','blockedCommands','requireDistributionSha256','providerPermissions','providerConfigPath','methodFlags'].some(key=>event.affectsConfiguration('wrapperGuard.'+key)))invalidate();void refreshMethodViews();void refreshHistory(); }
    })
    ,vscode.workspace.onDidChangeWorkspaceFolders?.(()=>{automation.pause('Paused · workspace folders changed');selectedProject=selectedRoot()?.fsPath;invalidate();state.set({kind:'not-scanned'});void refreshMethodViews();void refreshHistory();})??{dispose(){}}
  );

  try{automation.configure(automationPreferences());}catch{automation.pause('Paused · automation settings are invalid');}

  void refreshMethodViews();
  void refreshHistory();
  status.show();
  renderAll(state.get());
  log.appendLine('WrapperGuard extension activated.');
  return {
    apiVersion:1,
    registerProvider: external => {
      const registration = registry.registerProvider(external);
      invalidate();automation.pause('Paused · provider registration changed');void refreshMethodViews();
      return {dispose:() => { registration.dispose();invalidate();automation.pause('Paused · provider registration changed');void refreshMethodViews(); }};
    },
    evaluate,
    gate: async (operation,projectRoot) => {
      if (operation !== 'BUILD' && operation !== 'SYNC') throw new Error('Gate operation must be BUILD or SYNC.');
      const report = await evaluate(operation,projectRoot);
      return {allowed:!report.findings.some(f => f.status === 'ACTIVE' && f.policyAction === 'BLOCK'),report};
    }
  };
}

function renderStatus(status: vscode.StatusBarItem, s: ScanState): void {
  status.backgroundColor = undefined;
  status.color = undefined;
  switch (s.kind) {
    case 'not-scanned':
      status.text = '$(shield) WrapperGuard: Not scanned';
      status.tooltip = 'WrapperGuard — run a scan to establish a verdict.';
      break;
    case 'scanning':
      status.text = '$(sync~spin) WrapperGuard: Scanning…';
      status.tooltip = 'Reading wrappers, build files, and bootstrap scripts.';
      break;
    case 'failed':
      status.text = '$(error) WrapperGuard: Failed';
      status.backgroundColor = new vscode.ThemeColor('statusBarItem.errorBackground');
      status.tooltip = s.error;
      break;
    case 'scanned': {
      const r = s.report;
      const blockers = r.findings.filter((f) => f.policyAction === 'BLOCK').length;
      if (blockers > 0) {
        status.text = `$(error) WrapperGuard: ${blockers} blocker${blockers === 1 ? '' : 's'}`;
        status.backgroundColor = new vscode.ThemeColor('statusBarItem.errorBackground');
      } else if (!r.complete) {
        status.text = '$(warning) WrapperGuard: Incomplete';
        status.backgroundColor = new vscode.ThemeColor('statusBarItem.warningBackground');
      } else if (r.stale) {
        status.text = '$(warning) WrapperGuard: Stale';
        status.backgroundColor = new vscode.ThemeColor('statusBarItem.warningBackground');
      } else if (r.verdict === 'CLEAN') {
        status.text = '$(pass) WrapperGuard: Clean';
        status.color = new vscode.ThemeColor('testing.iconPassed');
      } else if (r.findings.length > 0) {
        status.text = `$(warning) WrapperGuard: ${r.findings.length} finding${r.findings.length === 1 ? '' : 's'}`;
        status.backgroundColor = new vscode.ThemeColor('statusBarItem.warningBackground');
      } else {
        status.text = '$(shield) WrapperGuard: Scanned';
      }
      status.tooltip = `${r.verdict} · ${r.policyName} · ${r.scannedFiles} files · ${r.durationMs} ms`;
      break;
    }
  }
  status.command = 'workbench.view.extension.wrapperGuard';
}

export function deactivate(): void {}
