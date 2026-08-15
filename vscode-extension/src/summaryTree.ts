import * as vscode from 'vscode';
import { ScanState } from './models';

const VERDICT_META: Record<string, { icon: string; color?: string; label: string }> = {
  NOT_SCANNED: { icon: 'circle-outline', label: 'Not scanned' },
  CLEAN: { icon: 'pass', color: 'testing.iconPassed', label: 'Clean' },
  REVIEW_REQUIRED: { icon: 'warning', color: 'editorWarning.foreground', label: 'Review Required' },
  EXECUTION_BLOCKED: { icon: 'error', color: 'testing.iconFailed', label: 'Execution Blocked' },
  INCOMPLETE: { icon: 'warning', color: 'editorWarning.foreground', label: 'Scan Incomplete' },
  STALE: { icon: 'history', color: 'editorWarning.foreground', label: 'Scan Stale' },
  FAILED: { icon: 'error', color: 'testing.iconFailed', label: 'Scan Failed' }
};

function themed(icon: string, color?: string): vscode.ThemeIcon {
  return new vscode.ThemeIcon(icon, color ? new vscode.ThemeColor(color) : undefined);
}

function leaf(label: string, icon: vscode.ThemeIcon, description?: string, tooltip?: string, command?: string): vscode.TreeItem {
  const item = new vscode.TreeItem(label, vscode.TreeItemCollapsibleState.None);
  item.iconPath = icon;
  item.description = description;
  item.tooltip = tooltip;
  if (command) {
    item.command = { command, title: label };
  }
  return item;
}

export class SummaryProvider implements vscode.TreeDataProvider<vscode.TreeItem> {
  private state: ScanState = { kind: 'not-scanned' };
  private readonly changed = new vscode.EventEmitter<void>();
  readonly onDidChangeTreeData = this.changed.event;

  setState(s: ScanState): void {
    this.state = s;
    this.changed.fire();
  }

  getTreeItem(element: vscode.TreeItem): vscode.TreeItem {
    return element;
  }

  getChildren(): vscode.TreeItem[] {
    switch (this.state.kind) {
      case 'not-scanned':
        return [leaf('Not scanned yet', themed('shield'), 'Run a scan', 'Run WrapperGuard on this workspace.', 'wrapperGuard.scanWorkspace')];
      case 'scanning':
        return [leaf('Scanning project…', themed('sync~spin'), undefined, 'Reading wrappers, build files, and bootstrap scripts.')];
      case 'failed':
        return [leaf('Scan failed', themed('error', 'testing.iconFailed'), undefined, this.state.error, 'wrapperGuard.scanWorkspace')];
      case 'scanned': {
        const r = this.state.report;
        const blockers = r.findings.filter(f => f.policyAction === 'BLOCK').length;
        const errors = r.findings.filter(f => f.severity === 'ERROR').length;
        const warnings = r.findings.filter(f => f.severity === 'WARN').length;
        const verdict = VERDICT_META[r.verdict] ?? { icon: 'circle-outline', label: r.verdict };
        const items: vscode.TreeItem[] = [
          leaf(
            `Verdict: ${verdict.label}`,
            themed(verdict.icon, verdict.color),
            r.policyName,
            `${r.projectPath}`
          )
        ];
        items.push(
          leaf(
            'Blockers',
            themed(blockers > 0 ? 'error' : 'check', blockers > 0 ? 'testing.iconFailed' : 'testing.iconPassed'),
            String(blockers),
            'Findings the policy marks BLOCK.'
          ),
          leaf(
            'Errors',
            themed(errors > 0 ? 'error' : 'check', errors > 0 ? 'testing.iconFailed' : 'testing.iconPassed'),
            String(errors),
            'ERROR-severity findings.'
          ),
          leaf(
            'Warnings',
            themed(warnings > 0 ? 'warning' : 'check', warnings > 0 ? 'editorWarning.foreground' : 'testing.iconPassed'),
            String(warnings),
            'WARN-severity findings.'
          ),
          leaf('Files scanned', themed('file'), String(r.scannedFiles), undefined)
        );
        if (!r.complete) {
          items.push(leaf('Scan incomplete', themed('warning', 'editorWarning.foreground'), undefined, r.notices.map((n) => n.message).join('; ') || 'Some files were skipped or unreadable.'));
        }
        if (r.stale) {
          items.push(leaf('Report is stale', themed('history', 'editorWarning.foreground'), 'file changed after scan', 'Rescan to refresh.', 'wrapperGuard.scanWorkspace'));
        }
        if (r.generatedAtEpochMs) {
          items.push(leaf('Last scan', themed('clock'), new Date(r.generatedAtEpochMs).toLocaleTimeString(), `${r.durationMs} ms`));
        }
        return items;
      }
    }
  }
}
