import * as path from 'node:path';
import { createHash } from 'node:crypto';
import { Finding, ScanNotice } from '../models';
import { RuleIds } from './ruleIds';
import { findingFingerprint } from './fingerprints';
import { checkedProjectFile, readProjectFile } from './safePath';

function sha256(root:string,file: string,maximum:number): string {
  return createHash('sha256').update(readProjectFile(root,file,maximum)).digest('hex');
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
export function analyzeProjectStructure(
  projectRoot: string,
  relativeRoot: string,
  trustedWrapperChecksums: Record<string, string>,
  notices:ScanNotice[] = [],
  maximum=1_000_000
): Finding[] {
  const wrapperDir = path.join(projectRoot, 'gradle', 'wrapper');
  const present=(relative:string):boolean=>{
    try {return !!checkedProjectFile(projectRoot,path.join(projectRoot,relative));}
    catch {if(!notices.some(notice=>notice.code === RuleIds.SCAN_FILE_SKIPPED && notice.location?.path === relative))notices.push({code:RuleIds.SCAN_FILE_SKIPPED,message:'File is not a readable regular input inside the project.',location:{path:relative}});return false;}
  };
  const hasWrapperProperties = present('gradle/wrapper/gradle-wrapper.properties');
  const wrapperJar = path.join(wrapperDir, 'gradle-wrapper.jar');
  const hasWrapperJar=present('gradle/wrapper/gradle-wrapper.jar');
  const hasUnixWrapper = present('gradlew');
  const hasWindowsWrapper = present('gradlew.bat');
  const hasSettingsFile =
    present('settings.gradle') || present('settings.gradle.kts');

  const findings: Finding[] = [];

  if (hasWrapperJar) {
    let sha:string|undefined;
    try {sha=sha256(projectRoot,wrapperJar,maximum);}
    catch {notices.push({code:RuleIds.SCAN_FILE_SKIPPED,message:'File is not a readable regular input inside the project.',location:{path:'gradle/wrapper/gradle-wrapper.jar'}});}
    if (sha !== undefined) {
    const expected = trustedWrapperChecksums['gradle-wrapper.jar'];
    if (Object.keys(trustedWrapperChecksums).length > 0 && expected !== sha) {
      findings.push({
        fingerprint: findingFingerprint(RuleIds.WRAPPER_JAR_UNKNOWN, relativeRoot, sha),
        ruleId: RuleIds.WRAPPER_JAR_UNKNOWN,
        title: 'Gradle wrapper JAR checksum is not in the approved list',
        message: 'Gradle wrapper JAR checksum is not in the approved list: ' + sha,
        severity: 'ERROR',
        confidence: 'HIGH',
        category: 'WRAPPER',
        kind: 'ACTIONABLE',
        location: { path: relativeRoot + '/gradle/wrapper/gradle-wrapper.jar' },
        evidence: { text: sha, redacted: false },
        remediation:
          'Review the wrapper JAR, then either restore the approved version or add its checksum to your trusted list.',
        reachable: true,
        callChain: [],
        policyAction: 'REVIEW',
        status: 'ACTIVE'
      });
    } else {
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
    }
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
