package com.sal7one.wrapperguard.rules

import com.sal7one.wrapperguard.core.inputs.ProviderReadTracker
import com.sal7one.wrapperguard.core.inputs.InputSealException
import java.nio.file.Files
import kotlin.test.*

class ProjectReadTrackingTest {
    @Test fun `project file reader records bytes for arbitrary recursive filenames`() {
        val root = Files.createTempDirectory("wg-rule-read-tracker-").toRealPath()
        try {
            val script = Files.writeString(root.resolve("custom.[script]"), "good")
            val timestamp = Files.getLastModifiedTime(script)
            assertFailsWith<InputSealException> {
                ProviderReadTracker.verifyReads(root) {
                    assertContentEquals("good".toByteArray(), ProjectFileAccess.read(root, script, 1024))
                    Files.writeString(script, "evil")
                    Files.setLastModifiedTime(script, timestamp)
                }
            }
        } finally { root.toFile().deleteRecursively() }
    }
}
