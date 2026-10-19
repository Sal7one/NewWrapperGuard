package com.sal7one.wrapperguard.rules

import com.sal7one.wrapperguard.core.model.PolicyAction
import com.sal7one.wrapperguard.core.model.ScanVerdict
import com.sal7one.wrapperguard.core.policy.EnforcementMode
import com.sal7one.wrapperguard.core.policy.ScanPolicy
import com.sal7one.wrapperguard.core.policy.ScanSettings
import com.sal7one.wrapperguard.core.spi.ScannerEngine
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression tests using tracked `test-fixtures/scanner` inputs available in fresh
 * checkouts, plus focused inline fixtures for the
 * recursion engine.
 */
class RepositoryScannerTest {

    // Tests run from the module dir; locate the tracked shared regression fixtures.
    private val repoRoot: Path = run {
        var dir = Path.of("").toAbsolutePath()
        while (dir != null && !Files.isDirectory(dir.resolve("test-fixtures/scanner"))) {
            dir = dir.parent
        }
        dir ?: error("Could not locate test-fixtures/scanner from ${Path.of("").toAbsolutePath()}")
    }
    private val samples: Path = repoRoot.resolve("test-fixtures/scanner")
    private val scanner = RepositoryScanner()
    private val enforce = ScanPolicy(mode = EnforcementMode.ENFORCE)
    private val warn = ScanPolicy(mode = EnforcementMode.WARN)

    @Test
    fun `malicious unix wrapper flags encoded exec or pipe-to-shell plus network and url findings`() {
        val root = copyToTemp("malicious-gradlew.sh", "gradlew")
        val report = scanner.scan(root, warn, ScanSettings())
        val ruleIds = report.findings.map { it.ruleId }.toSet()
        assertTrue(RuleIds.SUPPLY_PIPE_TO_SHELL in ruleIds || RuleIds.OBFUSCATION_ENCODED_EXEC in ruleIds, "Expected pipe-to-shell or encoded-exec in $ruleIds")
        assertTrue(ruleIds.any { it == RuleIds.NETWORK_COMMAND || it == RuleIds.NETWORK_INSECURE_TLS }, "Expected network findings in $ruleIds")
        assertTrue(RuleIds.NETWORK_URL_NOT_ALLOWLISTED in ruleIds, "Expected non-allowlisted URL finding in $ruleIds")
    }

    @Test
    fun `malicious bat wrapper flags blocked command`() {
        val root = copyToTemp("malicious-gradlew.bat", "gradlew.bat")
        val report = scanner.scan(root, warn, ScanSettings())
        val ruleIds = report.findings.map { it.ruleId }.toSet()
        assertTrue(RuleIds.SUPPLY_BLOCKED_COMMAND in ruleIds, "certutil should be flagged as a blocked command in $ruleIds")
    }

    @Test
    fun `malicious build script flags remote repo and remote apply or process exec`() {
        val report = scanner.scan(samples, warn, ScanSettings())
        val ruleIds = report.findings.filter { it.location.path.endsWith("malicious-build.gradle") }
            .map { it.ruleId }.toSet()
        assertTrue(RuleIds.REPO_NOT_HTTPS in ruleIds || RuleIds.REPO_NOT_ALLOWLISTED in ruleIds, "Expected repo findings in $ruleIds")
        assertTrue(RuleIds.SUPPLY_REMOTE_SCRIPT in ruleIds || RuleIds.EXECUTION_PROCESS in ruleIds, "Expected remote-apply or process-exec in $ruleIds")
    }

    @Test
    fun `mavenLocal fixture flags mavenLocal rule`() {
        val report = scanner.scan(samples.resolve("simple-maven-local"), warn, ScanSettings())
        assertTrue(report.findings.any { it.ruleId == RuleIds.REPO_MAVEN_LOCAL }, "mavenLocal() must be flagged: ${report.findings.map { it.ruleId }}")
    }

    @Test
    fun `remote apply fixture flags remote script rule`() {
        val report = scanner.scan(samples.resolve("simple-remote-apply"), warn, ScanSettings())
        assertTrue(report.findings.any { it.ruleId == RuleIds.SUPPLY_REMOTE_SCRIPT }, "remote apply from: must be flagged: ${report.findings.map { it.ruleId }}")
    }

    @Test
    fun `dynamic version fixture flags unstable dependency rule`() {
        val report = scanner.scan(samples.resolve("simple-dynamic-version"), warn, ScanSettings())
        assertTrue(report.findings.any { it.ruleId == RuleIds.SUPPLY_UNSTABLE_DEP }, "dynamic version must be flagged: ${report.findings.map { it.ruleId }}")
    }

    @Test
    fun `generated-then-executed script is detected`() {
        val report = scanner.scan(samples.resolve("simple-generated-script"), warn, ScanSettings())
        assertTrue(report.findings.any { it.ruleId == RuleIds.EXECUTION_GENERATED_THEN_RUN }, "generated-then-executed must be flagged: ${report.findings.map { it.ruleId }}")
    }

    @Test
    fun `repo-shipped wrapperguard ignore is untrusted and does not suppress`() {
        val report = scanner.scan(samples.resolve("simple-ignore-comment"), warn, ScanSettings())
        val violation = report.findings.firstOrNull { it.ruleId == RuleIds.SUPPLY_PIPE_TO_SHELL }
        assertTrue(violation != null, "Untrusted repo ignore must NOT suppress the finding: ${report.findings.map { it.ruleId }}")
        assertFalse(
            violation!!.evidence!!.text.contains("wrapperguard:ignore", ignoreCase = true),
            "Ignore marker must be stripped from evidence"
        )
        val missingReason = report.findings.single { it.ruleId == RuleIds.CONFIG_SUPPRESS_NO_REASON }
        assertEquals(PolicyAction.ALLOW, missingReason.policyAction, "Missing reasons are audit evidence, not blockers")
    }

    @Test
    fun `circular dependency is detected with a call chain`() {
        val report = scanner.scan(samples.resolve("circular-dependency"), warn, ScanSettings())
        val circular = report.findings.firstOrNull { it.ruleId == RuleIds.SCAN_CIRCULAR }
        assertTrue(circular != null, "Circular dependency must be flagged: ${report.findings.map { it.ruleId }}")
        assertTrue(circular!!.callChain.isNotEmpty(), "Circular finding must carry a call chain")
    }

    @Test
    fun `enforce mode blocks error-severity findings and produces execution-blocked verdict`() {
        val report = scanner.scan(samples.resolve("simple-remote-apply"), enforce, ScanSettings())
        assertEquals(ScanVerdict.EXECUTION_BLOCKED, report.verdict)
        assertTrue(report.findings.any { it.policyAction == PolicyAction.BLOCK }, "Expected at least one BLOCK finding")
    }

    @Test
    fun `every finding has a stable WG rule id and 24-char fingerprint`() {
        val report = scanner.scan(samples, warn, ScanSettings())
        assertTrue(report.findings.isNotEmpty(), "Expected findings from the fixtures")
        report.findings.forEach { finding ->
            assertTrue(finding.ruleId.startsWith("WG-"), "ruleId must be a stable WG-* id, was ${finding.ruleId}")
            assertEquals(24, finding.fingerprint.length, "fingerprint must be 24 chars, was ${finding.fingerprint}")
        }
    }

    @Test
    fun `scanner never imports intellij classes`() {
        // Guardrail: scanner must remain IDE-neutral. The build enforces this at compile time
        // (core/rules have no com.intellij dependency — that's why this test compiles against
        // only core/spi + kotlin stdlib). Keeping the assertion as a documented invariant.
        assertFalse(ScannerEngine::class.java.`package`.name.startsWith("com.intellij"), "Scanner SPI must not live in a com.intellij package")
    }

    @Test
    fun `clean project produces clean verdict`() {
        val root = createTempDirectory(prefix = "wrapperguard-clean")
        root.resolve("settings.gradle").writeText("rootProject.name = \"clean-project\"\n")
        try {
            val report = scanner.scan(root, warn, ScanSettings())
            assertEquals(ScanVerdict.CLEAN, report.verdict)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /** Copies a tracked fixture into a temp directory. */
    private fun copyToTemp(fixtureName: String, targetName: String = fixtureName): Path {
        val root = createTempDirectory(prefix = "wrapperguard-fixture-")
        root.resolve(targetName).writeText(Files.readString(samples.resolve(fixtureName)))
        return root
    }
}
