import { ScanPolicy, ScanSettings } from '../models';

export const DEFAULT_ALLOWED_HOSTS = [
  'services.gradle.org',
  'downloads.gradle.org',
  'downloads.gradle-dn.com',
  'plugins.gradle.org',
  'repo.maven.apache.org',
  'repo1.maven.org',
  'dl.google.com',
  'maven.google.com'
];

export const DEFAULT_ALLOWED_URL_PATTERNS = [
  '^https://services\\.gradle\\.org/distributions/gradle-[0-9.]+-(bin|all)\\.zip$',
  '^https://downloads\\.gradle\\.org/distributions/gradle-[0-9.]+-(bin|all)\\.zip$'
];

export const DEFAULT_BLOCKED_COMMANDS = ['certutil', 'bitsadmin'];

export const DEFAULT_KNOWN_SAFE_REPOSITORIES = [
  'repo.maven.apache.org',
  'repo1.maven.org',
  'plugins.gradle.org',
  'dl.google.com',
  'maven.google.com'
];

export const DEFAULT_SETTINGS: ScanSettings = {
  allowedHosts: DEFAULT_ALLOWED_HOSTS,
  allowedUrlPatterns: DEFAULT_ALLOWED_URL_PATTERNS,
  blockedCommands: DEFAULT_BLOCKED_COMMANDS,
  maxScriptDepth: 10,
  maxFileBytes: 1_000_000,
  requireDistributionSha256: false,
  enableRecursiveScanning: true,
  knownSafeRepositoryHosts: DEFAULT_KNOWN_SAFE_REPOSITORIES,
  trustedWrapperChecksums: {},
  maxLineLength: 10_000,
  maxFilesPerScan: 50_000,
  maxDirectoryDepth: 64
};

export const DEFAULT_ENFORCE_POLICY: ScanPolicy = {
  name: 'OSS Defaults',
  mode: 'ENFORCE',
  minimumBlockingSeverity: 'ERROR',
  requireReachableToBlock: true,
  blockedRuleIds: []
};
