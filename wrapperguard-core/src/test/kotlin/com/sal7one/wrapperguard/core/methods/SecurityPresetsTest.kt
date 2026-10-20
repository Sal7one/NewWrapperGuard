package com.sal7one.wrapperguard.core.methods

import kotlin.test.*

class SecurityPresetsTest {
    @Test fun `packaged presets contain reviewed known method choices and preserve unrelated settings`() {
        val presets = SecurityPresets.presets
        assertEquals(setOf("android-review", "ktor-review", "offline-static", "python-powershell", "supply-chain"), presets.map { it.id }.toSet())
        val known = SecurityMethodCatalog.methods.map { it.id }.toSet()
        presets.forEach {
            assertEquals(known, it.methodFlags.keys)
            assertTrue(it.notice.isNotBlank())
            assertTrue(SecurityMethodCatalog.builtInMethods.all { method -> it.methodFlags[method.id] == true })
        }
        val original = mutableMapOf("thirdparty.custom" to true, "sast.bandit" to true)
        val offline = presets.single { it.id == "offline-static" }.applyTo(original)
        assertTrue(offline.getValue("thirdparty.custom"))
        assertFalse(offline.getValue("sast.bandit"))
        assertTrue(original.getValue("sast.bandit"))
    }
}
