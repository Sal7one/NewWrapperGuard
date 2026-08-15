import * as fs from 'node:fs';
import * as vscode from 'vscode';
import { BaselineEntry, ScanReport, ScanSettings } from '../models';
import { scanProject } from './repositoryScanner';
import { DEFAULT_ENFORCE_POLICY, DEFAULT_SETTINGS } from './defaults';

/** Builds the active scan settings from workspace/user configuration, merged over defaults. */
export function buildSettings(): ScanSettings {
  const cfg = vscode.workspace.getConfiguration('wrapperGuard');
  const extraHosts = cfg.get<string[]>('allowedHosts') ?? [];
  const extraCommands = cfg.get<string[]>('blockedCommands') ?? [];
  return {
    ...DEFAULT_SETTINGS,
    allowedHosts: Array.from(new Set([...DEFAULT_SETTINGS.allowedHosts, ...extraHosts])),
    blockedCommands: Array.from(new Set([...DEFAULT_SETTINGS.blockedCommands, ...extraCommands])),
    requireDistributionSha256: cfg.get<boolean>('requireDistributionSha256') ?? false
  };
}

/**
 * Native, in-process scan entry point for the extension. Loads the trusted baseline from
 * OUTSIDE the repository (extension global storage), then runs the TypeScript scanner in
 * ENFORCE mode — the same policy the IntelliJ run guard applies.
 */
export async function scanWorkspaceNative(
  root: vscode.Uri,
  baselinePath?: string
): Promise<{ report: ScanReport; rawJson: string; settings: ScanSettings }> {
  let baseline: BaselineEntry[] = [];
  if (baselinePath) {
    try {
      baseline = JSON.parse(fs.readFileSync(baselinePath, 'utf8')) as BaselineEntry[];
    } catch {
      baseline = [];
    }
  }
  const settings = buildSettings();
  const report = scanProject(root.fsPath, DEFAULT_ENFORCE_POLICY, settings, baseline);
  return { report, rawJson: JSON.stringify(report, null, 2), settings };
}
