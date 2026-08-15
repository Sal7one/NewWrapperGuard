import * as fs from 'node:fs';
import * as path from 'node:path';
import { BaselineEntry, Finding, PolicyAction, ScanNotice, ScanPolicy, ScanReport, ScanSettings, ScriptEdge } from '../models';
import { RuleIds } from './ruleIds';
import { actionFor } from './policy';
import { rulesFor, isScannable } from './fileTypeRouter';
import { analyzeScript, ScriptFlavor } from './scriptRules';
import { analyzeBuildScript } from './buildScriptRules';
import { analyzeWrapperProperties } from './wrapperPropertiesRules';
import { analyzeVersionCatalog } from './versionCatalogRules';
import { analyzeProjectStructure } from './projectStructure';
import { findCalledScripts } from './calledScripts';
import { resolveScriptPath } from './safePath';
import { findingFingerprint } from './fingerprints';

const EXCLUDED_DIRS = new Set(['.git', '.gradle', '.idea', 'build', 'out', '.intellijPlatform', '.kotlin', 'node_modules']);

function walkFiles(root: string, out: string[], depth: number, maxDepth: number): void {
  if (depth > maxDepth) return;
  let entries: fs.Dirent[];
  try {
    entries = fs.readdirSync(root, { withFileTypes: true });
  } catch {
    return;
  }
  for (const e of entries) {
    if (EXCLUDED_DIRS.has(e.name)) continue;
    const p = path.join(root, e.name);
    // Directory symlinks are never followed (matches Files.walk) — no symlink loops.
    if (e.isDirectory()) walkFiles(p, out, depth + 1, maxDepth);
    else if (e.isFile()) out.push(p);
  }
}

function relativize(root: string, file: string): string {
  const normalized = path.resolve(file);
  const normalizedRoot = path.resolve(root);
  if (normalized === normalizedRoot) return normalized;
  if (normalized.startsWith(normalizedRoot + path.sep)) {
    return normalized.slice(normalizedRoot.length + 1).replace(/\\/g, '/');
  }
  return normalized;
}

/** Faithful port of RepositoryScanner: seed roots, DFS with cycle detection, policy, baseline. */
export function scanProject(
  projectRoot: string,
  policy: ScanPolicy,
  settings: ScanSettings,
  baseline: BaselineEntry[]
): ScanReport {
  const started = Date.now();
  const findings: Finding[] = [];
  const edges: ScriptEdge[] = [];
  const notices: ScanNotice[] = [];
  const scannedFiles = new Set<string>();

  if (!fs.existsSync(projectRoot) || !fs.statSync(projectRoot).isDirectory()) {
    return report(
      projectRoot,
      'FAILED',
      policy,
      [],
      [],
      0,
      0,
      false,
      [{ code: RuleIds.SCAN_ROOT, message: 'Project path does not exist or is not a directory.' }]
    );
  }

  const normalizedRoot = path.resolve(projectRoot);
  const relativeRoot = path.basename(normalizedRoot) || normalizedRoot;

  findings.push(...analyzeProjectStructure(normalizedRoot, relativeRoot, settings.trustedWrapperChecksums));

  const completed = new Set<string>();
  const active = new Set<string>();

  const roots = findScanRoots(normalizedRoot, settings, notices);
  if (roots.length === 0) {
    notices.push({ code: RuleIds.SCAN_ROOT, message: 'No Gradle wrapper or build scripts found to scan.' });
  }

  for (const root of roots) {
    scanFile(normalizedRoot, root, settings, 0, [], findings, edges, notices, scannedFiles, completed, active);
  }

  const withPolicy = findings.map((f) => ({ ...f, policyAction: actionFor(policy, f) }));
  const baselineSet = new Set(baseline.map((b) => b.fingerprint));
  const finalFindings =
    baselineSet.size === 0
      ? withPolicy
      : withPolicy.map((f) => (baselineSet.has(f.fingerprint) ? { ...f, status: 'SUPPRESSED' as const } : f));

  const durationMs = Date.now() - started;
  const complete = !notices.some((n) => n.code === RuleIds.SCAN_FILE_SKIPPED || n.code === RuleIds.SCAN_FILE_READ);
  let verdict: ScanReport['verdict'];
  if (!complete) verdict = 'INCOMPLETE';
  else if (finalFindings.some((f) => f.policyAction === 'BLOCK' && f.status === 'ACTIVE')) verdict = 'EXECUTION_BLOCKED';
  else if (finalFindings.some((f) => f.policyAction === 'REVIEW' && f.status === 'ACTIVE')) verdict = 'REVIEW_REQUIRED';
  else verdict = 'CLEAN';

  return report(projectRoot, verdict, policy, finalFindings, edges, scannedFiles.size, durationMs, complete, notices);
}

function scanFile(
  root: string,
  file: string,
  settings: ScanSettings,
  depth: number,
  callChain: Finding['callChain'],
  findings: Finding[],
  edges: ScriptEdge[],
  notices: ScanNotice[],
  scannedFiles: Set<string>,
  completed: Set<string>,
  active: Set<string>
): void {
  const normalized = path.resolve(file);
  const relative = relativize(root, normalized);

  if (active.has(normalized)) {
    findings.push(circularFinding(relative, callChain ?? []));
    return;
  }
  if (completed.has(normalized)) return;

  active.add(normalized);
  scannedFiles.add(normalized);

  analyze(normalized, relative, settings, findings, callChain ?? [], notices);

  if (settings.enableRecursiveScanning && depth < settings.maxScriptDepth) {
    let content: string | null = null;
    try {
      content = fs.readFileSync(normalized, 'utf8');
    } catch {
      content = null;
    }
    if (content !== null) {
      const called = findCalledScripts(path.basename(normalized), content);
      for (const c of called) {
        const resolved = resolveScriptPath(root, normalized, c.path, settings.maxFileBytes);
        const targetRelative = resolved !== null ? relativize(root, path.resolve(resolved)) : null;
        if (resolved === null || targetRelative === null) {
          findings.push(unresolvedFinding(relative, c));
          continue;
        }
        edges.push({
          from: { path: relative, line: c.lineNumber },
          to: { path: targetRelative },
          viaCommand: c.rawLine
        });
        const nextChain = [
          ...(callChain ?? []),
          { source: { path: relative, line: c.lineNumber }, viaCommand: c.rawLine }
        ];
        scanFile(root, resolved, settings, depth + 1, nextChain, findings, edges, notices, scannedFiles, completed, active);
      }
    }
  } else if (settings.enableRecursiveScanning && depth >= settings.maxScriptDepth) {
    findings.push({
      fingerprint: findingFingerprint(RuleIds.SCAN_DEPTH_LIMIT, relative, String(depth)),
      ruleId: RuleIds.SCAN_DEPTH_LIMIT,
      title: 'Maximum recursive script depth reached',
      message: 'Recursive scan stopped at depth ' + depth + '.',
      severity: 'INFO',
      confidence: 'HIGH',
      category: 'AUDIT',
      kind: 'SCANNER_NOTICE',
      location: { path: relative },
      evidence: { text: 'depth=' + depth, redacted: false },
      remediation: undefined,
      reachable: true,
      callChain: callChain ?? [],
      policyAction: 'REVIEW',
      status: 'ACTIVE'
    });
  }

  active.delete(normalized);
  completed.add(normalized);
}

function analyze(
  file: string,
  relative: string,
  settings: ScanSettings,
  findings: Finding[],
  callChain: Finding['callChain'],
  notices: ScanNotice[]
): void {
  const keys = rulesFor(path.basename(file));
  if (keys.length === 0) return;
  let size = 0;
  try {
    size = fs.statSync(file).size;
  } catch {
    size = 0;
  }
  if (size > settings.maxFileBytes) {
    notices.push({
      code: RuleIds.SCAN_FILE_SKIPPED,
      message: 'File exceeds the configured size limit.',
      location: { path: relative }
    });
    return;
  }
  let content: string;
  try {
    content = readTextSmart(file);
  } catch (e) {
    notices.push({
      code: RuleIds.SCAN_FILE_READ,
      message: 'Could not read file: ' + (e instanceof Error ? e.message : String(e)),
      location: { path: relative }
    });
    return;
  }
  // Hardening: binary/null-byte content is not analyzed as text.
  if (content.indexOf('\u0000') >= 0) {
    notices.push({
      code: RuleIds.SCAN_FILE_READ,
      message: 'File contains null bytes (binary content).',
      location: { path: relative }
    });
    return;
  }
  // Hardening: reject single oversized lines instead of feeding them to regexes (ReDoS budget).
  let oversized = false;
  for (const line of content.split('\n')) {
    if (line.length > settings.maxLineLength) {
      oversized = true;
      break;
    }
  }
  if (oversized) {
    notices.push({
      code: RuleIds.SCAN_FILE_SKIPPED,
      message: 'File contains a line longer than ' + settings.maxLineLength + ' characters.',
      location: { path: relative }
    });
    return;
  }
  const source = { relativePath: relative, content };

  for (const key of keys) {
    let produced: Finding[] = [];
    if (key === 'unix') produced = analyzeScript(source, 'UNIX', settings);
    else if (key === 'windows') produced = analyzeScript(source, 'WINDOWS', settings);
    else if (key === 'powershell') produced = analyzeScript(source, 'POWERSHELL', settings);
    else if (key === 'wrapper-props') produced = analyzeWrapperProperties(source, settings);
    else if (key === 'settings') produced = analyzeBuildScript(source, 'settings', false, settings);
    else if (key === 'init') produced = analyzeBuildScript(source, 'init', true, settings);
    else if (key === 'groovy') produced = analyzeBuildScript(source, 'Groovy', false, settings);
    else if (key === 'kotlin') produced = analyzeBuildScript(source, 'Kotlin', false, settings);
    else if (key === 'catalog') produced = analyzeVersionCatalog(source);
    else if (key === 'generic') produced = analyzeBuildScript(source, 'Gradle', false, settings);
    for (const f of produced) {
      findings.push(callChain && callChain.length > 0 ? { ...f, callChain } : f);
    }
  }
}

function findScanRoots(root: string, settings: ScanSettings, notices: ScanNotice[]): string[] {
  const seeds = [
    'gradlew',
    'gradlew.bat',
    'gradle/wrapper/gradle-wrapper.properties',
    'settings.gradle',
    'settings.gradle.kts',
    'build.gradle',
    'build.gradle.kts',
    'gradle/libs.versions.toml',
    'init.gradle',
    'init.gradle.kts'
  ]
    .map((s) => path.join(root, s))
    .filter((p) => fs.existsSync(p) && fs.statSync(p).isFile());

  const all: string[] = [];
  walkFiles(root, all, 0, settings.maxDirectoryDepth);
  const extra = all
    .filter((p) => !seeds.includes(p) && isScannable(path.basename(p)) && rulesFor(path.basename(p)).length > 0)
    .sort((a, b) => (a < b ? -1 : a > b ? 1 : 0));
  const limited: string[] = [];
  let capped = false;
  for (const p of extra) {
    if (seeds.length + limited.length >= settings.maxFilesPerScan) {
      capped = true;
      break;
    }
    limited.push(p);
  }
  if (capped) {
    notices.push({
      code: RuleIds.SCAN_ROOT,
      message: 'File count limit reached (' + settings.maxFilesPerScan + '); the scan may be incomplete.'
    });
  }
  return Array.from(new Set([...seeds, ...limited]));
}

/** Reads text honoring UTF-8 BOM and UTF-16 LE/BE encodings (hostile-input hardening). */
function readTextSmart(file: string): string {
  const bytes = fs.readFileSync(file);
  if (bytes.length >= 2 && bytes[0] === 0xfe && bytes[1] === 0xff) {
    return Buffer.from(bytes.subarray(2)).swap16().toString('utf16le');
  }
  if (bytes.length >= 2 && bytes[0] === 0xff && bytes[1] === 0xfe) {
    return bytes.subarray(2).toString('utf16le');
  }
  if (bytes.length >= 3 && bytes[0] === 0xef && bytes[1] === 0xbb && bytes[2] === 0xbf) {
    return bytes.subarray(3).toString('utf8');
  }
  return bytes.toString('utf8');
}

function circularFinding(relative: string, callChain: Finding['callChain']): Finding {
  return {
    fingerprint: findingFingerprint(
      RuleIds.SCAN_CIRCULAR,
      relative,
      (callChain ?? []).map((c) => c.source.path + (c.source.line != null ? ':' + c.source.line : '')).join('→')
    ),
    ruleId: RuleIds.SCAN_CIRCULAR,
    title: 'Circular script dependency detected',
    message: 'A script dependency cycle was detected during recursive scanning.',
    severity: 'ERROR',
    confidence: 'HIGH',
    category: 'EXECUTION',
    kind: 'ACTIONABLE',
    location: { path: relative },
    evidence: {
      text: (callChain ?? []).map((c) => c.source.path + (c.source.line != null ? ':' + c.source.line : '') + ' → ' + c.viaCommand).join(String.fromCharCode(10)),
      redacted: false
    },
    remediation: 'Break the cycle by removing one of the script references.',
    reachable: true,
    callChain: callChain ?? [],
    policyAction: 'REVIEW',
    status: 'ACTIVE'
  };
}

function unresolvedFinding(relative: string, called: { rawLine: string; path: string; lineNumber: number }): Finding {
  return {
    fingerprint: findingFingerprint(RuleIds.SCAN_UNRESOLVED, relative, called.path),
    ruleId: RuleIds.SCAN_UNRESOLVED,
    title: 'Referenced script could not be resolved safely',
    message: 'A referenced script could not be resolved within the project or failed safety checks.',
    severity: 'WARN',
    confidence: 'MEDIUM',
    category: 'FILE_SYSTEM',
    kind: 'ACTIONABLE',
    location: { path: relative, line: called.lineNumber },
    evidence: { text: called.rawLine, redacted: false },
    remediation: 'Check that the referenced path exists and stays inside the project.',
    reachable: true,
    callChain: [],
    policyAction: 'REVIEW',
    status: 'ACTIVE'
  };
}

function report(
  projectRoot: string,
  verdict: ScanReport['verdict'],
  policy: ScanPolicy,
  findings: Finding[],
  edges: ScriptEdge[],
  scannedFiles: number,
  durationMs: number,
  complete: boolean,
  notices: ScanNotice[]
): ScanReport {
  const order: Record<string, number> = { INFO: 0, WARN: 1, ERROR: 2 };
  const sorted = [...findings].sort(
    (a, b) =>
      (order[b.severity] ?? 0) - (order[a.severity] ?? 0) ||
      a.location.path.localeCompare(b.location.path) ||
      (a.location.line ?? 0) - (b.location.line ?? 0)
  );
  return {
    schemaVersion: 1,
    projectPath: path.resolve(projectRoot),
    verdict,
    policyName: policy.name,
    findings: sorted,
    edges,
    scannedFiles,
    durationMs,
    complete,
    stale: false,
    notices,
    generatedAtEpochMs: Date.now()
  };
}
