package com.sal7one.wrapperguard.core.policy

import com.sal7one.wrapperguard.core.model.*


data class ScanPolicy(
    val name: String = "OSS Defaults",
    val mode: EnforcementMode = EnforcementMode.WARN,
    val minimumBlockingSeverity: Severity = Severity.ERROR,
    val requireReachableToBlock: Boolean = true,
    val blockedRuleIds: Set<String> = emptySet()
) {
    fun actionFor(finding: Finding): PolicyAction {
        if (finding.kind != FindingKind.ACTIONABLE) return PolicyAction.ALLOW
        if (mode == EnforcementMode.AUDIT) return PolicyAction.REVIEW
        val explicitlyBlocked = finding.ruleId in blockedRuleIds
        val severityBlocks = finding.severity.ordinal >= minimumBlockingSeverity.ordinal
        val reachabilityAllows = !requireReachableToBlock || finding.reachable
        return when {
            mode == EnforcementMode.ENFORCE && reachabilityAllows && (explicitlyBlocked || severityBlocks) -> PolicyAction.BLOCK
            finding.severity == Severity.INFO -> PolicyAction.ALLOW
            else -> PolicyAction.REVIEW
        }
    }
}

enum class EnforcementMode { AUDIT, WARN, ENFORCE }
