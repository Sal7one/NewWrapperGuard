package com.sal7one.wrapperguard.rules

import com.sal7one.wrapperguard.core.policy.ScanPolicy
import com.sal7one.wrapperguard.core.policy.ScanSettings
import com.sal7one.wrapperguard.core.spi.TextSource
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnixNetworkCommandsTest {
    private fun networkFindings(line: String) = ScriptRules.unix().analyze(
        TextSource(Path.of("setup.sh"), "tools/setup.sh", line), ScanSettings()
    ).filter { it.ruleId == RuleIds.NETWORK_COMMAND }

    @Test
    fun `color assignments quoted prose arguments and comments are not network commands`() {
        listOf(
            "NC='\\033[0m' # No Color", "export NC='\\033[0m'", "readonly NC=plain",
            "MESSAGE='nc host 80'", "MESSAGE=\"nc host 80\"", "printf '%s' 'nc'",
            "echo nc", "printf 'network nc curl wget'", "echo ok # nc host 80", "# nc host 80",
            "printf '%s' '# nc'", "printf '%s' '\$(nc host 80)'", "cat < nc", "echo foo#nc"
        ).forEach { assertTrue(networkFindings(it).isEmpty(), it) }
    }

    @Test
    fun `real command tokens wrappers separators and substitutions retain original finding evidence`() {
        listOf(
            "nc host 80", "'nc' host 80", "\"nc\" host 80", "n\\c host 80", "NC=plain nc host 80",
            "echo ok; nc host 80", "echo ok && nc host 80", "echo data | nc host 80",
            "if true; then nc host 80; fi", "env MODE=test nc host 80", "command -- nc host 80",
            "sudo nc host 80", "2>/dev/null nc host 80", "value=\$(nc host 80)",
            "printf '%s' \"\$(nc host 80)\"", "echo `nc host 80`", "(nc host 80)"
        ).forEach { line ->
            val finding = networkFindings(line).single()
            assertEquals(line, finding.evidence?.text, line)
            assertEquals(1, finding.location.line, line)
        }
    }

    @Test
    fun `deep command substitution stays bounded and conservatively reports a network command`() {
        val line = "\$(".repeat(40) + "nc host 80" + ")".repeat(40)
        assertEquals(1, networkFindings(line).size)
    }

    @Test
    fun `external nightly artifact path remains unresolved and incomplete`() {
        val root = createTempDirectory("wg-nightly-external-")
        try {
            root.resolve("nightly.sh").writeText("bash \$KOKORO_ARTIFACTS_DIR/git/nowinandroid/kokoro/build.sh \"walleye\" \"27\"\n")
            val report = RepositoryScanner().scan(root, ScanPolicy(), ScanSettings())
            assertFalse(report.complete)
            assertTrue(report.findings.any { it.ruleId == RuleIds.SCAN_UNRESOLVED })
            assertTrue(report.notices.any { it.code == RuleIds.SCAN_FILE_SKIPPED })
            assertTrue(report.edges.isEmpty())
        } finally { root.toFile().deleteRecursively() }
    }
}
