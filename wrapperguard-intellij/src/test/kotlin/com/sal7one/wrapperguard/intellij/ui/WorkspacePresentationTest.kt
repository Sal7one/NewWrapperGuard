package com.sal7one.wrapperguard.intellij.ui

import com.sal7one.wrapperguard.core.model.*
import org.junit.Assert.*
import org.junit.Test

class WorkspacePresentationTest {
    private fun finding(id: String, action: PolicyAction = PolicyAction.BLOCK,
        status: FindingStatus = FindingStatus.ACTIVE, line: Int = 1) = Finding(
        fingerprint = id, ruleId = "WG-SCRIPT-001", title = "Remote script execution",
        message = "Review the command", severity = Severity.ERROR, confidence = Confidence.HIGH,
        category = FindingCategory.EXECUTION, location = SourceLocation("scripts/bootstrap.sh", line),
        policyAction = action, status = status)

    @Test fun `blocking and review scopes exclude suppressed findings`() {
        val active = finding("active")
        val suppressed = finding("suppressed", status = FindingStatus.SUPPRESSED)
        val review = finding("review", PolicyAction.REVIEW)
        val suppressedReview = finding("suppressed-review", PolicyAction.REVIEW, FindingStatus.SUPPRESSED)
        val all = listOf(active, suppressed, review, suppressedReview)
        assertEquals(listOf(active), visibleFindings(all, FindingsViewState(scope = FindingScope.BLOCKERS)))
        assertEquals(listOf(review), visibleFindings(all, FindingsViewState(scope = FindingScope.REVIEW)))
        assertEquals(setOf(suppressed, suppressedReview), visibleFindings(all, FindingsViewState(scope = FindingScope.SUPPRESSED)).toSet())
    }

    @Test fun `file order compares line numbers numerically in both directions`() {
        val second = finding("second", line = 2)
        val tenth = finding("tenth", line = 10)
        val all = listOf(tenth, second)
        val state = FindingsViewState(order = FindingOrder.FILE, selected = tenth)
        assertEquals(listOf(second, tenth), visibleFindings(all, state))
        assertEquals(listOf(tenth, second), visibleFindings(all, state.copy(descending = true)))
        assertEquals(tenth, state.selected)
    }

    @Test fun `priority puts active blockers before reviews and suppressed blockers`() {
        val block = finding("block")
        val review = finding("review", PolicyAction.REVIEW)
        val suppressed = finding("suppressed", status = FindingStatus.SUPPRESSED)
        assertEquals(listOf(block, review, suppressed), visibleFindings(listOf(suppressed, review, block), FindingsViewState()))
    }

    @Test fun `search combines terms across fields without case sensitivity`() {
        val item = finding("match")
        assertEquals(listOf(item), visibleFindings(listOf(item), FindingsViewState(query = "BOOTSTRAP remote")))
        assertTrue(visibleFindings(listOf(item), FindingsViewState(query = "bootstrap missing")).isEmpty())
    }

    @Test fun `stale flag overrides a clean verdict`() {
        val report = ScanReport(projectPath = "/project", verdict = ScanVerdict.CLEAN, policyName = "test",
            findings = emptyList(), edges = emptyList(), scannedFiles = 1, durationMs = 1)
        assertEquals(ScanVerdict.NOT_SCANNED, displayVerdict(null))
        assertEquals(ScanVerdict.CLEAN, displayVerdict(report))
        assertEquals(ScanVerdict.STALE, displayVerdict(report.copy(stale = true)))
        assertEquals(ScanVerdict.FAILED, displayVerdict(report.copy(verdict = ScanVerdict.FAILED)))
    }

    private fun edge(from: String, to: String) = ScriptEdge(SourceLocation(from), SourceLocation(to), "source")

    @Test fun `graph retains rootless cycles disconnected components and self calls`() {
        val edges = listOf(edge("entry", "child"), edge("a", "b"), edge("b", "a"), edge("self", "self"))
        val layout = ExecutionLayout.build(edges)
        assertEquals(setOf("entry", "child", "a", "b", "self"), layout.layers.flatten().toSet())
        assertEquals(5, layout.layers.flatten().size)
        assertEquals(setOf("entry"), layout.roots)
        assertEquals(layout, ExecutionLayout.build(edges.reversed()))
    }

    @Test fun `risky path finds shortest entry through cycles without depending on edge order`() {
        val edges = listOf(edge("entry", "a"), edge("a", "b"), edge("b", "a"),
            edge("entry", "long"), edge("long", "longer"), edge("longer", "b"))
        val blocker = finding("block").copy(location = SourceLocation("b"))
        val paths = executionRiskPaths(edges, listOf(blocker))
        assertEquals(listOf(ExecutionRiskPath("b", listOf("entry", "a", "b"), true, true)), paths)
        assertEquals(paths, executionRiskPaths(edges.reversed(), listOf(blocker)))
    }

    @Test fun `risky paths distinguish detached files and rootless cycles and exclude approvals`() {
        val edges = listOf(edge("a", "b"), edge("b", "a"))
        val cycle = finding("cycle").copy(location = SourceLocation("b"))
        val detached = finding("detached").copy(location = SourceLocation("standalone"))
        val approved = finding("approved", status = FindingStatus.SUPPRESSED).copy(location = SourceLocation("approved"))
        val review = finding("review", PolicyAction.REVIEW).copy(location = SourceLocation("review"))
        val paths = executionRiskPaths(edges, listOf(cycle, cycle.copy(fingerprint = "same-file"), detached, approved, review))
        assertEquals(listOf(
            ExecutionRiskPath("b", listOf("b"), false, true),
            ExecutionRiskPath("standalone", listOf("standalone"), false, false)), paths)
    }
}
