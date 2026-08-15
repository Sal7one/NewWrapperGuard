import { createHash } from 'node:crypto';

/** Faithful port of core/util/Fingerprints.finding: SHA-256 hex, first 24 chars. */
export function findingFingerprint(ruleId: string, path: string, evidence: string): string {
  const normalized = ruleId + '\u0000' + path.replace(/\\/g, '/') + '\u0000' + evidence.trim();
  return createHash('sha256').update(normalized, 'utf8').digest('hex').slice(0, 24);
}
