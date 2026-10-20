import { Finding, ScanReport } from '../models';
import { maskSecretValues } from './redaction';
const EXTENSION_VERSION=(require('../../package.json') as {version:string}).version;

const mask = (text:string):string => maskSecretValues(text).masked;
const ruleKey = (finding:Finding):string => mask(!finding.providerId || finding.providerId === 'wrapperguard' ? finding.ruleId : finding.providerId + '/' + finding.ruleId);

/** Faithful port of core/export/SarifExporter — SARIF 2.1.0 for CI integrations. */
export function toSarif(report: ScanReport): unknown {
  const rules = Array.from(new Set(report.findings.map(ruleKey))).map((ruleId) => ({
    id: ruleId,
    name: ruleId,
    shortDescription: { text: ruleId }
  }));
  const results = report.findings.map((f) => {
    const level = f.severity === 'ERROR' ? 'error' : f.severity === 'WARN' ? 'warning' : 'note';
    const result: Record<string, unknown> = {
      ruleId: ruleKey(f),
      level,
      message: { text: mask(f.message) },
      locations: [
        {
          physicalLocation: {
            artifactLocation: { uri: mask(f.location.path) },
            ...(f.location.line != null ? { region: { startLine: f.location.line } } : {})
          }
        }
      ],
      partialFingerprints: { 'wrapperguard/v1': mask(f.fingerprint) },
      properties: {
        policyAction: f.policyAction,
        category: f.category,
        ...(f.providerId ? {providerId:mask(f.providerId)} : {}),
        ...(f.actions?.length ? {actions:f.actions} : {}),
        ...(f.evidence ? { evidence: mask(f.evidence.text) } : {})
      }
    };
    if (f.status !== 'ACTIVE') {
      result.suppressions = [{ kind: 'external', status: f.status.toLowerCase() }];
    }
    return result;
  });
  return {
    $schema: 'https://json.schemastore.org/sarif-2.1.0.json',
    version: '2.1.0',
    runs: [
      {
        tool: {
          driver: {
            name: 'WrapperGuard',
            version: EXTENSION_VERSION,
            rules
          }
        },
        results
      }
    ]
  };
}
