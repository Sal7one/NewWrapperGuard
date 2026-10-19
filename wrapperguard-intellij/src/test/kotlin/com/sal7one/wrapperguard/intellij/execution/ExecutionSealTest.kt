package com.sal7one.wrapperguard.intellij.execution

import com.sal7one.wrapperguard.core.policy.ScanSettings
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
