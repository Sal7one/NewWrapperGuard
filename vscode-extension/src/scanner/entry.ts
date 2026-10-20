import * as fs from 'node:fs';
import * as vscode from 'vscode';
import { BaselineEntry, ScanPolicy, ScanReport, ScanSettings } from '../models';
import { userSetting } from '../providers/userSettings';
import { scanProject } from './repositoryScanner';
import { DEFAULT_ENFORCE_POLICY, DEFAULT_SETTINGS } from './defaults';
import { validateMethodFlags } from '../methods/catalog';

/** Builds the active scan settings from workspace/user configuration, merged over defaults. */
export function buildSettings(): ScanSettings {
  const cfg = vscode.workspace.getConfiguration('wrapperGuard');
  const extraHosts = userSetting(cfg.inspect<string[]>('allowedHosts'), []);
  const extraCommands = userSetting(cfg.inspect<string[]>('blockedCommands'), []);
  return {
    ...DEFAULT_SETTINGS,
    methodFlags: validateMethodFlags(userSetting(cfg.inspect<Record<string,boolean>>('methodFlags'), {})),
    allowedHosts: Array.from(new Set([...DEFAULT_SETTINGS.allowedHosts, ...extraHosts])),
    blockedCommands: Array.from(new Set([...DEFAULT_SETTINGS.blockedCommands, ...extraCommands])),
    requireDistributionSha256: userSetting(cfg.inspect<boolean>('requireDistributionSha256'), false)
  };
}

/**
 * Native, in-process scan entry point for the extension. Loads the trusted baseline from
 * OUTSIDE the repository (extension global storage), then runs the TypeScript scanner in
 * ENFORCE mode — the same policy the IntelliJ run guard applies.
 */
export async function scanWorkspaceNative(
  root: vscode.Uri,
  baselinePath?: string,
  options?: { policy: ScanPolicy; settings: ScanSettings; baseline: BaselineEntry[] }
): Promise<{ report: ScanReport; rawJson: string; settings: ScanSettings }> {
  let baseline: BaselineEntry[] = [];
  if (baselinePath) {
    try {
      baseline = JSON.parse(fs.readFileSync(baselinePath, 'utf8')) as BaselineEntry[];
    } catch {
      baseline = [];
    }
  }
  const settings = options?.settings ?? buildSettings();
  const report = scanProject(root.fsPath, options?.policy ?? DEFAULT_ENFORCE_POLICY, settings, options?.baseline ?? baseline);
  return { report, rawJson: JSON.stringify(report, null, 2), settings };
}
