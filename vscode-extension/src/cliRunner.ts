import * as vscode from 'vscode';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { ScanReport } from './models';

const execFileAsync = promisify(execFile);

/**
 * Runs the CLI in ENFORCE mode so the report mirrors the IDE guard policy
 * (BLOCK verdicts for actionable ERROR findings). Returns the parsed report
 * and the raw JSON (kept for export).
 */
export async function scanWorkspace(root: vscode.Uri): Promise<{ report: ScanReport; rawJson: string }> {
  const cliPath = vscode.workspace.getConfiguration('wrapperGuard').get<string>('cliPath', 'wrapperguard');
  const { stdout } = await execFileAsync(cliPath, [root.fsPath, '--json', '--enforce'], {
    cwd: root.fsPath,
    maxBuffer: 10 * 1024 * 1024
  });
  return { report: JSON.parse(stdout) as ScanReport, rawJson: stdout };
}
