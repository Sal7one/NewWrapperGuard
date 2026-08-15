import { Finding } from '../models';
import { RuleIds } from './ruleIds';
import { findingFingerprint } from './fingerprints';

const DYNAMIC_VERSION = /=\s*["'](?:\+|latest\.release|latest\.integration)["']/i;
const INLINE_VERSION = /version\s*=\s*["'](?:\+|latest\.release|latest\.integration)["']/i;

/** Faithful port of VersionCatalogRules. */
export function analyzeVersionCatalog(source: { relativePath: string; content: string }): Finding[] {
  const findings: Finding[] = [];
  source.content.split(/\r\n|\r|\n/).forEach((rawLine, index) => {
    const line = rawLine.trim();
    if (line === '' || line.startsWith('#')) return;
    if (DYNAMIC_VERSION.test(line) || INLINE_VERSION.test(line)) {
      findings.push({
        fingerprint: findingFingerprint(RuleIds.CATALOG_DYNAMIC_VERSION, source.relativePath, line),
        ruleId: RuleIds.CATALOG_DYNAMIC_VERSION,
        title: 'Dynamic version in Gradle version catalog',
        message: 'Dynamic version in Gradle version catalog',
        severity: 'WARN',
        confidence: 'MEDIUM',
        category: 'SUPPLY_CHAIN',
        kind: 'ACTIONABLE',
        location: { path: source.relativePath, line: index + 1 },
        evidence: { text: line, redacted: false },
        remediation: 'Pin exact versions in libs.versions.toml for reproducible builds.',
        reachable: true,
        callChain: [],
        policyAction: 'REVIEW',
        status: 'ACTIVE'
      });
    }
  });
  const seen = new Set<string>();
  return findings.filter((f) => {
    const key = [f.location.path, String(f.location.line), f.evidence?.text ?? ''].join('|');
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}
