package com.sal7one.wrapperguard.core.inputs

import java.nio.file.Files
import kotlin.test.*

class ProviderReadTrackerTest {
    private val root = Files.createTempDirectory("wg-actual-reads-").toRealPath()
    @AfterTest fun cleanup() { root.toFile().deleteRecursively() }

    @Test fun `observed unknown suffix content must match final bytes even with restored metadata`() {
        val file = Files.writeString(root.resolve("script.[custom]"), "good")
        val timestamp = Files.getLastModifiedTime(file)
        assertFailsWith<InputSealException> {
            ProviderReadTracker.verifyReads(root) {
                ProviderReadTracker.record(file, Files.readAllBytes(file))
                Files.writeString(file, "evil")
                Files.setLastModifiedTime(file, timestamp)
                "clean result"
            }
        }
        assertEquals("valid", ProviderReadTracker.verifyReads(root) { ProviderReadTracker.record(file, Files.readAllBytes(file)); "valid" })
    }

    @Test fun `deletion and two inconsistent reads cannot produce accepted result`() {
        val file = Files.writeString(root.resolve("extensionless"), "good")
        assertFailsWith<InputSealException> {
            ProviderReadTracker.verifyReads(root) { ProviderReadTracker.record(file, Files.readAllBytes(file)); Files.delete(file) }
        }
        assertFailsWith<InputSealException> {
            ProviderReadTracker.verifyReads(root) {
                ProviderReadTracker.record(file, "first".toByteArray())
                runCatching { ProviderReadTracker.record(file, "other".toByteArray()) }
                "provider attempted to swallow observation failure"
            }
        }
    }

    @Test fun `observers are scoped and bounded without retaining source data`() {
        val file = Files.writeString(root.resolve("input"), "12345")
        assertFailsWith<InputSealException> {
            ProviderReadTracker.verifyReads(root, InputSealLimits(maxFileBytes = 4)) {
                ProviderReadTracker.record(file, Files.readAllBytes(file))
            }
        }
        ProviderReadTracker.record(root.resolve("outside-observer"), ByteArray(10)) // no active observer
        val sentinel = IllegalStateException("callback failed")
        assertSame(sentinel, assertFailsWith<IllegalStateException> { ProviderReadTracker.verifyReads(root) { throw sentinel } })
        assertEquals(3, ProviderReadTracker.verifyReads(root) { 3 })
    }
}
