package com.sal7one.wrapperguard.intellij.ui

import com.sal7one.wrapperguard.core.model.*

/** Presentation state stays outside the scanner and survives navigation between tabs. */
internal enum class FindingScope(val label: String) {
    ALL("All findings"), BLOCKERS("Blocking"), REVIEW("Needs review"), SUPPRESSED("Suppressed")
}

internal enum class FindingOrder(val label: String) {
    PRIORITY("Priority"), FILE("File"), RULE("Rule"), TITLE("Title")
}

internal data class FindingsViewState(
    val query: String = "",
    val scope: FindingScope = FindingScope.ALL,
    val order: FindingOrder = FindingOrder.PRIORITY,
    val descending: Boolean = false,
    val selected: Finding? = null
)

internal fun visibleFindings(findings: List<Finding>, state: FindingsViewState): List<Finding> {
    val terms = state.query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val filtered = findings.filter { finding ->
        val inScope = when (state.scope) {
            FindingScope.ALL -> true
            FindingScope.BLOCKERS -> finding.status == FindingStatus.ACTIVE && finding.policyAction == PolicyAction.BLOCK
            FindingScope.REVIEW -> finding.status == FindingStatus.ACTIVE && finding.policyAction == PolicyAction.REVIEW
            FindingScope.SUPPRESSED -> finding.status == FindingStatus.SUPPRESSED
        }
        inScope && terms.all { term ->
            listOf(finding.title, finding.ruleId, finding.location.path, finding.category.name,
                finding.severity.name, finding.evidence?.text.orEmpty()).any { it.contains(term, ignoreCase = true) }
        }
    }
    val primary = when (state.order) {
        FindingOrder.PRIORITY -> compareBy<Finding> { it.status != FindingStatus.ACTIVE }
            .thenByDescending { it.policyAction.ordinal }.thenByDescending { it.severity.ordinal }
        FindingOrder.FILE -> compareBy(String.CASE_INSENSITIVE_ORDER) { f: Finding -> f.location.path }
            .thenBy { it.location.line }.thenBy { it.location.column }
        FindingOrder.RULE -> compareBy<Finding> { it.ruleId }
        FindingOrder.TITLE -> compareBy(String.CASE_INSENSITIVE_ORDER) { f: Finding -> f.title }
    }
    val ordered = if (state.descending) primary.reversed() else primary
    return filtered.sortedWith(ordered.thenBy { it.location.path }.thenBy { it.location.line }
        .thenBy { it.ruleId }.thenBy { it.fingerprint })
}

internal fun displayVerdict(report: ScanReport?): ScanVerdict = when {
    report == null -> ScanVerdict.NOT_SCANNED
    report.stale -> ScanVerdict.STALE
    else -> report.verdict
}
