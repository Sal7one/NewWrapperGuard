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
        // The legacy research signal is an inactive shadow observation, never enforcement.
        if (finding.mlSignal != null) return PolicyAction.ALLOW
        if (finding.kind != FindingKind.ACTIONABLE) return PolicyAction.ALLOW
        // Even explicit rule overrides must preserve the INFO-never-blocks contract.
        if (finding.severity == Severity.INFO) return PolicyAction.ALLOW
        if (mode == EnforcementMode.AUDIT) return PolicyAction.REVIEW
        val explicitlyBlocked = finding.ruleId in blockedRuleIds
        val severityBlocks = finding.severity.ordinal >= minimumBlockingSeverity.ordinal
        val reachabilityAllows = !requireReachableToBlock || finding.reachable
        return when {
            mode == EnforcementMode.ENFORCE && reachabilityAllows && (explicitlyBlocked || severityBlocks) -> PolicyAction.BLOCK
            else -> PolicyAction.REVIEW
        }
    }
}

enum class EnforcementMode { AUDIT, WARN, ENFORCE }
