export type Severity = 'INFO' | 'WARN' | 'ERROR';
export type PolicyAction = 'ALLOW' | 'REVIEW' | 'BLOCK';
export type ScanVerdict =
  | 'NOT_SCANNED'
  | 'CLEAN'
  | 'REVIEW_REQUIRED'
  | 'EXECUTION_BLOCKED'
  | 'INCOMPLETE'
  | 'STALE'
  | 'FAILED';

export interface SourceLocation {
  path: string;
  line?: number;
  column?: number;
}

export interface Finding {
  fingerprint: string;
  ruleId: string;
  title: string;
  message: string;
  severity: Severity;
  confidence: 'LOW' | 'MEDIUM' | 'HIGH';
  category: string;
  kind: 'ACTIONABLE' | 'AUDIT_EVIDENCE' | 'SCANNER_NOTICE';
  location: SourceLocation;
  evidence?: { text: string; redacted: boolean };
  remediation?: string;
  reachable: boolean;
  policyAction: PolicyAction;
  status: string;
}

export interface ScanReport {
  schemaVersion: number;
  projectPath: string;
  verdict: ScanVerdict;
  policyName: string;
  findings: Finding[];
  scannedFiles: number;
  durationMs: number;
  complete: boolean;
  stale: boolean;
  notices: Array<{ code: string; message: string; location?: SourceLocation }>;
}
