package com.sal7one.wrapperguard.intellij.vetting

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.sal7one.wrapperguard.core.model.ScanNotice
import com.sal7one.wrapperguard.core.model.ScanReport
import com.sal7one.wrapperguard.core.policy.ScanPolicy
import com.sal7one.wrapperguard.core.vetting.*
import com.sal7one.wrapperguard.intellij.settings.WrapperGuardSettings
import com.sal7one.wrapperguard.intellij.settings.buildScanSettings
import com.sal7one.wrapperguard.intellij.settings.OptionalMethodConnections
import com.sal7one.wrapperguard.intellij.demo.WrapperGuardDemo
import com.sal7one.wrapperguard.rules.RepositoryScanner
import java.nio.file.Path

/**
 * SDK plugins depend on com.sal7one.wrapperguard.next and register their VettingProvider at
 * <vettingProvider implementation="vendor.MyProvider"/> in that plugin's extension namespace.
 * Providers are disabled until the user enables them and grants actions in IDE settings.
 * This API shares the same contract as CLI/command providers; it does not sandbox SDK code.
 */
object IntellijVettingProviders {
    @JvmField
    val EP_NAME: ExtensionPointName<VettingProvider> =
        ExtensionPointName.create("com.sal7one.wrapperguard.next.vettingProvider")

    fun installedProviders(): List<VettingProvider> = if (WrapperGuardDemo.enabled) emptyList() else EP_NAME.extensionList

    fun scan(project: Project, root: Path, policy: ScanPolicy, operation: VettingOperation): ScanReport {
        if (WrapperGuardDemo.enabled) return WrapperGuardDemo.loadReport()
        val settings = WrapperGuardSettings.getInstance()
        val trusted = TrustedProjects.isProjectTrusted(project)
        val scanner = RepositoryScanner()
        val path = settings.state.providerConfigurationPath.trim()
        val configured = if (path.isEmpty()) null else ProviderConfiguration.load(Path.of(path), root)
        val registrations = (configured?.registrations(scanner) ?: listOf(
            ProviderRegistration(BuiltInVettingProvider(scanner), settings.permissions("wrapperguard"))
        )).map { registration ->
            if (settings.state.providers.any { it.id == registration.provider.descriptor.id && it.overrideConfiguredPermissions })
                registration.copy(permissions = settings.permissions(registration.provider.descriptor.id))
            else registration
        }.toMutableList()
        // In Safe Mode we do not even initialize third-party SDK extensions.
        if (trusted) installedProviders().forEach { provider ->
            registrations += ProviderRegistration(provider, settings.permissions(provider.descriptor.id))
        }
        if (trusted) {
            val preferredIds = settings.state.methodConnections.filter { connection -> connection.preferManaged &&
                settings.state.methodFlags[connection.methodId] != false }.mapNotNull { connection ->
                com.sal7one.wrapperguard.core.methods.SecurityMethodCatalog.find(connection.methodId)?.providerId
                    ?.takeIf { settings.permissions(it).enabled }
            }.toSet()
            registrations.removeAll { it.provider.descriptor.id in preferredIds }
            val existing = registrations.map { it.provider.descriptor.id }.toSet()
            OptionalMethodConnections.registrations(root, settings.state, existing).filterNot { it.provider.descriptor.id in existing }.forEach { registrations += it }
        }
        val effective = if (trusted) registrations else registrations.map { registration ->
            if (registration.provider is BuiltInVettingProvider) registration
            else registration.copy(permissions = registration.permissions.copy(enabled = false))
        }
        val report = VettingCoordinator(effective).scan(root, policy, buildScanSettings(), operation = operation)
        return if (trusted) report else report.copy(
            providerRuns = report.providerRuns.map { run ->
                if (run.descriptor.id == "wrapperguard") run
                else run.copy(message = "Paused in Safe Mode; third-party provider code was not invoked.")
            },
            notices = report.notices + ScanNotice(
                "WG-PROVIDERS-UNTRUSTED", "Third-party providers are paused in Safe Mode. Only enabled built-in static analysis may run."
            )
        )
    }
}

internal fun VettingAction.displayName(): String = when (this) {
    VettingAction.WARNING -> "Warning"
    VettingAction.MAJOR_WARNING -> "Major warning"
    VettingAction.ALERT -> "Alert"
    VettingAction.BLOCK_BUILD -> "Stop build / run"
    VettingAction.BLOCK_SYNC -> "Stop Gradle sync"
}
