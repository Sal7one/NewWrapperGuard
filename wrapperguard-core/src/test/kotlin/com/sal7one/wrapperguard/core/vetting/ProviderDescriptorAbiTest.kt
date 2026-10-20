package com.sal7one.wrapperguard.core.vetting

import com.sal7one.wrapperguard.core.inputs.ProviderInputScope
import com.sal7one.wrapperguard.core.model.ScanReport
import com.sal7one.wrapperguard.core.model.ScanVerdict
import com.sal7one.wrapperguard.core.model.BaselineEntry
import com.sal7one.wrapperguard.core.policy.ScanPolicy
import com.sal7one.wrapperguard.core.policy.ScanSettings
import com.sal7one.wrapperguard.core.spi.ScannerEngine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64
import java.nio.file.Path
import kotlin.test.*

class ProviderDescriptorAbiTest {
    @Test fun `SDK bytecode compiled against release zero five constructs copies and registers without recompiling`() {
        // This fixture is deliberately NOT compiled by the current test build. It was
        // compiled against build/releases/0.5.0-rc.1/cli/lib/wrapperguard-core-0.5.0.jar,
        // SHA256 7e1401c73a0f22c3e5e2942d941aadd71ecc6a484fdf2565a2136a78acffc24e.
        // The adjacent .kt.source records its readable source. Changing current default
        // constructors, copy$default or factory JVM signatures makes this test fail.
        val bytes = javaClass.getResourceAsStream("/abi/LegacyProviderFixture.class.base64")!!.use {
            Base64.getDecoder().decode(it.readBytes().toString(Charsets.UTF_8).trim())
        }
        val loader = object : ClassLoader(javaClass.classLoader) {
            fun fixture(): Class<*> = defineClass("com.sal7one.wrapperguard.core.abi.LegacyProviderFixture", bytes, 0, bytes.size)
        }
        val fixture = loader.fixture()
        val instance = fixture.getDeclaredConstructor().newInstance()
        val original = fixture.getMethod("descriptor").invoke(instance) as ProviderDescriptor
        assertEquals(1, original.apiVersion)
        assertEquals("legacy.sdk", original.id)
        assertNull(original.category)
        assertEquals("Legacy copied", (fixture.getMethod("copied").invoke(instance) as ProviderDescriptor).displayName)
        assertEquals("legacy.sdk", (fixture.getMethod("command").invoke(instance) as CommandProviderConfiguration).id)
        assertEquals("Legacy command copied", (fixture.getMethod("copiedCommand").invoke(instance) as CommandProviderConfiguration).displayName)
        fixture.getMethod("validateConfiguration").invoke(instance)
        val engine = object : ScannerEngine {
            override fun scan(projectRoot: Path, policy: ScanPolicy, settings: ScanSettings, baseline: List<BaselineEntry>) =
                ScanReport(projectPath = projectRoot.toString(), verdict = ScanVerdict.CLEAN, policyName = policy.name,
                    findings = emptyList(), scannedFiles = 0, durationMs = 0)
        }
        val registrations = fixture.getMethod("registrations", ScannerEngine::class.java).invoke(instance, engine) as List<*>
        assertEquals(1, registrations.size)
    }

    @Test fun `additive metadata serializes and explicit copy preserves it without changing legacy copy ABI`() {
        val descriptor = ProviderDescriptor("custom", "Custom", "1").copyWithMetadata(category = "SAST", summary = "Checks source",
            tags = listOf("android"), inputScope = ProviderInputScope(listOf("**/*.kt")))
        val copied = descriptor.copyWithMetadata(displayName = "Renamed")
        assertEquals("SAST", copied.category)
        assertEquals(descriptor.inputScope, copied.inputScope)
        val decoded = Json.decodeFromString<ProviderDescriptor>(Json.encodeToString(copied))
        assertEquals("SAST", decoded.category)
        assertEquals("Checks source", decoded.summary)
        assertEquals(listOf("android"), decoded.tags)
        assertEquals(copied.inputScope, decoded.inputScope)
        assertEquals("Renamed", decoded.displayName)
    }
}
