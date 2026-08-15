import { Finding, ScanSettings } from '../models';
import { RuleIds } from './ruleIds';
import { findingFingerprint } from './fingerprints';
import { isAllowed } from './urlAllowlist';

const DISTRIBUTION_URL = /^\s*distributionUrl\s*=\s*(.+)$/m;
const PRE_RELEASE = /-snapshot-|nightly|release-candidate|rc-/i;
const SHA256_PRESENT = /^\s*distributionSha256Sum\s*=/m;

function lineNumber(content: string, charIndex: number): number {
  const upto = content.slice(0, Math.min(charIndex, content.length));
  return upto.split('\n').length;
}

function base(
  ruleId: string,
  title: string,
  remediation: string,
  relativePath: string,
  lineNo: number | null,
  evidence: string,
  severity: Finding['severity'],
  confidence: Finding['confidence'],
  category: Finding['category']
): Finding {
  return {
    fingerprint: findingFingerprint(ruleId, relativePath, evidence),
    ruleId,
    title,
    message: title,
    severity,
    confidence,
    category,
    kind: 'ACTIONABLE',
    location: { path: relativePath, line: lineNo ?? undefined },
    evidence: { text: evidence, redacted: false },
    remediation,
    reachable: true,
    callChain: [],
    policyAction: 'REVIEW',
    status: 'ACTIVE'
  };
}

/** Faithful port of WrapperPropertiesRules. */
export function analyzeWrapperProperties(
  source: { relativePath: string; content: string },
  settings: ScanSettings
): Finding[] {
  const findings: Finding[] = [];
  const relPath = source.relativePath;

  const urlMatch = DISTRIBUTION_URL.exec(source.content);
  if (!urlMatch) {
    findings.push(
      base(
        RuleIds.WRAPPER_MISSING_DISTRIBUTION_URL,
        'Missing Gradle distribution URL',
        'Add a distributionUrl that points to an approved HTTPS Gradle distribution.',
        relPath,
        null,
        'distributionUrl=',
        'ERROR',
        'HIGH',
        'WRAPPER'
      )
    );
  } else {
    const rawUrl = urlMatch[1].trim();
    const url = rawUrl.replace(/\\:/g, ':');
    const lineNo = lineNumber(source.content, urlMatch.index);

    if (!url.startsWith('https://')) {
      findings.push(
        base(
          RuleIds.WRAPPER_NOT_HTTPS,
          'Gradle distribution is not loaded over HTTPS',
          'Use an HTTPS distribution URL.',
          relPath,
          lineNo,
          url,
          'ERROR',
          'HIGH',
          'WRAPPER'
        )
      );
    }

    if (!isAllowed(url, settings.allowedHosts, settings.allowedUrlPatterns)) {
      findings.push(
        base(
          RuleIds.WRAPPER_HOST_NOT_ALLOWLISTED,
          'Gradle distribution host is not allowlisted',
          'Use services.gradle.org/downloads.gradle.org or add your trusted mirror in WrapperGuard settings.',
          relPath,
          lineNo,
          url,
          'ERROR',
          'HIGH',
          'SUPPLY_CHAIN'
        )
      );
    }

    if (PRE_RELEASE.test(url)) {
      findings.push(
        base(
          RuleIds.WRAPPER_PRE_RELEASE,
          'Gradle distribution appears to be a pre-release or nightly build',
          'Prefer pinned stable Gradle distributions for reproducible builds.',
          relPath,
          lineNo,
          url,
          'WARN',
          'MEDIUM',
          'SUPPLY_CHAIN'
        )
      );
    }
  }

  if (settings.requireDistributionSha256 && !SHA256_PRESENT.test(source.content)) {
    findings.push(
      base(
        RuleIds.WRAPPER_CHECKSUM_MISSING,
        'Gradle distribution checksum is missing',
        'Add distributionSha256Sum to pin the wrapper download artifact.',
        relPath,
        null,
        'distributionSha256Sum=',
        'WARN',
        'HIGH',
        'WRAPPER'
      )
    );
  }

  return findings;
}
