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
import { checkedProjectDirectory, checkedProjectFile, readProjectFile, resolveScriptPath } from './safePath';
import { findingFingerprint } from './fingerprints';
import { BUILTIN_METHOD_IDS, methodEnabled } from '../methods/catalog';
import { analyzeMavenPom, supportedMavenPom } from './mavenPomRules';

const EXCLUDED_DIRS = new Set(['.git', '.gradle', '.idea', 'build', 'out', '.intellijPlatform', '.kotlin', 'node_modules']);

function walkFiles(root: string, out: string[], depth: number, maxDepth: number,projectRoot:string,notices:ScanNotice[]): boolean {
  let limited=false;
  let entries: fs.Dirent[];
  try {
    if (!checkedProjectDirectory(projectRoot,root)) throw new Error('Missing project directory.');
    entries = fs.readdirSync(root, { withFileTypes: true });
  } catch {
    notices.push({code:RuleIds.SCAN_FILE_READ,message:'Could not inspect a project directory; the scan is incomplete.',location:{path:path.relative(projectRoot,root).replace(/\\/g,'/') || '.'}});
    return false;
  }
  if (depth === maxDepth) return entries.some(entry=>!EXCLUDED_DIRS.has(entry.name));
  for (const e of entries) {
    if (EXCLUDED_DIRS.has(e.name)) continue;
    const p = path.join(root, e.name);
    if (e.isSymbolicLink()) {unsafeFileNotice(projectRoot,p,notices);continue;}
    // Directory symlinks are never followed (matches Files.walk) — no symlink loops.
    if (e.isDirectory()) limited=walkFiles(p, out, depth + 1, maxDepth,projectRoot,notices) || limited;
    else if (e.isFile()) out.push(p);
  }
  return limited;
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

  const normalizedRoot = fs.realpathSync(projectRoot);
  const relativeRoot = path.basename(normalizedRoot) || normalizedRoot;

  for (const id of BUILTIN_METHOD_IDS) if (!methodEnabled(settings.methodFlags,id)) {
    notices.push({code:'WG-METHOD-DISABLED',message:'Security method disabled: ' + id + '. Coverage is reduced.'});
  }
  if (BUILTIN_METHOD_IDS.every(id=>!methodEnabled(settings.methodFlags,id))) notices.push({code:'WG-METHOD-NONE',message:'No built-in security methods are enabled.'});
  if (methodEnabled(settings.methodFlags,'builtin.wrapper-integrity')) findings.push(...analyzeProjectStructure(normalizedRoot, relativeRoot, settings.trustedWrapperChecksums,notices,settings.maxFileBytes));

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
  const complete = !notices.some((n) => [RuleIds.SCAN_FILE_SKIPPED,RuleIds.SCAN_FILE_READ,'WG-METHOD-NONE','WG-MAVEN-UNSUPPORTED','WG-SCAN-LIMIT'].includes(n.code));
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
  try {if (!checkedProjectFile(root,normalized)) {unsafeFileNotice(root,normalized,notices);return;}}
  catch {unsafeFileNotice(root,normalized,notices);return;}
  const graphEnabled = settings.enableRecursiveScanning && methodEnabled(settings.methodFlags,'builtin.script-graph');
  const methodsForFile=rulesFor(path.basename(file)).some(key=>methodEnabled(settings.methodFlags,methodForKey(key)));
  if (!methodsForFile && !graphEnabled) return;

  if (active.has(normalized)) {
    findings.push(circularFinding(relative, callChain ?? []));
    return;
  }
  if (completed.has(normalized)) return;
  if (scannedFiles.size >= settings.maxFilesPerScan) {fileLimitNotice(settings,notices);return;}

  active.add(normalized);
  scannedFiles.add(normalized);

  analyze(root,normalized, relative, settings, findings, callChain ?? [], notices);

  if (graphEnabled && depth < settings.maxScriptDepth) {
    let content: string | null = null;
    try {
      content = readProjectFile(root,normalized,settings.maxFileBytes).toString('utf8');
    } catch {
      content = null;
      unsafeFileNotice(root,normalized,notices);
    }
    if (content !== null) {
      const called = findCalledScripts(path.basename(normalized), content);
      for (const c of called) {
        const resolved = resolveScriptPath(root, normalized, c.path, settings.maxFileBytes);
        const targetRelative = resolved !== null ? relativize(root, path.resolve(resolved)) : null;
        if (resolved === null || targetRelative === null) {
          findings.push(unresolvedFinding(relative, c));
          notices.push({code:RuleIds.SCAN_FILE_SKIPPED,message:'A referenced script could not be inspected safely.',location:{path:relative,line:c.lineNumber}});
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
  } else if (graphEnabled && depth >= settings.maxScriptDepth) {
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
  root:string,
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
    content = readTextSmart(root,file,settings.maxFileBytes);
  } catch (e) {
    notices.push({
      code: RuleIds.SCAN_FILE_READ,
      message: 'Could not read a project input safely.',
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
    const method = methodForKey(key);
    if (!methodEnabled(settings.methodFlags,method)) continue;
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
    else if (key === 'maven') {
      if (!supportedMavenPom(source)) notices.push({code:'WG-MAVEN-UNSUPPORTED',message:'Maven POM could not be analyzed safely; dependency resolution and external entities are never executed.',location:{path:relative}});
      else produced=analyzeMavenPom(source,settings);
    }
    else if (key === 'generic') produced = analyzeBuildScript(source, 'Gradle', false, settings);
    for (const f of produced) {
      findings.push(callChain && callChain.length > 0 ? { ...f, callChain } : f);
    }
  }
}

function findScanRoots(root: string, settings: ScanSettings, notices: ScanNotice[]): string[] {
  if (!Number.isInteger(settings.maxFilesPerScan) || settings.maxFilesPerScan < 1 || !Number.isInteger(settings.maxDirectoryDepth) || settings.maxDirectoryDepth < 0) throw new Error('Invalid scan limits.');
  const canonical = [
    'gradlew',
    'gradlew.bat',
    'gradle/wrapper/gradle-wrapper.properties',
    'settings.gradle',
    'settings.gradle.kts',
    'build.gradle',
    'build.gradle.kts',
    'gradle/libs.versions.toml',
    'init.gradle',
    'init.gradle.kts',
    'pom.xml'
  ]
    .map((s) => path.join(root, s))
    .filter((p) => {try{return !!checkedProjectFile(root,p);}catch{unsafeFileNotice(root,p,notices);return false;}})
    .filter(p=>path.relative(root,p).split(path.sep).length <= settings.maxDirectoryDepth);
  const seeds=canonical.slice(0,settings.maxFilesPerScan);

  const all: string[] = [];
  if (walkFiles(root, all, 0, settings.maxDirectoryDepth,root,notices)) notices.push({code:'WG-SCAN-LIMIT',message:'Directory depth limit reached ('+settings.maxDirectoryDepth+'); the scan is incomplete.'});
  const extra = all
    .filter((p) => !seeds.includes(p) && isScannable(path.basename(p)) && rulesFor(path.basename(p)).length > 0)
    .sort((a, b) => (a < b ? -1 : a > b ? 1 : 0));
  const limited: string[] = [];
  let capped = canonical.length > settings.maxFilesPerScan;
  for (const p of extra) {
    if (seeds.length + limited.length >= settings.maxFilesPerScan) {
      capped = true;
      break;
    }
    limited.push(p);
  }
  if (capped) {
    fileLimitNotice(settings,notices);
  }
  return Array.from(new Set([...seeds, ...limited]));
}
function methodForKey(key:string):string {
  return ['unix','windows','powershell'].includes(key) ? 'builtin.script-rules' : key === 'wrapper-props' ? 'builtin.wrapper-integrity' : key === 'catalog' ? 'builtin.catalog-rules' : key === 'maven' ? 'builtin.maven-rules' : 'builtin.build-rules';
}
function unsafeFileNotice(root:string,file:string,notices:ScanNotice[]):void {
  const relative=path.relative(root,file).replace(/\\/g,'/');
  if (!notices.some(notice=>notice.code === RuleIds.SCAN_FILE_SKIPPED && notice.location?.path === relative && notice.message === 'File is not a readable regular input inside the project.')) notices.push({code:RuleIds.SCAN_FILE_SKIPPED,message:'File is not a readable regular input inside the project.',location:{path:relative}});
}
function fileLimitNotice(settings:ScanSettings,notices:ScanNotice[]):void {
  const message='File count limit reached ('+settings.maxFilesPerScan+'); the scan is incomplete.';
  if (!notices.some(notice=>notice.code === 'WG-SCAN-LIMIT' && notice.message === message)) notices.push({code:'WG-SCAN-LIMIT',message});
}

/** Reads text honoring UTF-8 BOM and UTF-16 LE/BE encodings (hostile-input hardening). */
function readTextSmart(root:string,file: string,maximum:number): string {
  const bytes = readProjectFile(root,file,maximum);
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
