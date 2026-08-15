package com.sal7one.wrapperguard.rules

import com.sal7one.wrapperguard.core.model.PolicyAction
import com.sal7one.wrapperguard.core.model.ScanVerdict
import com.sal7one.wrapperguard.core.model.Severity
import com.sal7one.wrapperguard.core.policy.EnforcementMode
import com.sal7one.wrapperguard.core.policy.ScanPolicy
import com.sal7one.wrapperguard.core.policy.ScanSettings
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WrapperJarEscalationTest {

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun fixture(): java.nio.file.Path {
        val root = Files.createTempDirectory("wg-jar-escalation")
        Files.createDirectories(root.resolve("gradle/wrapper"))
        root.resolve("gradle/wrapper/gradle-wrapper.properties")
            .writeText("distributionUrl=https\\://services.gradle.org/distributions/gradle-8.10-bin.zip\n")
        root.resolve("gradle/wrapper/gradle-wrapper.jar").writeBytes("test-jar-content".toByteArray())
        return root
    }

    @Test
    fun unknownJarIsEscalatedToErrorWhenTrustedListConfigured() {
        val root = fixture()
        val settings = ScanSettings(trustedWrapperChecksums = mapOf("gradle-wrapper.jar" to "0".repeat(64)))
        val report = RepositoryScanner().scan(root, ScanPolicy(mode = EnforcementMode.ENFORCE), settings)
        val unknown = report.findings.firstOrNull { it.ruleId == RuleIds.WRAPPER_JAR_UNKNOWN }
        assertNotNull(unknown, "unknown wrapper JAR must be escalated")
        assertEquals(Severity.ERROR, unknown.severity)
        assertEquals(PolicyAction.BLOCK, unknown.policyAction)
        assertEquals(ScanVerdict.EXECUTION_BLOCKED, report.verdict)
    }

    @Test
    fun matchingJarIsObservationOnly() {
        val root = fixture()
        val content = "test-jar-content".toByteArray()
        val settings = ScanSettings(trustedWrapperChecksums = mapOf("gradle-wrapper.jar" to sha256(content)))
        val report = RepositoryScanner().scan(root, ScanPolicy(mode = EnforcementMode.ENFORCE), settings)
        assertTrue(report.findings.none { it.ruleId == RuleIds.WRAPPER_JAR_UNKNOWN })
        assertTrue(report.findings.any { it.ruleId == RuleIds.WRAPPER_JAR_SHA256 })
    }

    @Test
    fun emptyTrustedListDoesNotEscalate() {
        val root = fixture()
        val report = RepositoryScanner().scan(root, ScanPolicy(mode = EnforcementMode.ENFORCE), ScanSettings())
        assertTrue(report.findings.none { it.ruleId == RuleIds.WRAPPER_JAR_UNKNOWN })
        assertTrue(report.findings.any { it.ruleId == RuleIds.WRAPPER_JAR_SHA256 })
    }
}
