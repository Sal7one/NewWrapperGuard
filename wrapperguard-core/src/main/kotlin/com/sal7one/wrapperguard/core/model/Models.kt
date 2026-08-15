package com.sal7one.wrapperguard.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class Severity { INFO, WARN, ERROR }

@Serializable
enum class Confidence { LOW, MEDIUM, HIGH }

@Serializable
enum class FindingCategory {
    WRAPPER, NETWORK, EXECUTION, OBFUSCATION, REPOSITORY,
    CREDENTIALS, SUPPLY_CHAIN, FILE_SYSTEM, CONFIGURATION, AUDIT
}

@Serializable
enum class FindingKind { ACTIONABLE, AUDIT_EVIDENCE, SCANNER_NOTICE }

@Serializable
enum class PolicyAction { ALLOW, REVIEW, BLOCK }

@Serializable
enum class FindingStatus { ACTIVE, SUPPRESSED, ACCEPTED, FIXED }

@Serializable
enum class ScanVerdict {
    NOT_SCANNED, CLEAN, REVIEW_REQUIRED, EXECUTION_BLOCKED,
    INCOMPLETE, STALE, FAILED
}

@Serializable
data class SourceLocation(
    val path: String,
    val line: Int? = null,
    val column: Int? = null
) {
    val display: String get() = if (line == null) path else "$path:$line"
}

@Serializable
data class Evidence(
    val text: String,
    val redacted: Boolean = false
)

@Serializable
data class CallChainLink(
    val source: SourceLocation,
    val viaCommand: String
)

@Serializable
data class Finding(
    val fingerprint: String,
    val ruleId: String,
    val title: String,
    val message: String,
    val severity: Severity,
    val confidence: Confidence,
    val category: FindingCategory,
    val kind: FindingKind = FindingKind.ACTIONABLE,
    val location: SourceLocation,
    val evidence: Evidence? = null,
    val remediation: String? = null,
    val reachable: Boolean = true,
    val callChain: List<CallChainLink> = emptyList(),
    val policyAction: PolicyAction = PolicyAction.REVIEW,
    val status: FindingStatus = FindingStatus.ACTIVE,
    val mlSignal: MlSignal? = null
)

@Serializable
data class ScriptEdge(
    val from: SourceLocation,
    val to: SourceLocation,
    val viaCommand: String
)

@Serializable
data class ScanNotice(
    val code: String,
    val message: String,
    val location: SourceLocation? = null
)

@Serializable
data class ScanReport(
    val schemaVersion: Int = 1,
    val projectPath: String,
    val verdict: ScanVerdict,
    val policyName: String,
    val findings: List<Finding>,
    val edges: List<ScriptEdge> = emptyList(),
    val scannedFiles: Int,
    val durationMs: Long,
    val complete: Boolean = true,
    val stale: Boolean = false,
    val notices: List<ScanNotice> = emptyList(),
    val generatedAtEpochMs: Long = System.currentTimeMillis()
) {
    val blockers: List<Finding> get() = findings.filter {
        it.status == FindingStatus.ACTIVE && it.policyAction == PolicyAction.BLOCK
    }
    val warnings: List<Finding> get() = findings.filter {
        it.status == FindingStatus.ACTIVE && it.policyAction == PolicyAction.REVIEW
    }
    val auditEvidence: List<Finding> get() = findings.filter {
        it.kind == FindingKind.AUDIT_EVIDENCE
    }
}

@Serializable
data class MlSignal(
    val score: Double,
    val modelId: String,
    val labels: List<String> = emptyList()
)

/**
 * One user/org-approved baseline entry. Baselines are trusted ONLY when they come from
 * outside the scanned repository (e.g. a user-level file or an org config); a file shipped
 * inside a repository is untrusted until the user approves it explicitly.
 */
@Serializable
data class BaselineEntry(
    val fingerprint: String,
    val ruleId: String? = null,
    val reason: String? = null
)
