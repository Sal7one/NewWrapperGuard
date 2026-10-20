package com.sal7one.wrapperguard.core.inputs

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.*

class ProviderInputSealerTest {
    private val root = Files.createTempDirectory("wg-input-project-").toRealPath()
    private val outside = Files.createTempDirectory("wg-input-external-").toRealPath()
    private val kotlinScope = ProviderInputScope(includes = listOf("**/*.kt"), excludes = listOf("**/build/**"))

    @AfterTest fun cleanup() { root.toFile().deleteRecursively(); outside.toFile().deleteRecursively() }
    private fun put(relative: String, value: String = "data"): Path = root.resolve(relative).also { Files.createDirectories(it.parent); Files.writeString(it, value) }
    private fun capture(scope: ProviderInputScope = kotlinScope, limits: InputSealLimits = InputSealLimits()) =
        ProviderInputSealer.capture(root, listOf(scope), limits = limits)

    @Test fun `portable globs match root files and nested files with exclusions winning`() {
        listOf("Main.kt", "src/main/Main.kt").forEach { assertTrue(ProviderInputSealer.matchesProjectPath(it, kotlinScope)) }
        listOf("Main.java", "build/Main.kt", "app/build/generated/Main.kt", "../Main.kt", "/Main.kt").forEach {
            assertFalse(ProviderInputSealer.matchesProjectPath(it, kotlinScope), it)
        }
        assertTrue(ProviderInputSealer.matchesProjectPath("a.kt", ProviderInputScope(listOf("?.kt"))))
        assertFalse(ProviderInputSealer.matchesProjectPath("ab.kt", ProviderInputScope(listOf("?.kt"))))
        assertFalse(ProviderInputSealer.matchesProjectPath("src/Main.kt", ProviderInputScope(listOf("**"), listOf("src"))))
        assertFalse(ProviderInputSealer.matchesProjectPath("Main.kt", ProviderInputScope()))
    }

    @Test fun `nonportable or ambiguous globs are rejected`() {
        listOf("", "/src/*.kt", "src//*.kt", "../*.kt", "./*.kt", "src/../*.kt", "src\\*.kt", "src/**a", "[ab].kt", "{a,b}.kt", "!build", "C:/a").forEach {
            assertFailsWith<IllegalArgumentException>(it) { ProviderInputScope(listOf(it)).validate() }
        }
        assertFailsWith<IllegalArgumentException> { ProviderInputScope(listOf("*.kt", "*.kt")).validate() }
    }

    @Test fun `content hashes detect same-size restored-timestamp changes additions and removals`() {
        val source = put("Main.kt", "good")
        put("build/Ignored.kt", "generated")
        put("irrelevant.txt", "ignore")
        val before = capture()
        assertEquals(setOf("project:Main.kt"), before.hashes.keys)
        assertEquals(4, before.totalBytes)
        val timestamp = Files.getLastModifiedTime(source)
        Files.writeString(source, "evil")
        Files.setLastModifiedTime(source, timestamp)
        assertNotEquals(before.hashes, capture().hashes)
        val changed = capture()
        put("src/New.kt")
        assertNotEquals(changed.hashes, capture().hashes)
        Files.delete(source)
        assertFalse(capture().hashes.containsKey("project:Main.kt"))
    }

    @Test fun `external files are content sealed but never allowed inside another project`() {
        val report = Files.writeString(outside.resolve("report.json"), "report")
        val scope = ProviderInputScope(externalFiles = listOf(report.toString()))
        val result = capture(scope)
        assertEquals(setOf("external:$report"), result.hashes.keys)
        assertEquals(6, result.totalBytes)
        assertFailsWith<InputSealException> {
            ProviderInputSealer.capture(root, listOf(scope), projectRoots = listOf(root, outside))
        }
        assertFailsWith<InputSealException> { capture(ProviderInputScope(externalFiles = listOf(put("rules.yml").toString()))) }
        assertFailsWith<InputSealException> { capture(ProviderInputScope(externalFiles = listOf(outside.resolve("missing").toString()))) }
    }

    @Test fun `outside symlink files and linked directory sources are never read`() {
        val secret = Files.writeString(outside.resolve("secret.kt"), "sensitive")
        Files.createSymbolicLink(root.resolve("Main.kt"), secret)
        assertFailsWith<InputSealException> { capture() }
        Files.delete(root.resolve("Main.kt"))
        Files.createSymbolicLink(root.resolve("src"), outside)
        assertFailsWith<InputSealException> { capture() }
        Files.delete(root.resolve("src"))
        Files.createSymbolicLink(outside.resolve("linked.kt"), secret)
        assertFailsWith<InputSealException> { capture(ProviderInputScope(externalFiles = listOf(outside.resolve("linked.kt").toString()))) }
        Files.createSymbolicLink(outside.resolve("linked-dir"), outside)
        assertFailsWith<InputSealException> { capture(ProviderInputScope(externalFiles = listOf(outside.resolve("linked-dir/secret.kt").toString()))) }
    }

    @Test fun `excluded irrelevant trees do not consume source reads`() {
        put("Main.kt")
        Files.createSymbolicLink(root.resolve("build"), outside)
        assertEquals(1, capture().fileCount)
        // Another provider can independently include the excluded tree; union scopes cannot hide it.
        assertFailsWith<InputSealException> {
            ProviderInputSealer.capture(root, listOf(kotlinScope, ProviderInputScope(listOf("build/**"))))
        }
    }

    @Test fun `file count bytes traversal and depth limits fail closed`() {
        put("Main.kt", "12345")
        assertFailsWith<InputSealException> { capture(limits = InputSealLimits(maxFileBytes = 4)) }
        assertFailsWith<InputSealException> { capture(limits = InputSealLimits(maxTotalBytes = 4)) }
        put("Other.kt")
        assertFailsWith<InputSealException> { capture(limits = InputSealLimits(maxFiles = 1)) }
        assertFailsWith<InputSealException> { capture(limits = InputSealLimits(maxVisitedEntries = 1)) }
        put("a/b/Main.kt")
        assertFailsWith<InputSealException> { capture(limits = InputSealLimits(maxDirectoryDepth = 1)) }
        val report = Files.writeString(outside.resolve("large-report"), "12345")
        assertFailsWith<InputSealException> {
            capture(ProviderInputScope(externalFiles = listOf(report.toString())), InputSealLimits(maxExternalFileBytes = 4))
        }
    }

    @Test fun `cancellation remains cancellation and deadline is bounded`() {
        val sentinel = IllegalStateException("cancelled")
        assertSame(sentinel, assertFailsWith<IllegalStateException> {
            ProviderInputSealer.capture(root, listOf(kotlinScope), checkCancelled = { throw sentinel })
        })
        assertFailsWith<InputSealException> {
            ProviderInputSealer.capture(root, listOf(kotlinScope), limits = InputSealLimits(timeoutMs = 1), checkCancelled = { Thread.sleep(5) })
        }
    }

    @Test fun `unreadable relevant directory fails while excluded unreadable directory is ignored`() {
        val directory = Files.createDirectories(root.resolve("src"))
        put("src/Main.kt")
        if (!Files.getFileStore(directory).supportsFileAttributeView("posix")) return
        val permissions = Files.getPosixFilePermissions(directory)
        try {
            Files.setPosixFilePermissions(directory, emptySet<PosixFilePermission>())
            if (Files.isReadable(directory)) return // Elevated environments cannot reproduce this boundary.
            assertFailsWith<InputSealException> { capture() }
            assertEquals(0, capture(ProviderInputScope(listOf("**/*.kt"), listOf("src/**"))).fileCount)
        } finally { Files.setPosixFilePermissions(directory, permissions) }
    }
}
