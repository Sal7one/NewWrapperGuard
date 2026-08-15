import * as vscode from 'vscode';
import { Finding, ScanState } from './models';

function iconFor(f: Finding): vscode.ThemeIcon {
  if (f.policyAction === 'BLOCK' || f.severity === 'ERROR') {
    return new vscode.ThemeIcon('error', new vscode.ThemeColor('testing.iconFailed'));
  }
  if (f.severity === 'WARN') {
    return new vscode.ThemeIcon('warning', new vscode.ThemeColor('editorWarning.foreground'));
  }
  return new vscode.ThemeIcon('info', new vscode.ThemeColor('textLink.foreground'));
}

export class FindingItem extends vscode.TreeItem {
  constructor(public readonly finding: Finding) {
    super(finding.title, vscode.TreeItemCollapsibleState.None);
    this.description = `${finding.ruleId} · ${finding.location.path}:${finding.location.line ?? 1}`;
    this.tooltip = `${finding.ruleId} — ${finding.category}
${finding.message}
Policy: ${finding.policyAction} · Confidence: ${finding.confidence}`;
    this.iconPath = iconFor(finding);
    this.contextValue = 'wrapperGuardFinding';
    this.command = {
      command: 'wrapperGuard.showFindingDetails',
      title: 'Show Finding Details',
      arguments: [finding]
    };
  }
}

class GroupItem extends vscode.TreeItem {
  children: FindingItem[] = [];
  constructor(label: string, icon: vscode.ThemeIcon, expanded: boolean) {
    super(label, vscode.TreeItemCollapsibleState.Collapsed);
    this.iconPath = icon;
    if (expanded) {
      this.collapsibleState = vscode.TreeItemCollapsibleState.Expanded;
    }
  }
}

function noteItem(label: string, icon: vscode.ThemeIcon, command?: string): vscode.TreeItem {
  const item = new vscode.TreeItem(label, vscode.TreeItemCollapsibleState.None);
  item.iconPath = icon;
  if (command) {
    item.command = { command, title: label };
  }
  return item;
}

export class FindingsTreeProvider implements vscode.TreeDataProvider<vscode.TreeItem> {
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

  getChildren(element?: vscode.TreeItem): vscode.TreeItem[] {
    if (element instanceof GroupItem) {
      return element.children;
    }
    if (element) {
      return [];
    }

    switch (this.state.kind) {
      case 'not-scanned':
        return [noteItem('No scan yet — wrappers, build files, and bootstrap scripts are unprotected until scanned.', new vscode.ThemeIcon('shield'), 'wrapperGuard.scanWorkspace')];
      case 'scanning':
        return [noteItem('Scanning project…', new vscode.ThemeIcon('sync~spin'))];
      case 'failed':
        return [noteItem('Scan failed — click to retry.', new vscode.ThemeIcon('error', new vscode.ThemeColor('testing.iconFailed')), 'wrapperGuard.scanWorkspace')];
      case 'scanned': {
        const r = this.state.report;
        const roots: vscode.TreeItem[] = [];

        if (r.stale) {
          roots.push(noteItem('Report is stale — a watched file changed after the scan.', new vscode.ThemeIcon('history', new vscode.ThemeColor('editorWarning.foreground')), 'wrapperGuard.scanWorkspace'));
        }
        if (!r.complete) {
          roots.push(noteItem('Scan incomplete — some files were skipped or unreadable.', new vscode.ThemeIcon('warning', new vscode.ThemeColor('editorWarning.foreground')), 'wrapperGuard.scanWorkspace'));
        }

        const groups: Array<GroupItem> = [
          new GroupItem('Execution Blockers', new vscode.ThemeIcon('error', new vscode.ThemeColor('testing.iconFailed')), true),
          new GroupItem('Errors', new vscode.ThemeIcon('error', new vscode.ThemeColor('testing.iconFailed')), false),
          new GroupItem('Warnings', new vscode.ThemeIcon('warning', new vscode.ThemeColor('editorWarning.foreground')), false),
          new GroupItem('Info', new vscode.ThemeIcon('info', new vscode.ThemeColor('textLink.foreground')), false)
        ];
        for (const finding of r.findings) {
          if (finding.policyAction === 'BLOCK') {
            groups[0].children.push(new FindingItem(finding));
          } else if (finding.severity === 'ERROR') {
            groups[1].children.push(new FindingItem(finding));
          } else if (finding.severity === 'WARN') {
            groups[2].children.push(new FindingItem(finding));
          } else {
            groups[3].children.push(new FindingItem(finding));
          }
        }
        for (const group of groups) {
          if (group.children.length > 0) {
            group.description = `(${group.children.length})`;
            roots.push(group);
          }
        }

        if (r.findings.length === 0 && r.complete && !r.stale) {
          roots.push(noteItem(`Clean — no findings in ${r.scannedFiles} scanned files.`, new vscode.ThemeIcon('pass', new vscode.ThemeColor('testing.iconPassed'))));
        }
        return roots;
      }
    }
  }
}
