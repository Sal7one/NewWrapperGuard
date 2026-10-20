package com.sal7one.wrapperguard.core.methods

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class MethodConfigurationTest {
    @Test fun `shared catalog has unique stable defaults and no external activation`() {
        val catalog = SecurityMethodCatalog.methods
        assertEquals(catalog.size, catalog.map { it.id }.distinct().size)
        assertEquals(6, catalog.count { it.defaultEnabled })
        assertTrue(SecurityMethodCatalog.builtInMethods.all { it.defaultEnabled })
        assertFalse(SecurityMethodCatalog.isEnabled(SecurityMethodIds.EXTERNAL_MODEL, emptyMap()))
        assertFalse(SecurityMethodCatalog.isEnabled(SecurityMethodIds.SCRIPT_RULES, mapOf(SecurityMethodIds.SCRIPT_RULES to false)))
    }

    @Test fun `method configuration requires explicit trusted file and known boolean flags`() {
        val root = Files.createTempDirectory("wg-method-root")
        val userFile = Files.createTempFile("wg-method-user", ".json")
        try {
            Files.writeString(userFile, """{"methodFlags":{"builtin.script-rules":false}}""")
            assertEquals(mapOf(SecurityMethodIds.SCRIPT_RULES to false), MethodConfiguration.load(userFile, root).methodFlags)
            val untrusted = root.resolve("methods.json")
            Files.copy(userFile, untrusted)
            assertFailsWith<IllegalArgumentException> { MethodConfiguration.load(untrusted, root) }
            assertFailsWith<IllegalArgumentException> { MethodConfiguration.load(Path.of("methods.json"), root) }
            Files.writeString(userFile, """{"methodFlags":{"builtin.typo":false}}""")
            assertFailsWith<IllegalArgumentException> { MethodConfiguration.load(userFile, root) }
            Files.writeString(userFile, """{"methodFlags":{"builtin.script-rules":"false"}}""")
            assertFailsWith<IllegalArgumentException> { MethodConfiguration.load(userFile, root) }
            Files.writeString(userFile, " ".repeat(65_537))
            assertFailsWith<IllegalArgumentException> { MethodConfiguration.load(userFile, root) }
        } finally { root.toFile().deleteRecursively(); Files.deleteIfExists(userFile) }
    }
}
