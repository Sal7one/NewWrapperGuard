package com.sal7one.wrapperguard.intellij.settings

import org.junit.Assert.*
import org.junit.Test

class NativeHistoryScopeTest {
    private val builtin = NativeHistoryProviderScope(builtIn = true)
    private val inputs = mapOf("project:src/Main.kt" to "source-a", "external:/user/profile.json" to "profile-a",
        "external:/user/report.json" to "results-a", "tool:external:/user/tool" to "binary-a")
    private fun scope(values: Map<String,String>) = nativeHistoryScope("policy", listOf(builtin), values, setOf("/user/report.json"))

    @Test fun profileConfigurationAndBinaryChangesInvalidateHistoryScopeButResultsAndSourceDoNot() {
        val before = scope(inputs)
        assertTrue(before.known)
        assertNotEquals(before.scopeId, scope(inputs + ("external:/user/profile.json" to "profile-b")).scopeId)
        assertNotEquals(before.scopeId, scope(inputs + ("external:/user/providers.json" to "configuration")).scopeId)
        assertNotEquals(before.scopeId, scope(inputs + ("tool:external:/user/tool" to "binary-b")).scopeId)
        assertEquals(before.scopeId, scope(inputs + ("project:src/Main.kt" to "source-b") + ("external:/user/report.json" to "results-b")).scopeId)
    }

    @Test fun declaredSdkPackageModelAndImportedProducerScopeRemainUnknown() {
        val direct = NativeHistoryProviderScope(declared = true, command = listOf("/user/checker"), binarySealed = true)
        assertTrue(direct.known())
        assertFalse(direct.copy(inProcessSdk = true).known())
        assertFalse(direct.copy(command = listOf("/user/python3.12", "-I", "-m", "vendor.checker")).known())
        assertFalse(direct.copy(managedMethod = "ai.external-model", runnerSealed = true).known())
        assertFalse(direct.copy(managedMethod = "sast.semgrep", runnerSealed = true).known())
        assertFalse(direct.copy(managedMethod = "sca.trivy", reportImport = true, runnerSealed = true).known())
        assertFalse(direct.copy(externalFileCount = 1).known())
        assertTrue(direct.copy(managedMethod = "sca.trivy", runnerSealed = true).known())
        assertFalse(direct.copy(managedMethod = "sca.osv", runnerSealed = false).known())
    }

    @Test fun missingSealAndRegistrationFailuresNeverCreateComparableScope() {
        assertFalse(nativeHistoryScope("policy", listOf(builtin), emptyMap(), emptySet()).known)
        assertFalse(nativeHistoryScope("policy", listOf(builtin), inputs, emptySet(), registrationKnown = false).known)
        assertFalse(nativeHistoryScope("policy", listOf(NativeHistoryProviderScope()), inputs, emptySet()).known)
    }
}
