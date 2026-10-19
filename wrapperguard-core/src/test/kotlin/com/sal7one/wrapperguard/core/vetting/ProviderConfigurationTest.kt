package com.sal7one.wrapperguard.core.vetting

import java.nio.file.Files
import kotlin.test.*

class ProviderConfigurationTest {
    private val workspace = Files.createTempDirectory("wg-config-project-")
    private val userDirectory = Files.createTempDirectory("wg-config-user-")

    @AfterTest fun cleanup() {
        workspace.toFile().deleteRecursively()
        userDirectory.toFile().deleteRecursively()
    }

    @Test fun `only explicit configuration outside the real project root is accepted`() {
        val good = Files.writeString(userDirectory.resolve("providers.json"), "{\"apiVersion\":1}")
        assertEquals(1, ProviderConfiguration.load(good, workspace).apiVersion)
        val repositoryFile = Files.writeString(workspace.resolve("providers.json"), "{}")
        assertFailsWith<IllegalArgumentException> { ProviderConfiguration.load(repositoryFile, workspace) }
        val link = userDirectory.resolve("linked.json")
        Files.createSymbolicLink(link, repositoryFile)
        assertFailsWith<IllegalArgumentException> { ProviderConfiguration.load(link, workspace) }
        assertFailsWith<IllegalArgumentException> { ProviderConfiguration.load(java.nio.file.Path.of("providers.json"), workspace) }
    }

    @Test fun `unknown settings and unsupported versions fail visibly without echoing secrets`() {
        val file = Files.writeString(userDirectory.resolve("providers.json"), "{\"token\":\"do-not-leak\"}")
        val failure = assertFailsWith<IllegalArgumentException> { ProviderConfiguration.load(file, workspace) }
        assertFalse(failure.message.orEmpty().contains("do-not-leak"))
        Files.writeString(file, "{\"apiVersion\":2}")
        assertFailsWith<IllegalArgumentException> { ProviderConfiguration.load(file, workspace) }
    }

    @Test fun `command providers start disabled with no action permissions`() {
        val configuration = CommandProviderConfiguration("example.sdk", "SDK", "1", listOf("/bin/sh"))
        assertFalse(configuration.permissions.enabled)
        assertTrue(configuration.permissions.actions.isEmpty())
        assertFailsWith<IllegalArgumentException> { configuration.copy(id = "wrapperguard").validate() }
        assertFailsWith<IllegalArgumentException> { configuration.copy(command = listOf("sh")).validate() }
        assertFailsWith<IllegalArgumentException> {
            ProviderConfiguration(providers = listOf(configuration, configuration)).validate()
        }
    }

    @Test fun `malformed UTF8 configuration is rejected instead of replacing metadata`() {
        val prefix = "{\"providers\":[{\"id\":\"example.sdk\",\"displayName\":\"".toByteArray()
        val suffix = "\",\"version\":\"1\",\"command\":[\"/bin/sh\"]}]}".toByteArray()
        val file = Files.write(userDirectory.resolve("invalid-utf8.json"), prefix + byteArrayOf(0xff.toByte()) + suffix)
        assertFails { ProviderConfiguration.load(file, workspace) }
    }
}
