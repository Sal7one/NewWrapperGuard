import * as fs from 'node:fs';
import * as path from 'node:path';
import { createHash } from 'node:crypto';
import { Finding } from '../models';
import { RuleIds } from './ruleIds';
import { findingFingerprint } from './fingerprints';

function sha256(file: string): string {
  return createHash('sha256').update(fs.readFileSync(file)).digest('hex');
}

function struct(
  ruleId: string,
  title: string,
  remediation: string,
  severity: Finding['severity'],
  category: Finding['category'],
  relativeRoot: string,
  evidence: string | null
): Finding {
  return {
    fingerprint: findingFingerprint(ruleId, relativeRoot, evidence ?? title),
    ruleId,
    title,
    message: title,
    severity,
    confidence: 'MEDIUM',
    category,
    kind: 'ACTIONABLE',
    location: { path: relativeRoot },
    evidence: evidence !== null ? { text: evidence, redacted: false } : undefined,
    remediation,
    reachable: true,
    callChain: [],
    policyAction: 'REVIEW',
    status: 'ACTIVE'
  };
}

/** Faithful port of ProjectStructureAnalyzer. */
export function analyzeProjectStructure(projectRoot: string, relativeRoot: string): Finding[] {
  const wrapperDir = path.join(projectRoot, 'gradle', 'wrapper');
  const hasWrapperProperties = fs.existsSync(path.join(wrapperDir, 'gradle-wrapper.properties'));
  const wrapperJar = path.join(wrapperDir, 'gradle-wrapper.jar');
  const hasWrapperJar = fs.existsSync(wrapperJar);
  const hasUnixWrapper = fs.existsSync(path.join(projectRoot, 'gradlew'));
  const hasWindowsWrapper = fs.existsSync(path.join(projectRoot, 'gradlew.bat'));
  const hasSettingsFile =
    fs.existsSync(path.join(projectRoot, 'settings.gradle')) || fs.existsSync(path.join(projectRoot, 'settings.gradle.kts'));

  const findings: Finding[] = [];

  if (hasWrapperJar) {
    const sha = sha256(wrapperJar);
    findings.push({
      fingerprint: findingFingerprint(RuleIds.WRAPPER_JAR_SHA256, relativeRoot, sha),
      ruleId: RuleIds.WRAPPER_JAR_SHA256,
      title: 'Gradle wrapper JAR SHA-256',
      message: 'Gradle wrapper JAR SHA-256: ' + sha,
      severity: 'INFO',
      confidence: 'HIGH',
      category: 'WRAPPER',
      kind: 'AUDIT_EVIDENCE',
      location: { path: relativeRoot + '/gradle/wrapper/gradle-wrapper.jar' },
      evidence: { text: sha, redacted: false },
      remediation: undefined,
      reachable: true,
      callChain: [],
      policyAction: 'REVIEW',
      status: 'ACTIVE'
    });
  }

  if (hasWrapperProperties && !hasWrapperJar) {
    findings.push(
      struct(
        RuleIds.STRUCT_WRAPPER_JAR_MISSING,
        'Gradle wrapper JAR is missing',
        'Regenerate the wrapper with Gradle or commit the reviewed wrapper JAR.',
        'WARN',
        'WRAPPER',
        relativeRoot,
        'gradle/wrapper/gradle-wrapper.jar'
      )
    );
  }
  if (hasWrapperProperties && !hasUnixWrapper && !hasWindowsWrapper) {
    findings.push(
      struct(
        RuleIds.STRUCT_WRAPPER_SCRIPTS_MISSING,
        'Wrapper properties exist without wrapper launcher scripts',
        'Commit gradlew and gradlew.bat or remove incomplete wrapper metadata.',
        'WARN',
        'WRAPPER',
        relativeRoot,
        null
      )
    );
  }
  if (!hasSettingsFile) {
    findings.push(
      struct(
        RuleIds.STRUCT_NO_SETTINGS,
        'No Gradle settings file found',
        'Most Gradle projects should commit settings.gradle or settings.gradle.kts.',
        'INFO',
        'CONFIGURATION',
        relativeRoot,
        null
      )
    );
  }
  return findings;
}
