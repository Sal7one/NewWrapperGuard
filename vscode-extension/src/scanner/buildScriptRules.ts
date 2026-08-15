import { Finding, ScanSettings } from '../models';
import { RuleIds } from './ruleIds';
import { findingFingerprint } from './fingerprints';
import { maskSecretValues } from './redaction';
import { isAllowed } from './urlAllowlist';

const REPOSITORY_PATTERNS: RegExp[] = [
  /maven\s*\{\s*url\s+['"](https?:\/\/[^'"]+)['"]/sg,
  /maven\s*\{\s*url\s*=\s*['"](https?:\/\/[^'"]+)['"]/sg,
  /maven\s*\(\s*['"](https?:\/\/[^'"]+)['"]/g,
  /maven\s*\(\s*url\s*=\s*['"](https?:\/\/[^'"]+)['"]/g,
  /url\s*=\s*uri\s*\(\s*['"](https?:\/\/[^'"]+)['"]/gi,
  /url\s*=\s*URI\s*\(\s*['"](https?:\/\/[^'"]+)['"]/g,
  /url\s*=\s*URL\s*\(\s*['"](https?:\/\/[^'"]+)['"]/g
];

const REMOTE_SCRIPT_PATTERNS: RegExp[] = [
  /apply\s+from:\s*['"](https?:\/\/[^'"]+)['"]/g,
  /apply\([^)]*from\s*=\s*['"](https?:\/\/[^'"]+)['"][^)]*\)/g,
  /apply\([^)]*from:\s*['"](https?:\/\/[^'"]+)['"][^)]*\)/g
];

const PROCESS_EXECUTION_PATTERNS: RegExp[] = [
  /exec\s*\{[^}]*\b(commandLine|executable)\b[^}]*\}/sg,
  /tasks?\.(register|create)\s*\([^)]*Exec::class/g,
  /tasks?\.(register|create)\s*<\s*Exec\s*>/g,
  /\b(commandLine|executable)\s*\(/g,
  /Runtime\.getRuntime\(\)\.exec\s*\(/g,
  /ProcessBuilder\s*\(/g,
  /["'](?:bash|sh|cmd|powershell|pwsh)["']\s*[,)]/gi
];

const NETWORK_CODE_PATTERNS: RegExp[] = [
  /new\s+URL\s*\(['"](https?:\/\/[^'"]+)['"]/g,
  /URI\.create\s*\(['"](https?:\/\/[^'"]+)['"]/g,
  /(HttpClient|DefaultHttpClient|CloseableHttpClient|OkHttpClient)\b/g
];

const DYNAMIC_CODE_PATTERNS: RegExp[] = [
  /Base64\.decode|\.decodeBase64|fromBase64/g,
  /Class\.forName\s*\(['"][^'"]+['"]/g,
  /\.getDeclared(Method|Field)\s*\(/g,
  /URLClassLoader\s*\(/g,
  /new\s+ClassLoader/g,
  /GroovyShell\s*\([^)]*\)\.evaluate/g,
  /Eval\.me\s*\(/g
];

const CREDENTIAL_PATTERNS: RegExp[] = [
  /\b(password|passwd|apiKey|secret|token|accessKey|secretKey)\b\s*[=:]\s*['"][^'"\s]{6,}['"]/gi,
  /\bcredentials\s*\{[^}]*['"][^'"]{6,}['"][^}]*\}/sg
];

const UNSTABLE_DEP_PATTERNS: RegExp[] = [
  /['"][^:'"]+:[^:'"]+:(latest\.(release|integration)|[+*])['"]/gi,
  /version\s*=\s*['"](latest\.(release|integration)|[+*])['"]/gi,
  /changing\s*=\s*true/gi
];

const INIT_SCRIPT_PATTERNS: RegExp[] = [
  /gradle\.addListener\s*\(/g,
  /gradle\.(beforeProject|afterProject|projectsLoaded|buildFinished)\s*\{/g,
  /allprojects\s*\{[^}]*repositories[^}]*maven/sg
];

function lineNumber(content: string, charIndex: number): number {
  const upto = content.slice(0, Math.min(charIndex, content.length));
  return upto.split('\n').length;
}

function hasSuppressionWithoutReason(rawLine: string): boolean {
  const m = /wrapperguard:ignore\b(.*)$/.exec(rawLine);
  if (!m) return false;
  return m[1].trim() === '';
}

function hostOf(url: string): string {
  try {
    return new URL(url).hostname.toLowerCase();
  } catch {
    return '';
  }
}

function take(s: string, n: number): string {
  return s.length > n ? s.slice(0, n) : s;
}

/**
 * Faithful port of BuildScriptRules (repositories, remote apply, process execution, network
 * clients, dynamic code, credentials, unstable deps, mavenLocal/flatDir, init-script hooks).
 */
export function analyzeBuildScript(
  source: { relativePath: string; content: string },
  scriptType: string,
  isInit: boolean,
  settings: ScanSettings
): Finding[] {
  const findings: Finding[] = [];
  const relPath = source.relativePath;

  if (isInit) {
    findings.push(
      base(
        RuleIds.SUPPLY_INIT_SCRIPT,
        'Gradle init script runs before every build',
        'Review init scripts carefully; they can alter repositories, tasks, and all projects.',
        relPath,
        1,
        relPath.split(/[\/\\]/).pop() ?? relPath,
        'WARN',
        'HIGH',
        'SUPPLY_CHAIN'
      )
    );
  }

  source.content.split(/\r\n|\r|\n/).forEach((rawLine, index) => {
    if (hasSuppressionWithoutReason(rawLine)) {
      findings.push(
        base(
          RuleIds.CONFIG_SUPPRESS_NO_REASON,
          'Suppression has no reason',
          'Add a short reason after wrapperguard:ignore.',
          relPath,
          index + 1,
          rawLine.trim(),
          'INFO',
          'LOW',
          'CONFIGURATION',
          'AUDIT_EVIDENCE'
        )
      );
    }
  });

  for (const pattern of REPOSITORY_PATTERNS) {
    pattern.lastIndex = 0;
    let m: RegExpExecArray | null;
    while ((m = pattern.exec(source.content)) !== null) {
      const url = m[1] ?? '';
      findings.push(checkRepositoryUrl(relPath, url, m[0], lineNumber(source.content, m.index), settings));
    }
  }

  for (const pattern of REMOTE_SCRIPT_PATTERNS) {
    pattern.lastIndex = 0;
    let m: RegExpExecArray | null;
    while ((m = pattern.exec(source.content)) !== null) {
      findings.push(
        base(
          RuleIds.SUPPLY_REMOTE_SCRIPT,
          'Remote Gradle script inclusion',
          'Vendor the script, pin it by checksum, or replace it with a reviewed plugin dependency.',
          relPath,
          lineNumber(source.content, m.index),
          take(m[0], 160),
          'ERROR',
          'HIGH',
          'SUPPLY_CHAIN'
        )
      );
    }
  }

  for (const pattern of PROCESS_EXECUTION_PATTERNS) {
    pattern.lastIndex = 0;
    let m: RegExpExecArray | null;
    while ((m = pattern.exec(source.content)) !== null) {
      findings.push(
        base(
          RuleIds.EXECUTION_PROCESS,
          'Process execution in ' + scriptType + ' build logic',
          'Keep build logic declarative; review any command execution path.',
          relPath,
          lineNumber(source.content, m.index),
          take(m[0], 160),
          'ERROR',
          'HIGH',
          'EXECUTION'
        )
      );
    }
  }

  for (const pattern of NETWORK_CODE_PATTERNS) {
    pattern.lastIndex = 0;
    let m: RegExpExecArray | null;
    while ((m = pattern.exec(source.content)) !== null) {
      findings.push(
        base(
          RuleIds.NETWORK_CLIENT,
          'Network client usage in ' + scriptType + ' build logic',
          'Prefer Gradle dependency resolution with approved repositories.',
          relPath,
          lineNumber(source.content, m.index),
          take(m[0], 160),
          'WARN',
          'MEDIUM',
          'NETWORK'
        )
      );
    }
  }

  for (const pattern of DYNAMIC_CODE_PATTERNS) {
    pattern.lastIndex = 0;
    let m: RegExpExecArray | null;
    while ((m = pattern.exec(source.content)) !== null) {
      findings.push(
        base(
          RuleIds.OBFUSCATION_DYNAMIC,
          'Dynamic or reflective code execution in ' + scriptType + ' build logic',
          'Avoid runtime code loading/evaluation in build scripts.',
          relPath,
          lineNumber(source.content, m.index),
          take(m[0], 160),
          'ERROR',
          'HIGH',
          'OBFUSCATION'
        )
      );
    }
  }

  for (const pattern of CREDENTIAL_PATTERNS) {
    pattern.lastIndex = 0;
    let m: RegExpExecArray | null;
    while ((m = pattern.exec(source.content)) !== null) {
      const { masked, changed } = maskSecretValues(take(m[0], 160));
      findings.push(
        base(
          RuleIds.CREDENTIALS_HARDCODED,
          'Hardcoded credential-like value',
          'Use environment variables, Gradle credentials providers, or a secret manager.',
          relPath,
          lineNumber(source.content, m.index),
          masked,
          'WARN',
          'MEDIUM',
          'CREDENTIALS',
          'ACTIONABLE',
          changed
        )
      );
    }
  }

  for (const pattern of UNSTABLE_DEP_PATTERNS) {
    pattern.lastIndex = 0;
    let m: RegExpExecArray | null;
    while ((m = pattern.exec(source.content)) !== null) {
      findings.push(
        base(
          RuleIds.SUPPLY_UNSTABLE_DEP,
          'Unpinned or changing dependency version',
          'Pin exact dependency versions for reproducible builds.',
          relPath,
          lineNumber(source.content, m.index),
          take(m[0], 160),
          'WARN',
          'MEDIUM',
          'SUPPLY_CHAIN'
        )
      );
    }
  }

  const mavenLocal = /\bmavenLocal\s*\(/g;
  mavenLocal.lastIndex = 0;
  let ml: RegExpExecArray | null;
  while ((ml = mavenLocal.exec(source.content)) !== null) {
    findings.push(
      base(
        RuleIds.REPO_MAVEN_LOCAL,
        'mavenLocal() is enabled',
        'mavenLocal() can make builds depend on unreviewed local artifacts.',
        relPath,
        lineNumber(source.content, ml.index),
        ml[0],
        'WARN',
        'MEDIUM',
        'REPOSITORY'
      )
    );
  }

  const flatDir = /\bflatDir\s*\{/g;
  flatDir.lastIndex = 0;
  let fd: RegExpExecArray | null;
  while ((fd = flatDir.exec(source.content)) !== null) {
    findings.push(
      base(
        RuleIds.REPO_FLAT_DIR,
        'flatDir repository is enabled',
        'Prefer a managed repository with checksums and provenance.',
        relPath,
        lineNumber(source.content, fd.index),
        fd[0],
        'WARN',
        'MEDIUM',
        'REPOSITORY'
      )
    );
  }

  if (isInit) {
    for (const pattern of INIT_SCRIPT_PATTERNS) {
      pattern.lastIndex = 0;
      let m: RegExpExecArray | null;
      while ((m = pattern.exec(source.content)) !== null) {
        findings.push(
          base(
            RuleIds.SUPPLY_INIT_LIFECYCLE,
            'Init script hooks into Gradle lifecycle',
            'Confirm this lifecycle hook is expected and reviewed.',
            relPath,
            lineNumber(source.content, m.index),
            take(m[0], 140),
            'WARN',
            'MEDIUM',
            'SUPPLY_CHAIN'
          )
        );
      }
    }
  }

  return distinct(findings);
}

function checkRepositoryUrl(
  relPath: string,
  url: string,
  evidence: string,
  lineNo: number,
  settings: ScanSettings
): Finding {
  if (!url.startsWith('https://')) {
    return base(
      RuleIds.REPO_NOT_HTTPS,
      'Repository URL is not HTTPS',
      'Use HTTPS or remove the repository.',
      relPath,
      lineNo,
      take(evidence, 160),
      'ERROR',
      'HIGH',
      'REPOSITORY'
    );
  }
  const host = hostOf(url);
  if (host === '') {
    return base(
      RuleIds.REPO_MALFORMED,
      'Repository URL is malformed',
      'Fix or remove the repository URL.',
      relPath,
      lineNo,
      take(evidence, 160),
      'ERROR',
      'HIGH',
      'REPOSITORY'
    );
  }
  const allowed = isAllowed(url, settings.allowedHosts, settings.allowedUrlPatterns);
  if (!allowed) {
    if (settings.knownSafeRepositoryHosts.includes(host)) {
      return base(
        RuleIds.REPO_KNOWN_PUBLIC,
        'Known public repository is not in your allowlist',
        'Add ' + host + ' to the allowlist if public repositories are approved for this project.',
        relPath,
        lineNo,
        take(evidence, 160),
        'INFO',
        'LOW',
        'REPOSITORY',
        'AUDIT_EVIDENCE'
      );
    }
    return base(
      RuleIds.REPO_NOT_ALLOWLISTED,
      'Repository host is not allowlisted',
      'Remove the repository or add ' + host + ' after review.',
      relPath,
      lineNo,
      take(evidence, 160),
      'ERROR',
      'HIGH',
      'REPOSITORY'
    );
  }
  return base(
    RuleIds.REPO_ALLOWLISTED,
    'Repository host is allowlisted',
    null,
    relPath,
    lineNo,
    host,
    'INFO',
    'LOW',
    'REPOSITORY',
    'AUDIT_EVIDENCE'
  );
}

function distinct(findings: Finding[]): Finding[] {
  const seen = new Set<string>();
  return findings.filter((f) => {
    const key = [f.severity, f.ruleId, f.location.path, String(f.location.line), f.evidence?.text ?? ''].join('|');
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function base(
  ruleId: string,
  title: string,
  remediation: string | null,
  relativePath: string,
  lineNo: number,
  evidence: string,
  severity: Finding['severity'],
  confidence: Finding['confidence'],
  category: Finding['category'],
  kind: Finding['kind'] = 'ACTIONABLE',
  redacted = false
): Finding {
  return {
    fingerprint: findingFingerprint(ruleId, relativePath, evidence),
    ruleId,
    title,
    message: title,
    severity,
    confidence,
    category,
    kind,
    location: { path: relativePath, line: lineNo },
    evidence: { text: evidence, redacted },
    remediation: remediation ?? undefined,
    reachable: true,
    callChain: [],
    policyAction: 'REVIEW',
    status: 'ACTIVE'
  };
}
