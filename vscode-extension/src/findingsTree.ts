import * as vscode from 'vscode';
import { Finding, ScanReport } from './models';

export class FindingItem extends vscode.TreeItem {
  constructor(public readonly finding: Finding, root: vscode.Uri) {
    super(finding.title, vscode.TreeItemCollapsibleState.None);
    this.description = `${finding.location.path}:${finding.location.line ?? 1}`;
    this.tooltip = `${finding.ruleId}\n${finding.message}`;
    this.iconPath = new vscode.ThemeIcon(
      finding.policyAction === 'BLOCK' ? 'error' : finding.severity === 'WARN' ? 'warning' : 'info'
    );
    this.command = {
      command: 'wrapperGuard.openFinding',
      title: 'Open Finding',
      arguments: [finding, root]
    };
    this.contextValue = 'wrapperGuardFinding';
  }
}

export class FindingsTreeProvider implements vscode.TreeDataProvider<FindingItem> {
  private readonly changed = new vscode.EventEmitter<FindingItem | undefined>();
  readonly onDidChangeTreeData = this.changed.event;
  private report?: ScanReport;
  private root?: vscode.Uri;

  update(report: ScanReport, root: vscode.Uri): void {
    this.report = report;
    this.root = root;
    this.changed.fire(undefined);
  }

  getTreeItem(element: FindingItem): vscode.TreeItem { return element; }

  getChildren(): FindingItem[] {
    if (!this.report || !this.root) return [];
    return this.report.findings.map(finding => new FindingItem(finding, this.root!));
  }
}
