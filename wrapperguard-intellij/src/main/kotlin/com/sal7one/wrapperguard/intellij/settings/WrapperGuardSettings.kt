package com.sal7one.wrapperguard.intellij.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import com.sal7one.wrapperguard.core.policy.ScanSettings
import com.sal7one.wrapperguard.core.vetting.BuiltInVettingProvider
import com.sal7one.wrapperguard.core.vetting.ProviderPermissions
import com.sal7one.wrapperguard.core.vetting.VettingAction

/**
 * Plugin-wide settings. Only these user-owned inputs feed the scanner:
 *  - additional allowed hosts (one per line in the settings UI),
 *  - whether gradle-wrapper.properties must pin distributionSha256Sum,
 *  - scan-on-open and rescan-on-change toggles.
 *
 * Repo content can never modify these values (trust boundary: user settings only).
 */
@State(name = "WrapperGuardSettings", storages = [Storage("wrapperguard.xml")])
class WrapperGuardSettings : PersistentStateComponent<WrapperGuardSettings.State> {

    data class State(
        var allowedHostsText: String = "",
        var requireDistributionSha256: Boolean = false,
        var scanOnOpen: Boolean = true,
        var scanOnFileChange: Boolean = true,
        var providerConfigurationPath: String = "",
        var providers: MutableList<ProviderState> = mutableListOf(),
        var methodFlags: MutableMap<String, Boolean> = mutableMapOf(),
        var methodConnections: MutableList<MethodConnectionState> = mutableListOf(),
        var managedProviderConfigurationPath: String = "",
        var managedSdkPython: String = "",
        var customProviders: MutableList<CustomProviderState> = mutableListOf(),
        var managedArtifacts: MutableList<ManagedArtifactState> = mutableListOf(),
        var automation: ScanAutomationState = ScanAutomationState(),
        var historyEnabled: Boolean = false,
        var historyLimit: Int = 20
    )

    /** Application settings live outside the scanned project and are the SDK grant authority. */
    data class ProviderState(
        var id: String = "",
        var enabled: Boolean = false,
        var actions: MutableList<String> = mutableListOf(),
        var required: Boolean = false,
        var timeoutMs: Long = 30_000,
        var overrideConfiguredPermissions: Boolean = false
    ) {
        fun permissions() = ProviderPermissions(enabled, actions.mapNotNull { name ->
            VettingAction.entries.firstOrNull { it.name == name }
        }.toSet(), required, timeoutMs.coerceIn(100, 300_000))
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    /** Additional hosts as a set of trimmed, non-blank lines. */
    val extraAllowedHosts: Set<String>
        get() = state.allowedHostsText.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    fun permissions(providerId: String): ProviderPermissions {
        val permissions = state.providers.firstOrNull { it.id == providerId }?.permissions()
            ?: if (providerId == "wrapperguard") BuiltInVettingProvider.DEFAULT_PERMISSIONS
            else ProviderPermissions(enabled = false)
        return if (providerId in setOf("ai.external-model", "research.script-semantics", "research.container-python")) permissions.copy(
            actions = permissions.actions.filterNot { it == VettingAction.BLOCK_BUILD || it == VettingAction.BLOCK_SYNC }.toSet(), required = false)
        else permissions
    }

    companion object {
        fun getInstance(): WrapperGuardSettings =
            ApplicationManager.getApplication().getService(WrapperGuardSettings::class.java)
    }
}

/** Builds the scanner settings from the user-owned settings (merged over OSS defaults). */
fun buildScanSettings(): ScanSettings {
    val settings = WrapperGuardSettings.getInstance()
    return ScanSettings(
        allowedHosts = ScanSettings.DEFAULT_ALLOWED_HOSTS + settings.extraAllowedHosts,
        requireDistributionSha256 = settings.state.requireDistributionSha256,
        methodFlags = settings.state.methodFlags.toMap()
    )
}
