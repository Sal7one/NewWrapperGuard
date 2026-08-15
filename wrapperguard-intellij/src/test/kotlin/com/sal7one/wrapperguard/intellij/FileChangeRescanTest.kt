package com.sal7one.wrapperguard.intellij

import com.intellij.testFramework.HeavyPlatformTestCase
import com.sal7one.wrapperguard.core.model.PolicyAction
import com.sal7one.wrapperguard.core.model.ScanVerdict
import com.sal7one.wrapperguard.intellij.execution.toctouBlockedReport
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Verifies the file-change rescan contract: a watched-file change marks the report STALE
 * immediately, the rescan is debounced, and VFS noise with identical content restores
 * freshness without a rescan (content-hash gating).
 */
class FileChangeRescanTest : HeavyPlatformTestCase() {

    private fun writeFixture(dir: Path, name: String, content: String) {
        val file = dir.resolve(name)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }

    fun testChangeMarksStaleThenRescans() {
        val root = Path.of(project.basePath!!)
        writeFixture(root, "gradlew", "#!/bin/sh\necho \"fixture\"\n")
        writeFixture(root, "build.gradle", "// v1\n")

        val service = project.getService(WrapperGuardProjectService::class.java)
        service.scan()
        val initial = service.report!!
        assertFalse(initial.stale)

        // Modify a watched file and go through the same path the VFS listener uses.
        Files.writeString(root.resolve("build.gradle"), "// v2\n")
        service.markStaleAndScheduleRescan()

        // STALE must be immediate (debounce applies only to the rescan).
        assertTrue(service.stale)

        // The debounced rescan (3 s) must eventually publish a fresh, non-stale report.
        var refreshed = false
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            val r = service.report
            if (r != null && r !== initial && !r.stale) { refreshed = true; break }
            Thread.sleep(250)
        }
        assertTrue("debounced rescan must publish a fresh report", refreshed)
        assertFalse(service.stale)
    }

    fun testWrapperJarWatchedAndToctouReportBlocks() {
        assertTrue(WrapperGuardProjectService.isWatchedFileName("gradle-wrapper.jar"))
        val report = toctouBlockedReport(project)
        assertEquals(ScanVerdict.EXECUTION_BLOCKED, report.verdict)
        assertTrue(report.findings.any { it.ruleId == "WG-SCAN-TOCTOU" && it.policyAction == PolicyAction.BLOCK })
    }

    fun testUnchangedContentRestoresFreshnessWithoutRescan() {
        val root = Path.of(project.basePath!!)
        writeFixture(root, "gradlew", "#!/bin/sh\necho \"fixture\"\n")
        writeFixture(root, "build.gradle", "// v1\n")

        val service = project.getService(WrapperGuardProjectService::class.java)
        service.scan()
        val initial = service.report!!
        assertFalse(initial.stale)

        val published = CopyOnWriteArrayList<com.sal7one.wrapperguard.core.model.ScanReport?>()
        service.addListener { published += it }

        // VFS noise: mark stale, but nothing on disk actually changed.
        service.markStaleAndScheduleRescan()
        assertTrue(service.stale)

        var restored = false
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (!service.stale) { restored = true; break }
            Thread.sleep(250)
        }
        assertTrue("freshness must be restored after the hash check", restored)
        // No new scan: the original report instance stays authoritative.
        assertTrue(service.report === initial)
    }
}
