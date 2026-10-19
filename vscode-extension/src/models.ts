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

export interface CallChainLink {
  source: SourceLocation;
  viaCommand: string;
}

export interface Finding {
  providerId?: string;
  actions?: import('./providers/sdk').VettingAction[];
  mlSignal?: { score: number; modelId: string; labels: string[] };
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
  callChain?: CallChainLink[];
  policyAction: PolicyAction;
  status: string;
}

export interface ScriptEdge {
  from: SourceLocation;
  to: SourceLocation;
  viaCommand: string;
}

export interface ScanReport {
  providerRuns?: import('./providers/sdk').ProviderRun[];
  schemaVersion: number;
  projectPath: string;
  verdict: ScanVerdict;
  policyName: string;
  findings: Finding[];
  edges: ScriptEdge[];
  scannedFiles: number;
  durationMs: number;
  complete: boolean;
  stale: boolean;
  notices: Array<{ code: string; message: string; location?: SourceLocation }>;
  generatedAtEpochMs?: number;
}

export type ScanState =
  | { kind: 'not-scanned' }
  | { kind: 'scanning' }
  | { kind: 'scanned'; report: ScanReport; rawJson: string }
  | { kind: 'failed'; error: string };

export interface BaselineEntry {
  fingerprint: string;
  ruleId?: string;
  reason?: string;
}

export type EnforcementMode = 'AUDIT' | 'WARN' | 'ENFORCE';

export interface ScanPolicy {
  name: string;
  mode: EnforcementMode;
  minimumBlockingSeverity: Severity;
  requireReachableToBlock: boolean;
  blockedRuleIds: string[];
}

export interface ScanSettings {
  allowedHosts: string[];
  allowedUrlPatterns: string[];
  blockedCommands: string[];
  maxScriptDepth: number;
  maxFileBytes: number;
  requireDistributionSha256: boolean;
  enableRecursiveScanning: boolean;
  knownSafeRepositoryHosts: string[];
  /** Non-empty = escalate unknown wrapper JAR digests to ERROR (WG-WRAPPER-007). */
  trustedWrapperChecksums: Record<string, string>;
  maxLineLength: number;
  maxFilesPerScan: number;
  maxDirectoryDepth: number;
}

export interface ScanNotice {
  code: string;
  message: string;
  location?: SourceLocation;
}
