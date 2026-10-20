import { Finding, ScanSettings } from '../models';
import { RuleIds } from './ruleIds';
import { findingFingerprint } from './fingerprints';
import { maskSecretValues } from './redaction';
import { isAllowed } from './urlAllowlist';
import { containsUnixNetworkCommand } from './unixNetworkCommands';

export type ScriptFlavor = 'UNIX' | 'WINDOWS' | 'POWERSHELL';

const URL_PATTERN = /https?:\/\/[^\s"'<>(){}|\\^\u0060\[\]]+/gi;
const INSECURE_TLS = /(?<!\w)(--insecure|-k|--no-check-certificate|-SkipCertificateCheck)(?!\w)/i;
const UNIX_NETWORK_COMMANDS = /(?<![\w./-])(curl|wget|fetch|aria2c|nc|netcat|socat|ftp|scp|rsync)(?![\w-])/i;
const WINDOWS_NETWORK_COMMANDS = /(?<![\w./-])(bitsadmin|certutil|curl(?:\.exe)?|wget(?:\.exe)?|Invoke-WebRequest|Invoke-RestMethod|iwr|irm|Start-BitsTransfer)(?![\w-])/i;
const PIPE_TO_SHELL = /(curl|wget|iwr|irm|Invoke-WebRequest|Invoke-RestMethod).*(\|\s*(bash|sh|zsh|pwsh|powershell|iex|Invoke-Expression)|>\s*\/tmp\/[^;&|]+\s*[;&|]\s*(bash|sh))/i;
const ENCODED_EXECUTION = /(base64\s+(-d|--decode)|certutil\s+-decode|FromBase64String|EncodedCommand|eval\s+["'$]|\biex\b|Invoke-Expression)/i;
const WRAPPER_MUTATION = /(>\s*|>>\s*|rm\s+-f\s+|del\s+|move\s+|copy\s+).*(gradle-wrapper\.jar|gradle-wrapper\.properties|gradlew(?:\.bat)?)/i;
const DESTRUCTIVE = /(?<!\w)(rm\s+-rf\s+[\w./$~*-]*|del\s+\/[fsq]\s+|format\s+|Remove-Item\s+.*-Recurse)/i;
const PRIVILEGE = /(?<!\w)(sudo|Set-ExecutionPolicy\s+Bypass|chmod\s+\+x\s+.*(?:\/tmp|http|curl|wget))(?![\w-])/i;
const ENVIRONMENT_LEAK = /(?<!\w)(env|printenv|set)(?!\w).*(token|secret|password|key|credential)/i;
const SCRIPT_WRITE = /(?:cat\s+>\s*|echo\b.*>\s*)(["']?)(?<path>[^\s;&|"']+\.sh)\1/gi;
const SCRIPT_EXECUTE = /(?<!\w)(?:bash|sh|chmod\s+\+x)\s+(["']?)(?<path>[^\s;&|"']+\.sh)\1/gi;

function escapeRegExp(s: string): string {
  return s.replace(/[.*+?^$|(){}\[\]\\]/g, '\\$&');
}

function lineNumber(content: string, charIndex: number): number {
  const upto = content.slice(0, Math.min(charIndex, content.length));
  return upto.split('\n').length;
}

function hasSuppressionWithoutReason(line: string): boolean {
  const m = /wrapperguard:ignore\b(.*)$/i.exec(line);
  if (!m) return false;
  return m[1].trim() === '';
}

/** Strips the untrusted wrapperguard:ignore suffix so fingerprints stay stable. */
function stripIgnoreSuffix(line: string): string {
  const idx = line.toLowerCase().indexOf('wrapperguard:ignore');
  let result = idx < 0 ? line : line.substring(0, idx).trimEnd();
  // Drop the comment marker that introduced the ignore (shell '#', groovy '//').
  if (result.endsWith('//')) result = result.slice(0, -2).trimEnd();
  else if (result.endsWith('#')) result = result.slice(0, -1).trimEnd();
  return result;
}

/**
 * Faithful port of ScriptRules (shell/batch/PowerShell per-line analysis), including the
 * exact check ordering, the eval "set -- $( Gradle JVM-opts special case, suppression, and
 * generated-then-executed detection.
 */
export function analyzeScript(
  source: { relativePath: string; content: string },
  flavor: ScriptFlavor,
  settings: ScanSettings
): Finding[] {
  const findings: Finding[] = [];
  const networkPattern = flavor === 'UNIX' ? UNIX_NETWORK_COMMANDS : WINDOWS_NETWORK_COMMANDS;
  const commentPrefixes = flavor === 'POWERSHELL' ? ['#'] : flavor === 'UNIX' ? ['#'] : ['@rem', 'rem ', '::'];

  source.content.split(/\r\n|\r|\n/).forEach((raw, idx) => {
    const line = raw.trim();
    if (line === '') return;
    const lineNo = idx + 1;
    // Evidence for line-level findings: strip any untrusted ignore-marker suffix so
    // fingerprints stay stable when the suppression is later approved (#5).
    const evidenceLine = stripIgnoreSuffix(line);

    if (line.toLowerCase().includes('wrapperguard:ignore')) {
      if (hasSuppressionWithoutReason(line)) {
        findings.push(
          finding(
            RuleIds.CONFIG_SUPPRESS_NO_REASON,
            'Suppression has no reason',
            'Add a short reason after wrapperguard:ignore.',
            source.relativePath,
            lineNo,
            line,
            'INFO',
            'LOW',
            'CONFIGURATION',
            'AUDIT_EVIDENCE'
          )
        );
      }
      // UNTRUSTED (#5): a repo-shipped ignore never suppresses — checks below still run.
    }
    if (commentPrefixes.some((p) => line.toLowerCase().startsWith(p.toLowerCase()))) return;

    for (const blocked of settings.blockedCommands) {
      const blockedPattern = new RegExp('(?<![\\w./-])' + escapeRegExp(blocked) + '(?![\\w-])', 'i');
      if (blockedPattern.test(line)) {
        findings.push(
          finding(
            RuleIds.SUPPLY_BLOCKED_COMMAND,
            'Blocked command detected: ' + blocked,
            'Remove this command or update the blocked command policy if it is approved.',
            source.relativePath,
            lineNo,
            evidenceLine,
            'ERROR',
            'HIGH',
            'EXECUTION'
          )
        );
      }
    }

    if (PIPE_TO_SHELL.test(line)) {
      findings.push(
        finding(
          RuleIds.SUPPLY_PIPE_TO_SHELL,
          'Remote content is piped into a shell',
          'Download, verify, and execute pinned artifacts instead of piping network output into an interpreter.',
          source.relativePath,
          lineNo,
          evidenceLine,
          'ERROR',
          'HIGH',
          'SUPPLY_CHAIN'
        )
      );
    } else if (flavor === 'UNIX' ? containsUnixNetworkCommand(line) : networkPattern.test(line)) {
      findings.push(
        finding(
          RuleIds.NETWORK_COMMAND,
          'Network-capable command detected',
          'Review why wrapper/bootstrap code needs network access.',
          source.relativePath,
          lineNo,
          evidenceLine,
          'WARN',
          'MEDIUM',
          'NETWORK'
        )
      );
    }

    if (INSECURE_TLS.test(line)) {
      findings.push(
        finding(
          RuleIds.NETWORK_INSECURE_TLS,
          'TLS verification is disabled',
          'Remove insecure TLS flags and fix the certificate trust chain.',
          source.relativePath,
          lineNo,
          evidenceLine,
          'ERROR',
          'HIGH',
          'NETWORK'
        )
      );
    }

    const isGradleJvmOptsEval = line.startsWith('eval "set -- $(');
    if (!isGradleJvmOptsEval && ENCODED_EXECUTION.test(line)) {
      findings.push(
        finding(
          RuleIds.OBFUSCATION_ENCODED_EXEC,
          'Encoded or dynamic execution pattern detected',
          'Avoid eval/iex/base64 execution in wrapper and bootstrap scripts.',
          source.relativePath,
          lineNo,
          evidenceLine,
          'ERROR',
          'HIGH',
          'OBFUSCATION'
        )
      );
    }

    if (WRAPPER_MUTATION.test(line)) {
      findings.push(
        finding(
          RuleIds.WRAPPER_MUTATION,
          'Script mutates Gradle wrapper files',
          'Wrapper files should be reviewed and committed, not rewritten by bootstrap scripts.',
          source.relativePath,
          lineNo,
          evidenceLine,
          'ERROR',
          'HIGH',
          'WRAPPER'
        )
      );
    }

    if (DESTRUCTIVE.test(line)) {
      findings.push(
        finding(
          RuleIds.FILE_SYSTEM_DESTRUCTIVE,
          'Destructive file-system command detected',
          'Confirm this cannot delete project or user data unexpectedly.',
          source.relativePath,
          lineNo,
          evidenceLine,
          'WARN',
          'MEDIUM',
          'FILE_SYSTEM'
        )
      );
    }

    if (PRIVILEGE.test(line)) {
      findings.push(
        finding(
          RuleIds.EXECUTION_PRIVILEGE,
          'Privilege or execution policy change detected',
          'Avoid privilege escalation or execution policy bypass in project bootstrap code.',
          source.relativePath,
          lineNo,
          evidenceLine,
          'WARN',
          'MEDIUM',
          'EXECUTION'
        )
      );
    }

    if (ENVIRONMENT_LEAK.test(line)) {
      const { masked, changed } = maskSecretValues(evidenceLine);
      findings.push(
        finding(
          RuleIds.CREDENTIALS_ENV_LEAK,
          'Potential environment secret exposure',
          'Do not print or transmit environment variables that may contain secrets.',
          source.relativePath,
          lineNo,
          masked,
          'WARN',
          'MEDIUM',
          'CREDENTIALS',
          'ACTIONABLE',
          changed
        )
      );
    }

    URL_PATTERN.lastIndex = 0;
    let m: RegExpExecArray | null;
    while ((m = URL_PATTERN.exec(line)) !== null) {
      const url = m[0].replace(/[.,;]+$/, '');
      const { masked: safeUrl, changed: urlRedacted } = maskSecretValues(url);
      const allowed = isAllowed(url, settings.allowedHosts, settings.allowedUrlPatterns);
      if (allowed) {
        findings.push(
          finding(
            RuleIds.NETWORK_URL_APPROVED,
            'Approved URL observed',
            null,
            source.relativePath,
            lineNo,
            safeUrl,
            'INFO',
            'LOW',
            'NETWORK',
            'AUDIT_EVIDENCE',
            urlRedacted
          )
        );
      } else {
        findings.push(
          finding(
            RuleIds.NETWORK_URL_NOT_ALLOWLISTED,
            'URL is not allowlisted',
            'Verify the URL and add it to the allowlist only if it is trusted.',
            source.relativePath,
            lineNo,
            safeUrl,
            'WARN',
            'MEDIUM',
            'NETWORK',
            'ACTIONABLE',
            urlRedacted
          )
        );
      }
    }
  });

  findings.push(...detectGeneratedThenExecutedScripts(source));
  return distinct(findings);
}

function detectGeneratedThenExecutedScripts(source: { relativePath: string; content: string }): Finding[] {
  const written = new Set<string>();
  SCRIPT_WRITE.lastIndex = 0;
  let m: RegExpExecArray | null;
  while ((m = SCRIPT_WRITE.exec(source.content)) !== null) {
    const p = m.groups?.path;
    if (p && p.trim() !== '') written.add(p);
  }
  if (written.size === 0) return [];
  const out: Finding[] = [];
  SCRIPT_EXECUTE.lastIndex = 0;
  while ((m = SCRIPT_EXECUTE.exec(source.content)) !== null) {
    const path = m.groups?.path;
    if (!path || !written.has(path)) continue;
    const lineNo = lineNumber(source.content, m.index);
    out.push(
      finding(
        RuleIds.EXECUTION_GENERATED_THEN_RUN,
        'Generated script is later executed',
        'Avoid generating and executing scripts during wrapper/bootstrap flow, or review and pin the generated script content.',
        source.relativePath,
        lineNo,
        path,
        'WARN',
        'MEDIUM',
        'EXECUTION'
      )
    );
  }
  return out;
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

function finding(
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
