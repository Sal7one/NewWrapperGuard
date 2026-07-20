import * as vscode from 'vscode';
import { scanWorkspace } from './cliRunner';
import { FindingsTreeProvider } from './findingsTree';
import { Finding, ScanReport } from './models';

export function activate(context: vscode.ExtensionContext): void {
  const provider = new FindingsTreeProvider();
  const status = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 80);
  status.command = 'wrapperGuard.scanWorkspace';
  status.text = '$(shield) WrapperGuard: Not scanned';
  status.show();

  context.subscriptions.push(
    status,
    vscode.window.registerTreeDataProvider('wrapperGuard.findings', provider),
    vscode.commands.registerCommand('wrapperGuard.scanWorkspace', async () => {
      const root = vscode.workspace.workspaceFolders?.[0]?.uri;
      if (!root) {
        void vscode.window.showWarningMessage('Open a workspace before scanning.');
        return;
      }
      status.text = '$(sync~spin) WrapperGuard: Scanning';
      try {
        const report = await vscode.window.withProgress(
          { location: vscode.ProgressLocation.Notification, title: 'WrapperGuard: Scanning workspace' },
          () => scanWorkspace(root)
        );
        provider.update(report, root);
        renderStatus(status, report);
      } catch (error) {
        status.text = '$(error) WrapperGuard: Failed';
        void vscode.window.showErrorMessage(`WrapperGuard scan failed: ${String(error)}`);
      }
    }),
    vscode.commands.registerCommand('wrapperGuard.openFinding', async (finding: Finding, root: vscode.Uri) => {
      const document = await vscode.workspace.openTextDocument(vscode.Uri.joinPath(root, finding.location.path));
      const editor = await vscode.window.showTextDocument(document);
      const line = Math.max(0, (finding.location.line ?? 1) - 1);
      editor.selection = new vscode.Selection(line, 0, line, 0);
      editor.revealRange(new vscode.Range(line, 0, line, 0), vscode.TextEditorRevealType.InCenter);
    })
  );
}

function renderStatus(status: vscode.StatusBarItem, report: ScanReport): void {
  const blockers = report.findings.filter(f => f.policyAction === 'BLOCK').length;
  status.text = blockers > 0
    ? `$(error) WrapperGuard: ${blockers} blocker${blockers === 1 ? '' : 's'}`
    : report.verdict === 'CLEAN'
      ? '$(pass) WrapperGuard: Clean'
      : `$(warning) WrapperGuard: ${report.findings.length} finding${report.findings.length === 1 ? '' : 's'}`;
  status.tooltip = `${report.policyName} · ${report.scannedFiles} files · ${report.durationMs} ms`;
}

export function deactivate(): void {}
