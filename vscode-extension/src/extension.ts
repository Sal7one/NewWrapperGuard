import * as vscode from 'vscode';
import { buildSettings, scanWorkspaceNative } from './scanner/entry';
import { toSarif } from './scanner/sarif';
import { WrapperGuardWebviewProvider, openFindingInEditor, findingFileUri } from './webviewUi';
import { BaselineEntry, Finding, ScanReport, ScanState } from './models';
import { WrapperGuardState } from './state';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { EXTERNAL_PERMISSIONS, ProviderPermissions, ProviderRegistry, VettingApi, VettingOperation, validatePermissions } from './providers/sdk';
import { evaluateProviders } from './providers/coordinator';
import { loadCommandProviders } from './providers/commandProvider';
import { userSetting } from './providers/userSettings';
import { DEFAULT_ENFORCE_POLICY } from './scanner/defaults';
export type { VettingApi, VettingProvider, VettingRequest, ProviderFinding, ProviderResult, ProviderDescriptor, ProviderPermissions, ProviderRun, VettingAction, VettingOperation } from './providers/sdk';

export function activate(context: vscode.ExtensionContext): VettingApi {
  const state = new WrapperGuardState();
  const provider = new WrapperGuardWebviewProvider();
  const status = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 80);

  // The trusted baseline lives in the extension's global storage — OUTSIDE any repository.
  const baselineUri = vscode.Uri.joinPath(context.globalStorageUri, 'baseline.json');
  let configurationGeneration = 0;
  let latestEvaluation = 0;
  const registry = new ProviderRegistry({
    descriptor: { id:'wrapperguard', displayName:'WrapperGuard built-in scanner', version:'0.4.0', apiVersion:1 },
    scan: async request => {
      const {report} = await scanWorkspaceNative(vscode.Uri.file(request.projectRoot),undefined,request);
      return {findings:report.findings,edges:report.edges,scannedFiles:report.scannedFiles,complete:report.complete,notices:report.notices};
    }
  });

  const renderAll = (s: ScanState): void => {
    provider.setState(s);
    renderStatus(status, s);
  };

  const evaluate = async (operation: VettingOperation = 'SCAN'): Promise<ScanReport> => {
    const evaluation = ++latestEvaluation;
    const generation = configurationGeneration;
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
      const commands = configPath && vscode.workspace.isTrusted ? await loadCommandProviders(configPath,root.fsPath,true) : {providers:[],permissions:{},configContents:''};
      // The user-owned file is authoritative for built-in/command grants. SDK grants live in User Settings.
      const effectivePermissions:Record<string,ProviderPermissions> = {...permissions,...commands.permissions};
      const registered = registry.list();
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
      if (!vscode.workspace.isTrusted && (configPath || pausedSdk.size)) {
        report.notices.push({code:'WG-PROVIDERS-PAUSED',message:'External providers are paused until this workspace is trusted. Only built-in static analysis ran.'});
        report.complete=false;
        if (report.verdict !== 'EXECUTION_BLOCKED') report.verdict='INCOMPLETE';
        for (const run of report.providerRuns ?? []) if (pausedSdk.has(run.descriptor.id)) run.message='Paused: Workspace Trust is required.';
      }
      if (generation !== configurationGeneration || configPath && vscode.workspace.isTrusted && commands.configContents !== await readFile(configPath,'utf8')) {
        throw new Error('Provider permissions changed during evaluation. Run the gate again.');
      }
      provider.setSettings(settings);
      if (evaluation === latestEvaluation) state.set({ kind: 'scanned', report, rawJson:JSON.stringify(report,null,2) });
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
  const configureProvider = async (id: string): Promise<void> => {
    const current = state.get();
    const run = current.kind === 'scanned' ? current.report.providerRuns?.find(r => r.descriptor.id === id) : undefined;
    if (!run) return;
    const configuredPath=userSetting(vscode.workspace.getConfiguration('wrapperGuard').inspect<string>('providerConfigPath'),'');
    if (configuredPath && (id === 'wrapperguard' || !registry.list().some(p=>p.descriptor.id === id))) {
      await vscode.window.showTextDocument(vscode.Uri.file(configuredPath));
      return;
    }
    const choices = await vscode.window.showQuickPick([
      {label:'Enabled',value:'enabled',picked:run.permissions.enabled},
      {label:'Required (fail closed within granted block actions)',value:'required',picked:run.permissions.required},
      ...(['WARNING','MAJOR_WARNING','ALERT','BLOCK_BUILD','BLOCK_SYNC'] as const).map(action => ({label:action,value:action,picked:run.permissions.actions.includes(action)}))
    ],{canPickMany:true,title:'Permissions for ' + run.descriptor.displayName,ignoreFocusOut:true});
    if (!choices) return;
    const selected = choices.map(c => c.value);
    const config = vscode.workspace.getConfiguration('wrapperGuard');
    const saved = userSetting(config.inspect<Record<string,ProviderPermissions>>('providerPermissions'),{});
    await config.update('providerPermissions',{...saved,[id]:{...run.permissions,enabled:selected.includes('enabled'),required:selected.includes('required'),actions:selected.filter(a => !['enabled','required'].includes(a))}},vscode.ConfigurationTarget.Global);
    await scan();
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
    openSettings: () => void vscode.commands.executeCommand('workbench.action.openSettings','@ext:sal7one.wrapperguard')
  };

  const log = vscode.window.createOutputChannel('WrapperGuard');

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
