package com.sal7one.wrapperguard.core.vetting

import com.sal7one.wrapperguard.core.model.*
import com.sal7one.wrapperguard.core.policy.EnforcementMode
import com.sal7one.wrapperguard.core.policy.ScanPolicy
import com.sal7one.wrapperguard.core.policy.ScanSettings
import com.sal7one.wrapperguard.core.spi.ScannerEngine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ProviderRepairTest {
    private val root = Files.createTempDirectory("wg-repair-project-").toRealPath()
    private val user = Files.createTempDirectory("wg-repair-user-").toRealPath()
    private val missing = user.resolve("uninstalled-secret-tool")
    private val engine = object : ScannerEngine {
        override fun scan(projectRoot: Path, policy: ScanPolicy, settings: ScanSettings, baseline: List<BaselineEntry>) =
            ScanReport(projectPath = projectRoot.toString(), verdict = ScanVerdict.CLEAN, policyName = policy.name,
                findings = emptyList(), scannedFiles = 0, durationMs = 0)
    }
    private fun command(enabled: Boolean, actions: Set<VettingAction> = setOf(VettingAction.BLOCK_BUILD), required: Boolean = true) =
        CommandProviderConfiguration("repair.sdk", "Repairable SDK", "1", listOf(missing.toString()),
            ProviderPermissions(enabled = enabled, actions = actions, required = required))
    private fun load(command: CommandProviderConfiguration): ProviderConfiguration {
        val file = Files.writeString(user.resolve("providers.json"), Json.encodeToString(ProviderConfiguration(providers = listOf(command))))
        return ProviderConfiguration.load(file, root)
    }
    @AfterTest fun cleanup() { root.toFile().deleteRecursively(); user.toFile().deleteRecursively() }

    @Test fun `disabled uninstalled provider loads retains editable metadata and can be removed`() {
        val loaded = load(command(enabled = false))
        val entry = loaded.providers.single()
        assertEquals(missing.toString(), entry.command.single())
        val edited = loaded.copy(providers = listOf(entry.copy(displayName = "Renamed while disabled")))
        edited.validateMetadata()
        assertEquals("Renamed while disabled", edited.providers.single().displayName)
        loaded.copy(providers = emptyList()).validateMetadata()
        val report = VettingCoordinator(loaded.registrations(engine, root)).scan(root, ScanPolicy(), ScanSettings())
        assertTrue(report.complete)
        assertEquals(ProviderRunStatus.DISABLED, report.providerRuns.single { it.descriptor.id == entry.id }.status)
        assertTrue(ProviderConformance.check(loaded.registrations(engine, root), root).valid)
    }

    @Test fun `enabled missing executable fails per provider while other analysis completes`() {
        val loaded = load(command(enabled = true))
        val report = VettingCoordinator(loaded.registrations(engine, root)).scan(root,
            ScanPolicy(mode = EnforcementMode.ENFORCE), ScanSettings(), operation = VettingOperation.BUILD)
        assertFalse(report.complete)
        assertEquals(ProviderRunStatus.COMPLETED, report.providerRuns.first().status)
        assertEquals(ProviderRunStatus.FAILED, report.providerRuns.single { it.descriptor.id == "repair.sdk" }.status)
        assertTrue(report.blockers.any { it.ruleId == "WG-PROVIDER-UNAVAILABLE" && it.providerId == "repair.sdk" })
        assertFalse(report.toString().contains("uninstalled-secret-tool"))
        val passive = ProviderConformance.check(loaded.registrations(engine, root), root)
        assertFalse(passive.valid)
        assertFalse(passive.executed)
        assertTrue(passive.notices.any { it.code == "WG-PROVIDER-NOT-READY" })
        assertFalse(ProviderConformance.check(loaded.registrations(engine, root), root, execute = true).valid)
    }

    @Test fun `missing tool never invents required status grants or operation permissions`() {
        for ((permissions, operation, enforce) in listOf(
            Triple(ProviderPermissions(required = false, actions = setOf(VettingAction.BLOCK_BUILD)), VettingOperation.BUILD, true),
            Triple(ProviderPermissions(required = true, actions = setOf(VettingAction.WARNING)), VettingOperation.BUILD, true),
            Triple(ProviderPermissions(required = true, actions = setOf(VettingAction.BLOCK_BUILD)), VettingOperation.SYNC, true),
            Triple(ProviderPermissions(required = true, actions = setOf(VettingAction.BLOCK_BUILD)), VettingOperation.BUILD, false)
        )) {
            val configured = command(enabled = true).copy(permissions = permissions)
            val report = VettingCoordinator(load(configured).registrations(engine, root)).scan(root,
                ScanPolicy(mode = if (enforce) EnforcementMode.ENFORCE else EnforcementMode.WARN), ScanSettings(), operation = operation)
            assertFalse(report.complete)
            assertTrue(report.blockers.isEmpty())
            assertEquals(ProviderRunStatus.FAILED, report.providerRuns.last().status)
        }
    }

    @Test fun `repairing installed tool works without recreating provider and shape checks remain strict`() {
        val adapter = CommandVettingProvider(command(enabled = true))
        assertFailsWith<IllegalArgumentException> { adapter.scan(VettingRequest(root, ScanPolicy(), ScanSettings())) }
        Files.writeString(missing, "#!/bin/sh\nIFS= read -r request\nprintf '%s' '{\"findings\":[],\"scannedFiles\":1}'\n")
        assertTrue(missing.toFile().setExecutable(true))
        assertEquals(1, adapter.scan(VettingRequest(root, ScanPolicy(), ScanSettings())).scannedFiles)
        assertFailsWith<IllegalArgumentException> { load(command(false).copy(command = listOf("relative-tool"))) }
        assertFailsWith<IllegalArgumentException> { load(command(false).copy(id = "wrapperguard")) }
    }
}
