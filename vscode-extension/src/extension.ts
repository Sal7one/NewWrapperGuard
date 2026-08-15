import * as vscode from 'vscode';
import { scanWorkspace } from './cliRunner';
import { WrapperGuardWebviewProvider, openFindingInEditor } from './webviewUi';
import { Finding, ScanState } from './models';
import { WrapperGuardState } from './state';
import { writeFile } from 'node:fs/promises';

export function activate(context: vscode.ExtensionContext): void {
  const state = new WrapperGuardState();
  const provider = new WrapperGuardWebviewProvider();
  const status = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 80);

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
        () => scanWorkspace(root)
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

  provider.handlers = {
    scan: () => void scan(),
    exportReport: () => void exportReport(),
    openFinding: (finding: Finding) => void openFindingInEditor(finding),
    copyEvidence: (finding: Finding) => void copyEvidence(finding)
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
