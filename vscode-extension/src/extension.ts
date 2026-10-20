import * as vscode from 'vscode';
import { buildSettings, scanWorkspaceNative } from './scanner/entry';
import { toSarif } from './scanner/sarif';
import { WrapperGuardWebviewProvider, openFindingInEditor, findingFileUri } from './webviewUi';
import { BaselineEntry, Finding, ScanReport, ScanState } from './models';
import { WrapperGuardState } from './state';
import { mkdir, readFile, writeFile, rename, unlink } from 'node:fs/promises';
import * as path from 'node:path';
import { SECURITY_METHODS, methodById, providerMethodDisabled, validateMethodFlags, MethodView } from './methods/catalog';
import { configureIntegration } from './methods/setup';
import { EXTERNAL_PERMISSIONS, ProviderPermissions, ProviderRegistry, VettingApi, VettingOperation, validatePermissions } from './providers/sdk';
import { evaluateProviders } from './providers/coordinator';
import { loadCommandProviders, validateCommandConfiguration } from './providers/commandProvider';
import { userSetting } from './providers/userSettings';
import { DEFAULT_ENFORCE_POLICY } from './scanner/defaults';
export type { VettingApi, VettingProvider, VettingRequest, ProviderFinding, ProviderResult, ProviderDescriptor, ProviderPermissions, ProviderRun, VettingAction, VettingOperation } from './providers/sdk';

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
  const registry = new ProviderRegistry({
    descriptor: { id:'wrapperguard', displayName:'WrapperGuard built-in scanner', version:'0.5.0', apiVersion:1 },
    scan: async request => {
      const {report} = await scanWorkspaceNative(vscode.Uri.file(request.projectRoot),undefined,request);
      return {findings:report.findings,edges:report.edges,scannedFiles:report.scannedFiles,complete:report.complete,notices:report.notices};
    }
  });

  const loadConfigured = async (root: string, configuredPath: string) => {
    const sources:Array<{file:string;contents:string}>=[];
    const providers:import('./providers/sdk').VettingProvider[]=[];
    const permissions:Record<string,ProviderPermissions>=Object.create(null);
    for (const file of [...new Set([configuredPath,managedConfigPath].filter(Boolean))]) {
      let loaded;
      try { loaded=await loadCommandProviders(file,root,true); }
      catch (error) { if (file === managedConfigPath && (error as NodeJS.ErrnoException).code === 'ENOENT') continue; throw error; }
      for (const item of loaded.providers) {
        if (providers.some(existing=>existing.descriptor.id === item.descriptor.id)) throw new Error('A provider is connected twice. Remove its duplicate connection before scanning.');
        providers.push(item);
      }
      const grants={...loaded.permissions};
      if (file === managedConfigPath) delete grants.wrapperguard;
      Object.assign(permissions,grants); sources.push({file,contents:loaded.configContents});
    }
    return {providers,permissions,sources};
  };
  const updateMethodViews = (flags: Record<string,boolean>, permissions: Record<string,ProviderPermissions>, ids: Set<string>): void => {
    const current=state.get();
    const views:MethodView[]=SECURITY_METHODS.map(method=>{
      const builtin=method.category === 'BUILT_IN';
      const granted=method.providerId ? permissions[method.providerId] : undefined;
      const enabled=flags[method.id] ?? (builtin ? method.defaultEnabled : granted?.enabled ?? false);
      const configured=builtin || !!method.providerId && ids.has(method.providerId);
      const run=current.kind === 'scanned' ? current.report.providerRuns?.find(item=>item.descriptor.id === method.providerId) : undefined;
      const status=!enabled ? 'Disabled' : builtin ? 'Ready locally' : !vscode.workspace.isTrusted ? 'Paused · Workspace Trust required' : !configured ? (process.platform === 'win32' && method.runner === 'script-semantics' ? 'Requires Linux/macOS source runner' : 'Setup needed') : granted?.enabled === false ? 'Paused · enable in Permissions' : run?.status === 'FAILED' ? 'Connection failed · configure to repair' : run?.status === 'TIMED_OUT' ? 'Timed out · configure to repair' : run?.status === 'COMPLETED' ? 'Connected · last run completed' : 'Connected · ready to scan';
      return {...method,enabled,configured,status};
    });
    provider.setMethods(views);
  };
  const refreshMethodViews = async (): Promise<void> => {
    try {
      const config=vscode.workspace.getConfiguration('wrapperGuard');
      const flags=validateMethodFlags(userSetting(config.inspect<Record<string,boolean>>('methodFlags'),{}));
      const saved=userSetting(config.inspect<Record<string,ProviderPermissions>>('providerPermissions'),{});
      const root=vscode.workspace.workspaceFolders?.[0]?.uri;
      const commands=root && vscode.workspace.isTrusted ? await loadConfigured(root.fsPath,userSetting(config.inspect<string>('providerConfigPath'),'')) : {providers:[],permissions:{}};
      updateMethodViews(flags,{...saved,...commands.permissions},new Set([...registry.list(),...commands.providers].map(item=>item.descriptor.id)));
    } catch { updateMethodViews({}, {}, new Set(registry.list().map(item=>item.descriptor.id))); }
    provider.setState(state.get());
  };
  const invalidate = (): void => {
    configurationGeneration++;
    const current=state.get();
    if (current.kind === 'scanned') { const report={...current.report,stale:true,verdict:'STALE' as const};state.set({kind:'scanned',report,rawJson:JSON.stringify(report,null,2)}); }
  };
  const renderAll = (s: ScanState): void => {
    provider.setState(s);
    renderStatus(status, s);
  };

  const evaluate = async (operation: VettingOperation = 'SCAN'): Promise<ScanReport> => {
    const evaluation = ++latestEvaluation;
    const generation = configurationGeneration;
    if (changingConnections) throw new Error('A security connection is being configured. Finish setup before evaluating a gate.');
    if (!['SCAN','BUILD','SYNC'].includes(operation)) throw new Error('Invalid vetting operation.');
    const root = vscode.workspace.workspaceFolders?.[0]?.uri;
    if (!root || root.scheme !== 'file') throw new Error('Open a local workspace folder before scanning.');
    state.set({ kind: 'scanning' });
    renderAll(state.get());
    try {
      if (operation !== 'SCAN' && !vscode.workspace.isTrusted) throw new Error('Build and sync gates require a trusted workspace.');
      const settings = buildSettings();
      const config = vscode.workspace.getConfiguration('wrapperGuard');
      const configPath = userSetting(config.inspect<string>('providerConfigPath'), '');
      const saved = userSetting(config.inspect<Record<string,ProviderPermissions>>('providerPermissions'), {});
      const permissions: Record<string,ProviderPermissions> = Object.create(null);
      for (const [id,value] of Object.entries(saved)) permissions[id] = validatePermissions(value);
      const commands = vscode.workspace.isTrusted ? await loadConfigured(root.fsPath,configPath) : {providers:[],permissions:{},sources:[]};
      // The user-owned file is authoritative for built-in/command grants. SDK grants live in User Settings.
      const effectivePermissions:Record<string,ProviderPermissions> = {...permissions,...commands.permissions};
      const registered = registry.list();
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
      const report = await vscode.window.withProgress(
        { location: vscode.ProgressLocation.Notification, title: 'WrapperGuard: Scanning workspace' },
        () => evaluateProviders({projectRoot:root.fsPath,policy:DEFAULT_ENFORCE_POLICY,settings,baseline,operation,signal:new AbortController().signal},
          {providers:[...registered,...commands.providers],permissions:effectivePermissions,concurrency:4})
      );
      const connectedIds=new Set([...registered,...commands.providers].map(item=>item.descriptor.id));
      if (!vscode.workspace.isTrusted && (configPath || pausedSdk.size)) {
        report.notices.push({code:'WG-PROVIDERS-PAUSED',message:'External providers are paused until this workspace is trusted. Only built-in static analysis ran.'});
        report.complete=false;
        if (report.verdict !== 'EXECUTION_BLOCKED') report.verdict='INCOMPLETE';
        for (const run of report.providerRuns ?? []) if (pausedSdk.has(run.descriptor.id)) run.message='Paused: Workspace Trust is required.';
      }
      let changed=generation !== configurationGeneration;
      for (const source of commands.sources) if (source.contents !== await readFile(source.file,'utf8')) changed=true;
      if (changed) {
        throw new Error('Security inputs or provider permissions changed during evaluation. Run the gate again.');
      }
      provider.setSettings(settings);
      if (evaluation === latestEvaluation) state.set({ kind: 'scanned', report, rawJson:JSON.stringify(report,null,2) });
      updateMethodViews(settings.methodFlags ?? {},effectivePermissions,connectedIds);
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
      if (await configureIntegration(context,method,managedConfigPath,state.get().kind === 'scanned' ? (state.get() as Extract<ScanState,{kind:'scanned'}>).report : undefined)) {
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
        const root=vscode.workspace.workspaceFolders?.[0]?.uri;
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
      await refreshMethodViews(); await scan();
    } catch (error) { void vscode.window.showErrorMessage('WrapperGuard: ' + (error instanceof Error ? error.message : String(error))); await refreshMethodViews(); }
  };
  const disconnectMethod = async (id: string): Promise<void> => {
    const method=methodById(id);if (!method?.providerId || method.category === 'BUILT_IN' || changingConnections) return;
    changingConnections=true;invalidate();
    try {
      const root=vscode.workspace.workspaceFolders?.[0]?.uri;
      if (!root || !vscode.workspace.isTrusted) throw new Error('Trust a local workspace before changing provider connections.');
      const config=vscode.workspace.getConfiguration('wrapperGuard');
      const configuredPath=userSetting(config.inspect<string>('providerConfigPath'),'');
      for (const file of [...new Set([configuredPath,managedConfigPath].filter(Boolean))]) {
        let loaded;
        try { loaded=await loadCommandProviders(file,root.fsPath,true); }
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
    finally {changingConnections=false;invalidate();await refreshMethodViews();await scan();}
  };
  const configureProvider = async (id: string): Promise<void> => {
    if (changingConnections) return;
    changingConnections=true;invalidate();
    try {
      const current=state.get();
      let run=current.kind === 'scanned' ? current.report.providerRuns?.find(r=>r.descriptor.id === id) : undefined;
      if (!run) {
        const root=vscode.workspace.workspaceFolders?.[0]?.uri;
        if (!root || !vscode.workspace.isTrusted) return;
        const config=vscode.workspace.getConfiguration('wrapperGuard');
        const loaded=await loadConfigured(root.fsPath,userSetting(config.inspect<string>('providerConfigPath'),''));
        const registered=[...registry.list(),...loaded.providers].find(item=>item.descriptor.id === id);
        if (!registered) return;
        const saved=userSetting(config.inspect<Record<string,ProviderPermissions>>('providerPermissions'),{});
        run={descriptor:registered.descriptor,permissions:loaded.permissions[id] ?? saved[id] ?? EXTERNAL_PERMISSIONS,status:'DISABLED',durationMs:0,findingCount:0};
      }
      const connectedMethod=SECURITY_METHODS.find(method=>method.category !== 'BUILT_IN' && method.providerId === id);
      let managedOperations:string[]|undefined;
      if (connectedMethod && id !== 'ai.external-model') {
        try {const profile=JSON.parse(await readFile(path.join(path.dirname(managedConfigPath),connectedMethod.id+'.profile.json'),'utf8'));if(Array.isArray(profile.allowedOperations))managedOperations=profile.allowedOperations;}catch{/* An external provider owns its own operation support. */}
      }
      const choices=await vscode.window.showQuickPick([
        {label:'Enabled',value:'enabled',picked:run.permissions.enabled},
        ...(managedOperations ? [{label:'Run on build requests (also enabled by Block build)',value:'operation_BUILD',picked:managedOperations.includes('BUILD')},{label:'Run on sync requests (also enabled by Block sync)',value:'operation_SYNC',picked:managedOperations.includes('SYNC')}] : []),
        ...(id === 'ai.external-model' ? [] : [{label:'Required (fail closed within granted block actions)',value:'required',picked:run.permissions.required}]),
        ...(id === 'ai.external-model' ? [] : ['WARNING','MAJOR_WARNING','ALERT','BLOCK_BUILD','BLOCK_SYNC'] as const).map(action=>({label:action,value:action,picked:run.permissions.actions.includes(action)}))
      ],{canPickMany:true,title:'Permissions for ' + run.descriptor.displayName,ignoreFocusOut:true});
      if (!choices) return;
      const selected=choices.map(c=>c.value);
      const permissions=validatePermissions({...run.permissions,enabled:selected.includes('enabled'),required:selected.includes('required'),actions:selected.filter(a=>['WARNING','MAJOR_WARNING','ALERT','BLOCK_BUILD','BLOCK_SYNC'].includes(a)) as ProviderPermissions['actions']});
      const config=vscode.workspace.getConfiguration('wrapperGuard');
      const configuredPath=userSetting(config.inspect<string>('providerConfigPath'),'');
      const root=vscode.workspace.workspaceFolders?.[0]?.uri;
      let savedToFile=false;
      if (root && vscode.workspace.isTrusted) for (const file of [...new Set([configuredPath,managedConfigPath].filter(Boolean))]) {
        let loaded;
        try { loaded=await loadCommandProviders(file,root.fsPath,true); }
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
    finally { changingConnections=false;invalidate();await refreshMethodViews();await scan(); }
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
    const root = vscode.workspace.workspaceFolders?.[0]?.uri;
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
    const fileUri = await findingFileUri(finding);
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

  provider.handlers = {
    scan: () => void scan(),
    exportReport: () => void exportReport(),
    exportSarif: () => void exportSarif(),
    openFinding: (finding: Finding) => void openFindingInEditor(finding),
    copyEvidence: (finding: Finding) => void copyEvidence(finding),
    suppress: (finding: Finding) => void suppress(finding),
    baseline: (finding: Finding) => void addBaseline(finding),
    unbaseline: (finding: Finding) => void removeBaseline(finding),
    configureProvider: id => void configureProvider(id),
    configureMethod: id => void configureMethod(id),
    toggleMethod: (id,enabled) => void toggleMethod(id,enabled),
    disconnectMethod: id => void disconnectMethod(id),
    openMethodDocs: id => { const url=methodById(id)?.docsUrl;if (url && /^https:\/\//.test(url)) void vscode.env.openExternal(vscode.Uri.parse(url)); },
    openSettings: () => void vscode.commands.executeCommand('workbench.action.openSettings','@ext:sal7one.wrapperguard')
  };

  const log = vscode.window.createOutputChannel('WrapperGuard');

  // A displayed verdict describes saved source at one point in time. File watchers
  // also invalidate evaluations in flight; cooperating gate clients still own the
  // final execution/input boundary after a decision is returned.
  const inputsChanged = (uri:vscode.Uri):void => {
    const root=vscode.workspace.workspaceFolders?.[0]?.uri;
    if (!root || root.scheme !== 'file' || uri.scheme !== 'file') return;
    const relative=path.relative(path.resolve(root.fsPath),path.resolve(uri.fsPath));
    if (relative === '..' || relative.startsWith('..'+path.sep) || path.isAbsolute(relative)) return;
    invalidate();
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
      if (event.affectsConfiguration('wrapperGuard')) { configurationGeneration++; void scan(); }
    })
  );

  void refreshMethodViews();
  status.show();
  renderAll(state.get());
  log.appendLine('WrapperGuard extension activated.');
  return {
    apiVersion:1,
    registerProvider: external => {
      const registration = registry.registerProvider(external);
      configurationGeneration++;
      void scan();
      return {dispose:() => { registration.dispose(); configurationGeneration++; void scan(); }};
    },
    evaluate,
    gate: async operation => {
      if (operation !== 'BUILD' && operation !== 'SYNC') throw new Error('Gate operation must be BUILD or SYNC.');
      const report = await evaluate(operation);
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
