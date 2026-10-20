package com.sal7one.wrapperguard.core.vetting

import com.sal7one.wrapperguard.core.inputs.InputSealLimits
import com.sal7one.wrapperguard.core.inputs.ProviderInputSealer
import com.sal7one.wrapperguard.core.methods.SecurityMethodCatalog
import com.sal7one.wrapperguard.core.model.ScanNotice
import com.sal7one.wrapperguard.core.policy.ScanPolicy
import com.sal7one.wrapperguard.core.policy.ScanSettings
import kotlinx.serialization.Serializable
import java.nio.file.Path

@Serializable
data class ProviderConformanceEntry(
    val id: String,
    val enabled: Boolean,
    val scopeDeclared: Boolean,
    val category: String? = null,
    val status: ProviderRunStatus? = null,
    val findingCount: Int = 0
)

@Serializable
data class ProviderConformanceResult(
    val schemaVersion: Int = 1,
    val mode: String,
    val valid: Boolean,
    val executed: Boolean,
    val providers: List<ProviderConformanceEntry>,
    val sealedFileCount: Int = 0,
    val sealedBytes: Long = 0,
    val notices: List<ScanNotice> = emptyList()
)

/** Configuration/input checks are passive. Runtime conformance is an explicit trusted-code SCAN. */
object ProviderConformance {
    fun check(
        registrations: List<ProviderRegistration>,
        projectRoot: Path,
        settings: ScanSettings = ScanSettings(),
        execute: Boolean = false,
        projectRoots: List<Path> = listOf(projectRoot),
        limits: InputSealLimits = InputSealLimits()
    ): ProviderConformanceResult {
        // Use the same identity, API, metadata, permissions and runtime validation as the hosts.
        val coordinator = VettingCoordinator(registrations)
        val entries = registrations.map { registration ->
            val descriptor = registration.provider.descriptor
            val enabled = registration.permissions.enabled &&
                (registration.provider !is CommandVettingProvider || registration.provider.appliesToProject(projectRoot)) && SecurityMethodCatalog.methods.none {
                it.providerId == descriptor.id && settings.methodFlags[it.id] == false
            }
            ProviderConformanceEntry(descriptor.id, enabled, descriptor.inputScope != null, descriptor.category)
        }
        val scopes = registrations.zip(entries).filter { it.second.enabled }.mapNotNull { it.first.provider.descriptor.inputScope?.snapshot() }
        val notices = entries.filter { it.enabled && !it.scopeDeclared && it.id != "wrapperguard" }.map {
            ScanNotice("WG-PROVIDER-SCOPE-UNKNOWN", "${it.id}: Provider input scope is not declared; freshness covers only the host-known inputs.")
        }.toMutableList()
        if (!execute) {
            val unavailable = registrations.zip(entries).filter { (registration, entry) ->
                entry.enabled && registration.provider is CommandVettingProvider &&
                    runCatching { registration.provider.validateReadiness() }.isFailure
            }.map { it.second.id }.toSet()
            if (unavailable.isNotEmpty()) return ProviderConformanceResult(mode = "configuration", valid = false,
                executed = false, providers = entries.map { if (it.id in unavailable) it.copy(status = ProviderRunStatus.FAILED) else it },
                notices = notices + unavailable.map { ScanNotice("WG-PROVIDER-NOT-READY", "$it: The configured tool is unavailable; repair its executable before scanning.") })
        }
        val before = try {
            ProviderInputSealer.capture(projectRoot, scopes, projectRoots = projectRoots, limits = limits)
        } catch (_: Exception) {
            return ProviderConformanceResult(mode = if (execute) "conformance" else "configuration", valid = false,
                executed = false, providers = entries, notices = notices + ScanNotice("WG-INPUT-SEAL-FAILED", "Declared inputs could not be inspected safely within the configured limits."))
        }
        if (!execute) return ProviderConformanceResult(mode = "configuration", valid = true, executed = false,
            providers = entries, sealedFileCount = before.fileCount, sealedBytes = before.totalBytes, notices = notices)
        val report = coordinator.scan(projectRoot, ScanPolicy(), settings, operation = VettingOperation.SCAN)
        val runs = report.providerRuns.associateBy { it.descriptor.id }
        var valid = entries.any { it.enabled } && report.providerRuns.filter { it.permissions.enabled }.all {
            it.status == ProviderRunStatus.COMPLETED && it.message == null
        } && report.notices.none { it.code in setOf("WG-INPUT-CHANGED", "WG-INPUT-SEAL-FAILED", "WG-METHOD-NOT-CONNECTED") }
        // Findings are valid conformance output; they do not make a provider incompatible.
        notices += report.notices.filter { it.code != "WG-PROVIDER-SCOPE-UNKNOWN" }
        try {
            val after = ProviderInputSealer.capture(projectRoot, scopes, projectRoots = projectRoots, limits = limits)
            if (before.hashes != after.hashes) {
                valid = false
                notices += ScanNotice("WG-INPUT-CHANGED", "Declared inputs changed during provider conformance; the result cannot establish freshness.")
            }
        } catch (_: Exception) {
            valid = false
            notices += ScanNotice("WG-INPUT-SEAL-FAILED", "Declared inputs could not be rechecked safely within the configured limits.")
        }
        if (entries.none { it.enabled }) notices += ScanNotice("WG-CONFORMANCE-NO-PROVIDERS", "No enabled providers were available for runtime conformance.")
        return ProviderConformanceResult(mode = "conformance", valid = valid, executed = true,
            providers = entries.map { entry -> entry.copy(status = runs[entry.id]?.status, findingCount = runs[entry.id]?.findingCount ?: 0) },
            sealedFileCount = before.fileCount, sealedBytes = before.totalBytes, notices = notices)
    }
}
