import * as vscode from 'vscode';
import { scanWorkspaceNative } from './scanner/entry';
import { WrapperGuardWebviewProvider, openFindingInEditor } from './webviewUi';
import { Finding, ScanState } from './models';
import { WrapperGuardState } from './state';
import { mkdir, readFile, writeFile } from 'node:fs/promises';

export function activate(context: vscode.ExtensionContext): void {
  const state = new WrapperGuardState();
  const provider = new WrapperGuardWebviewProvider();
  const status = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 80);

  // The trusted baseline lives in the extension's global storage — OUTSIDE any repository.
  const baselineUri = vscode.Uri.joinPath(context.globalStorageUri, 'baseline.json');

  const renderAll = (s: ScanState): void => {
    provider.setState(s);
    renderStatus(status, s);
  };

  const scan = async (): Promise<void> => {
    const root = vscode.workspace.workspaceFolders?.[0]?.uri;
    if (!root) {
      void vscode.window.showWarningMessage('Open a workspace folder before scanning.');
      return;
    }
    state.set({ kind: 'scanning' });
    renderAll(state.get());
    try {
      const { report, rawJson } = await vscode.window.withProgress(
        { location: vscode.ProgressLocation.Notification, title: 'WrapperGuard: Scanning workspace' },
        () => scanWorkspaceNative(root, baselineUri.fsPath)
      );
      state.set({ kind: 'scanned', report, rawJson });
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      state.set({ kind: 'failed', error: message });
      const hint = message.includes('ENOENT')
        ? '\n\nSet "wrapperGuard.cliPath" to the wrapperguard-cli executable (see the extension README).'
        : '';
      void vscode.window.showErrorMessage(`WrapperGuard scan failed: ${message}${hint}`);
    }
    renderAll(state.get());
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

  const copyEvidence = async (finding: Finding): Promise<void> => {
    await vscode.env.clipboard.writeText(finding.evidence?.text ?? finding.message);
    void vscode.window.showInformationMessage('WrapperGuard: evidence copied to the clipboard.');
  };

  /** Inline suppression: appends a wrapperguard:ignore marker at the finding's line, then rescans. */
  const suppress = async (finding: Finding): Promise<void> => {
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
    const fileUri = vscode.Uri.joinPath(root, finding.location.path);
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
    openFinding: (finding: Finding) => void openFindingInEditor(finding),
    copyEvidence: (finding: Finding) => void copyEvidence(finding),
    suppress: (finding: Finding) => void suppress(finding),
    baseline: (finding: Finding) => void addBaseline(finding),
    unbaseline: (finding: Finding) => void removeBaseline(finding)
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
    vscode.commands.registerCommand('wrapperGuard.exportReport', exportReport)
  );

  status.show();
  renderAll(state.get());
  log.appendLine('WrapperGuard extension activated.');
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
