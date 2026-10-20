package com.sal7one.wrapperguard.core.methods

import kotlin.test.*

class OrganizationPresetsTest {
    private fun preset() = OrganizationPresets.create("example.android", "Android baseline", "1.0.0", "Example team", mapOf("sast.bandit" to false), 1770000000000L)

    @Test fun `round trip validates identity digest and selections without authority`() {
        val original = preset()
        assertEquals(original, OrganizationPresets.decode(OrganizationPresets.encode(original)))
        val forged = OrganizationPresets.encode(original).replace("Android baseline", "Changed baseline")
        assertFailsWith<IllegalArgumentException> { OrganizationPresets.decode(forged) }
        val grants = OrganizationPresets.encode(original).trimEnd().dropLast(1) + ",\"permissions\":{\"actions\":[\"BLOCK_BUILD\"]}}"
        assertFailsWith<IllegalArgumentException> { OrganizationPresets.decode(grants) }
    }

    @Test fun `unknown methods and invalid versions cannot masquerade as organization policy`() {
        assertFailsWith<IllegalArgumentException> { OrganizationPresets.create("example.a", "A", "1.0.0", "Issuer", mapOf("unknown.method" to true)) }
        assertFailsWith<IllegalArgumentException> { OrganizationPresets.create("example.a", "A", "latest", "Issuer", mapOf("sast.bandit" to true)) }
        assertFailsWith<IllegalArgumentException> { OrganizationPresets.create("example.a", "A", "1.0.0", "Issuer\nSpoof", mapOf("sast.bandit" to true)) }
    }

    @Test fun `canonical keys follow cross host sorted JSON order`() {
        val input = kotlinx.serialization.json.Json.parseToJsonElement("{\"z\":true,\"a\":{\"b\":2,\"a\":1}}")
        assertEquals("{\"a\":{\"a\":1,\"b\":2},\"z\":true}", OrganizationPresets.canonical(input))
    }

    @Test fun `the VS Code organization fixture has the identical Kotlin digest`() {
        val source = java.nio.file.Path.of("../vscode-extension/tests/fixtures/organization-preset-v1.json")
        val preset = OrganizationPresets.read(source.toAbsolutePath())
        assertEquals("fda3700e05955537cbc71b8b2bcab10b88784cccddb92db25d50ab4950cd3c96", preset.digest)
        assertEquals("Acme Security", preset.issuer)
        assertEquals(mapOf("builtin.script-rules" to true, "sast.semgrep" to false), preset.methodFlags)
    }
}
