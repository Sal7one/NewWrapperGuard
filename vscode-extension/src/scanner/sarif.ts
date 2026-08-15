import { ScanReport } from '../models';

/** Faithful port of core/export/SarifExporter — SARIF 2.1.0 for CI integrations. */
export function toSarif(report: ScanReport): unknown {
  const rules = Array.from(new Set(report.findings.map((f) => f.ruleId))).map((ruleId) => ({
    id: ruleId,
    name: ruleId,
    shortDescription: { text: ruleId }
  }));
  const results = report.findings.map((f) => {
    const level = f.severity === 'ERROR' ? 'error' : f.severity === 'WARN' ? 'warning' : 'note';
    const result: Record<string, unknown> = {
      ruleId: f.ruleId,
      level,
      message: { text: f.message },
      locations: [
        {
          physicalLocation: {
            artifactLocation: { uri: f.location.path },
            ...(f.location.line != null ? { region: { startLine: f.location.line } } : {})
          }
        }
      ],
      partialFingerprints: { 'wrapperguard/v1': f.fingerprint },
      properties: {
        policyAction: f.policyAction,
        category: f.category,
        ...(f.evidence ? { evidence: f.evidence.text } : {})
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
            version: '0.2.0',
            rules
          }
        },
        results
      }
    ]
  };
}
