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
export async function scanWorkspace(root: vscode.Uri, baselinePath?: string): Promise<{ report: ScanReport; rawJson: string }> {
  const cliPath = vscode.workspace.getConfiguration('wrapperGuard').get<string>('cliPath', 'wrapperguard');
  const args = [root.fsPath, '--json', '--enforce'];
  if (baselinePath) {
    args.push('--baseline', baselinePath);
  }
  try {
    const { stdout } = await execFileAsync(cliPath, args, {
      cwd: root.fsPath,
      maxBuffer: 10 * 1024 * 1024
    });
    return { report: JSON.parse(stdout) as ScanReport, rawJson: stdout };
  } catch (error) {
    // Exit code 2 is a successful scan: BLOCK-policy findings were found. The report is
    // still printed to stdout; only treat other failures (spawn errors, code 1) as errors.
    const err = error as { code?: number | string; stdout?: string };
    if ((err.code === 2 || err.code === '2') && err.stdout) {
      return { report: JSON.parse(err.stdout) as ScanReport, rawJson: err.stdout };
    }
    throw error;
  }
}
