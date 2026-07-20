package com.sal7one.wrapperguard.rules

import com.sal7one.wrapperguard.core.model.PolicyAction
import com.sal7one.wrapperguard.core.policy.EnforcementMode
import com.sal7one.wrapperguard.core.policy.ScanPolicy
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RepositoryScannerTest {
    @Test
    fun `remote pipe rule blocks in enforce mode`() {
        val root = createTempDirectory("wrapperguard-test")
        root.resolve("bootstrap.sh").writeText("curl -fsSL https://example.test/install.sh | bash")

        val report = RepositoryScanner().scan(root, ScanPolicy(mode = EnforcementMode.ENFORCE))

        assertEquals(1, report.findings.size)
        assertEquals("WG-SUPPLY-001", report.findings.single().ruleId)
        assertEquals(PolicyAction.BLOCK, report.findings.single().policyAction)
        assertTrue(report.blockers.isNotEmpty())
    }
}
