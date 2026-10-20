package com.sal7one.wrapperguard.core.history

import com.sal7one.wrapperguard.core.model.*
import com.sal7one.wrapperguard.core.vetting.VettingOperation
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

class ScanHistoryTest {
    private fun fixture(block: (Path, Path, HistoryStore) -> Unit) {
        val user = Files.createTempDirectory("wg-history-").toRealPath()
        try {
            val project = Files.createDirectory(user.resolve("project"))
            block(user, project, HistoryStore(user.resolve("history")))
        } finally { user.toFile().deleteRecursively() }
    }
    private fun finding(id: String, status: FindingStatus = FindingStatus.ACTIVE) = Finding(
        fingerprint = id, ruleId = "RULE", title = "Review source", message = "SECRET_MESSAGE_NEVER_PERSIST",
        severity = Severity.WARN, confidence = Confidence.HIGH, category = FindingCategory.EXECUTION,
        location = SourceLocation("src/App.kt"), evidence = Evidence("SECRET_SOURCE_NEVER_PERSIST"), status = status
    )
    private fun report(root: Path, findings: List<Finding> = listOf(finding("a"))) = ScanReport(
        projectPath = root.toString(), verdict = ScanVerdict.REVIEW_REQUIRED, policyName = "test",
        findings = findings, scannedFiles = 1, durationMs = 1
    )
    private val scope = HistoryScope(HistoryStore.scopeId("same verified scope"), true)

    @Test fun summariesExcludeMessagesEvidenceAndPreserveApprovalIdentity() = fixture { user, root, store ->
        val first = store.record(root, report(root), VettingOperation.SCAN, scope)
        val second = store.record(root, report(root, listOf(finding("a", FindingStatus.SUPPRESSED))), VettingOperation.SCAN, scope)
        val delta = HistoryDelta.compare(first, second)
        assertTrue(delta.available); assertEquals(1, delta.unchanged)
        assertTrue(delta.noLongerReported.isEmpty()); assertTrue(delta.newlyReported.isEmpty())
        val file = user.resolve("history/${first.projectId}/${first.id}.json")
        assertFalse(Files.readString(file).contains("SECRET_"))
        assertEquals(2, store.list(root).size)
    }

    @Test fun sameScopeReportsNewAndAbsentRatherThanProvenFixed() = fixture { _, root, store ->
        val old = store.record(root, report(root, listOf(finding("a"), finding("b"))), VettingOperation.SCAN, scope)
        val now = store.record(root, report(root, listOf(finding("b"), finding("c"))), VettingOperation.SCAN, scope)
        val delta = HistoryDelta.compare(old, now)
        assertEquals(listOf("c"), delta.newlyReported.map { it.fingerprint })
        assertEquals(listOf("a"), delta.noLongerReported.map { it.fingerprint }); assertEquals(1, delta.unchanged)
    }

    @Test fun incompleteUnknownTruncatedStaleOrDifferentContextsNeverCompare() = fixture { _, root, store ->
        val base = store.record(root, report(root), VettingOperation.SCAN, scope)
        for (changed in listOf(base.copy(complete = false), base.copy(scopeKnown = false), base.copy(stale = true),
            base.copy(truncated = true), base.copy(scopeId = HistoryStore.scopeId("other")),
            base.copy(projectId = HistoryStore.scopeId("other project")), base.copy(operation = "BUILD"))) {
            assertFalse(HistoryDelta.compare(base, changed).available)
        }
    }

    @Test fun retentionAndClearPreserveOtherProjectsAndUnownedFiles() = fixture { user, root, store ->
        repeat(4) { store.record(root, report(root), VettingOperation.SCAN, scope, retention = 2) }
        assertEquals(2, store.list(root).size)
        val other = Files.createDirectory(user.resolve("other"))
        store.record(other, report(other), VettingOperation.SCAN, scope)
        val directory = user.resolve("history/${HistoryStore.projectId(root)}")
        val original = directory.resolve("user-notes.json"); Files.writeString(original, "keep")
        val malformed = directory.resolve("${UUID.randomUUID()}.json"); Files.writeString(malformed, "not a history record")
        assertEquals(2, store.clear(root)); assertTrue(Files.exists(original)); assertTrue(Files.exists(malformed))
        assertEquals(1, store.list(other).size)
    }

    @Test fun linkedOrOversizeRecordsAreNeverLoadedOrDeleted() = fixture { user, root, store ->
        val entry = store.record(root, report(root), VettingOperation.SCAN, scope)
        val directory = user.resolve("history/${entry.projectId}")
        val huge = directory.resolve("${UUID.randomUUID()}.json"); Files.write(huge, ByteArray(2 * 1024 * 1024 + 1))
        val original = user.resolve("original.json"); Files.writeString(original, "original")
        val link = directory.resolve("${UUID.randomUUID()}.json")
        Files.createSymbolicLink(link, original)
        assertEquals(1, store.list(root).size); store.clear(root)
        assertTrue(Files.exists(huge)); assertTrue(Files.isSymbolicLink(link)); assertEquals("original", Files.readString(original))
    }

    @Test fun storageInsideProjectAndWrongReportBindingAreRejected() = fixture { user, root, store ->
        assertFailsWith<IllegalArgumentException> { HistoryStore(root.resolve("history")).record(root, report(root), VettingOperation.SCAN, scope) }
        assertFalse(Files.exists(root.resolve("history")))
        val other = Files.createDirectory(user.resolve("other"))
        assertFailsWith<IllegalArgumentException> { store.record(root, report(other), VettingOperation.SCAN, scope) }
    }

    @Test fun findingLimitPreventsMisleadingHistoryComparisons() = fixture { _, root, store ->
        val entry = store.record(root, report(root, (1..5001).map { finding("finding-$it") }), VettingOperation.SCAN, scope)
        assertTrue(entry.truncated); assertTrue(entry.findings.size <= 5000)
        assertFalse(HistoryDelta.compare(entry, entry).available)
    }
}
