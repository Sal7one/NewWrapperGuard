import { Finding, PolicyAction, ScanPolicy } from '../models';

const ORDER = ['INFO', 'WARN', 'ERROR'];

/** Faithful port of core/policy/ScanPolicy.actionFor. */
export function actionFor(policy: ScanPolicy, finding: Finding): PolicyAction {
  if (finding.mlSignal) return 'ALLOW';
  if (finding.kind !== 'ACTIONABLE') return 'ALLOW';
  if (finding.severity === 'INFO') return 'ALLOW';
  if (policy.mode === 'AUDIT') return 'REVIEW';
  const explicitlyBlocked = policy.blockedRuleIds.indexOf(finding.ruleId) >= 0;
  const severityBlocks = ORDER.indexOf(finding.severity) >= ORDER.indexOf(policy.minimumBlockingSeverity);
  const reachabilityAllows = !policy.requireReachableToBlock || finding.reachable;
  if (policy.mode === 'ENFORCE' && reachabilityAllows && (explicitlyBlocked || severityBlocks)) return 'BLOCK';
  return 'REVIEW';
}
