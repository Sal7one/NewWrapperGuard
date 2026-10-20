package com.sal7one.wrapperguard.intellij.execution

import com.sal7one.wrapperguard.core.policy.ScanSettings
import com.sal7one.wrapperguard.intellij.WrapperGuardProjectService
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files

class ExecutionSealTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun hashesTrackWatchedContentAndIgnoreUnwatchedFiles() {
        val root = temporary.newFolder("project").toPath()
        val script = Files.writeString(root.resolve("gradlew"), "echo first\n")
        Files.writeString(root.resolve("README.md"), "first")
        val initial = watchedFileHashes(root, ScanSettings())
        Files.writeString(root.resolve("README.md"), "second")
        assertEquals(initial, watchedFileHashes(root, ScanSettings()))
        Files.writeString(script, "echo second\n")
        assertNotEquals(initial, watchedFileHashes(root, ScanSettings()))
    }

    @Test fun sourceAndDependencyEditsAdditionsAndDeletionChangeSealRegardlessOfMethodFlags() {
        val root = temporary.newFolder("source-inputs").toPath()
        val inputs = listOf("src/PaymentRoute.kt", "src/LegacyClient.JAVA", "tools/audit.PY", "scripts/module.psm1",
            "pom.xml", "gradle.lockfile", "gradle/dependency-locks/runtimeClasspath.lockfile", "package-lock.json", "uv.lock")
        inputs.forEach { relative ->
            val path = root.resolve(relative); Files.createDirectories(path.parent); Files.writeString(path, "first")
        }
        val settings = ScanSettings(methodFlags = mapOf("builtin.build-rules" to false, "sast.semgrep" to false))
        var previous = watchedFileHashes(root, settings)
        assertEquals(inputs.toSet(), previous.keys)
        inputs.forEach { relative ->
            Files.writeString(root.resolve(relative), "changed")
            val current = watchedFileHashes(root, settings)
            assertNotEquals("Changing $relative must invalidate the execution seal", previous, current)
            previous = current
        }
        val added = Files.writeString(root.resolve("new-source.kt"), "new")
        val withAdded = watchedFileHashes(root, settings)
        assertNotEquals(previous, withAdded)
        Files.delete(added)
        assertEquals(previous, watchedFileHashes(root, settings))
        Files.delete(root.resolve("pom.xml"))
        assertNotEquals(previous, watchedFileHashes(root, settings))
    }

    @Test fun dependencyAndSourcePredicateIncludesBundledRunnerInputsWithoutClaimingAllFiles() {
        val names = listOf("PaymentRoute.kt", "init.kts", "Client.java", "audit.py", "module.psm1", "module.psd1",
            "test.bash", "test.zsh", "test.bats", "pom.xml", "verification-metadata.xml", "requirements.txt",
            "requirements-dev.txt", "poetry.lock", "Pipfile.lock", "pdm.lock", "uv.lock", "pyproject.toml",
            "package-lock.json", "npm-shrinkwrap.json", "pnpm-lock.yaml", "yarn.lock", "bun.lock", "go.mod", "go.sum",
            "Cargo.lock", "composer.lock", "Gemfile.lock", "packages.lock.json")
        names.forEach { name ->
            assertTrue("Must watch $name", WrapperGuardProjectService.isWatchedFileName(name))
            assertTrue("Source suffixes and names are case insensitive: $name", WrapperGuardProjectService.isWatchedFileName(name.uppercase(java.util.Locale.ROOT)))
        }
        assertFalse(WrapperGuardProjectService.isWatchedFileName("README.md"))
        assertFalse(WrapperGuardProjectService.isWatchedFileName("logo.png"))
    }

    @Test fun newSourceInputsRetainSizeCapsAndExternalSymlinkRejection() {
        val root = temporary.newFolder("bounded-sources").toPath()
        val source = root.resolve("PaymentRoute.kt")
        Files.writeString(source, "12345")
        assertThrows(IOException::class.java) { watchedFileHashes(root, ScanSettings(maxFileBytes = 4)) }
        Files.delete(source)
        val outside = temporary.newFile("outside.py").toPath()
        Files.createSymbolicLink(root.resolve("audit.py"), outside)
        assertThrows(IOException::class.java) { watchedFileHashes(root, ScanSettings()) }
    }

    @Test fun excludedTreesArePrunedBeforeDepthAndFileLimits() {
        val root = temporary.newFolder("excluded").toPath()
        Files.writeString(root.resolve("gradlew"), "echo clean\n")
        val excluded = Files.createDirectories(root.resolve("node_modules/deep/tree"))
        RandomAccessFile(excluded.resolve("large.gradle").toFile(), "rw").use { it.setLength(2_000_000) }
        assertEquals(setOf("gradlew"), watchedFileHashes(root, ScanSettings(maxDirectoryDepth = 1)).keys)
    }

    @Test fun oversizedWatchedFileCannotProduceSuccessfulSeal() {
        val root = temporary.newFolder("oversized").toPath()
        RandomAccessFile(root.resolve("gradlew").toFile(), "rw").use { it.setLength(1_000_001) }
        assertThrows(IOException::class.java) { watchedFileHashes(root, ScanSettings()) }
    }

    @Test fun watchedSymlinksMustResolveToRegularFilesInsideProject() {
        val root = temporary.newFolder("links").toPath()
        val outside = temporary.newFile("outside.sh").toPath()
        val link = Files.createSymbolicLink(root.resolve("gradlew"), outside)
        assertThrows(IOException::class.java) { watchedFileHashes(root, ScanSettings()) }
        Files.delete(link)
        val target = Files.writeString(root.resolve("wrapper.txt"), "echo clean\n")
        Files.createSymbolicLink(link, target)
        val initial = watchedFileHashes(root, ScanSettings())
        Files.writeString(target, "echo changed\n")
        assertNotEquals(initial, watchedFileHashes(root, ScanSettings()))
    }

    @Test fun traversalAndWatchedFileCapsFailInsteadOfTruncatingSeal() {
        val root = temporary.newFolder("limits").toPath()
        Files.writeString(root.resolve("gradlew"), "echo clean\n")
        Files.writeString(root.resolve("build.gradle"), "// clean\n")
        assertThrows(IOException::class.java) { watchedFileHashes(root, ScanSettings(maxFilesPerScan = 1)) }
        Files.createDirectories(root.resolve("deep/tree"))
        assertThrows(IOException::class.java) { watchedFileHashes(root, ScanSettings(maxDirectoryDepth = 1)) }
    }

    @Test fun missingInputsAndCancellationCannotProduceSuccessfulSeal() {
        val root = temporary.newFolder("missing").toPath()
        Files.writeString(root.resolve("gradlew"), "echo clean\n")
        assertThrows(IOException::class.java) { watchedFileHashes(root.resolve("absent"), ScanSettings()) }
        assertThrows(IllegalStateException::class.java) {
            watchedFileHashes(root, ScanSettings()) { error("cancelled") }
        }
    }
}
