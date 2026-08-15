import * as fs from 'node:fs';
import * as vscode from 'vscode';
import { BaselineEntry, ScanReport } from '../models';
import { scanProject } from './repositoryScanner';
import { DEFAULT_ENFORCE_POLICY, DEFAULT_SETTINGS } from './defaults';

/**
 * Native, in-process scan entry point for the extension. Loads the trusted baseline from
 * OUTSIDE the repository (extension global storage), then runs the TypeScript scanner in
 * ENFORCE mode — the same policy the IntelliJ run guard applies.
 */
export async function scanWorkspaceNative(
  root: vscode.Uri,
  baselinePath?: string
): Promise<{ report: ScanReport; rawJson: string }> {
  let baseline: BaselineEntry[] = [];
  if (baselinePath) {
    try {
      baseline = JSON.parse(fs.readFileSync(baselinePath, 'utf8')) as BaselineEntry[];
    } catch {
      baseline = [];
    }
  }
  const report = scanProject(root.fsPath, DEFAULT_ENFORCE_POLICY, DEFAULT_SETTINGS, baseline);
  return { report, rawJson: JSON.stringify(report, null, 2) };
}
