import * as vscode from 'vscode';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { ScanReport } from './models';

const execFileAsync = promisify(execFile);

export async function scanWorkspace(root: vscode.Uri): Promise<ScanReport> {
  const cliPath = vscode.workspace.getConfiguration('wrapperGuard').get<string>('cliPath', 'wrapperguard');
  const { stdout } = await execFileAsync(cliPath, [root.fsPath, '--json'], {
    cwd: root.fsPath,
    maxBuffer: 10 * 1024 * 1024
  });
  return JSON.parse(stdout) as ScanReport;
}
