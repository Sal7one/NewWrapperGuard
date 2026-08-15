package com.sal7one.wrapperguard.intellij.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import com.sal7one.wrapperguard.core.policy.ScanSettings

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
        var scanOnFileChange: Boolean = true
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    /** Additional hosts as a set of trimmed, non-blank lines. */
    val extraAllowedHosts: Set<String>
        get() = state.allowedHostsText.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet()

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
        requireDistributionSha256 = settings.state.requireDistributionSha256
    )
}
