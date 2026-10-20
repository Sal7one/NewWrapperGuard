package com.sal7one.wrapperguard.core.vetting

import com.sal7one.wrapperguard.core.inputs.ProviderInputScope
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

    @Test fun `metadata and explicit scopes remain additive API one without implicit grants`() {
        val file = Files.writeString(userDirectory.resolve("custom.json"), """{
            "providers":[{"id":"team.custom","displayName":"Team scanner","version":"1",
            "command":["/bin/sh"],"category":"CUSTOM_REVIEW","summary":"Organization analysis",
            "tags":["android","internal"],"inputScope":{"includes":["**/*.kt"],"excludes":["**/build/**"]}}]
        }""")
        val config = ProviderConfiguration.load(file, workspace).providers.single()
        assertEquals("CUSTOM_REVIEW", config.category)
        assertEquals(listOf("android", "internal"), config.tags)
        assertEquals(listOf("**/*.kt"), config.inputScope?.includes)
        assertFalse(config.permissions.enabled)
        assertTrue(config.permissions.actions.isEmpty())
        assertEquals(config.descriptor(), CommandVettingProvider(config).descriptor)
        assertNull(CommandProviderConfiguration("legacy", "Legacy", "1", listOf("/bin/sh")).inputScope)
        assertEquals(ProviderInputScope(), config.copy(inputScope = ProviderInputScope()).descriptor().inputScope)
        listOf(config.copy(category = "BUILT_IN"), config.copy(category = "lowercase"), config.copy(tags = listOf("bad tag")),
            config.copy(summary = "secret\nline"), config.copy(inputScope = ProviderInputScope(listOf("../*.kt")))).forEach {
            assertFailsWith<IllegalArgumentException> { it.validate() }
        }
    }

    @Test fun `trusted config outside selected root must also be outside sibling projects`() {
        val config = Files.writeString(userDirectory.resolve("providers.json"), "{}")
        assertFailsWith<IllegalArgumentException> { ProviderConfiguration.load(config, workspace, listOf(workspace, userDirectory)) }
    }

    @Test fun `bound provider from another project does not invalidate reusable global configuration`() {
        val configuration = CommandProviderConfiguration("other.project", "Other project", "1", listOf("/missing/other/project/tool"),
            ProviderPermissions(enabled = true), projectRoots = listOf(userDirectory.toString()))
        assertFalse(configuration.appliesToProject(workspace))
        assertTrue(configuration.appliesToProject(userDirectory))
        ProviderConfiguration(providers = listOf(configuration)).validate(workspace)
        assertFailsWith<IllegalArgumentException> { ProviderConfiguration(providers = listOf(configuration)).validate(userDirectory) }
        assertFailsWith<IllegalArgumentException> { configuration.copy(projectRoots = listOf("relative")).validate(false) }
        assertFalse(configuration.copy(projectRoots = listOf(userDirectory.resolve("removed").toString())).appliesToProject(workspace))
    }
}
