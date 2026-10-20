package com.sal7one.wrapperguard.intellij.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.ProjectManager
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.sal7one.wrapperguard.core.methods.SecurityMethodCatalog
import com.sal7one.wrapperguard.core.vetting.BuiltInVettingProvider
import com.sal7one.wrapperguard.core.vetting.ProviderConfiguration
import com.sal7one.wrapperguard.core.vetting.ProviderDescriptor
import com.sal7one.wrapperguard.core.vetting.VettingAction
import com.sal7one.wrapperguard.intellij.WrapperGuardProjectService
import com.sal7one.wrapperguard.intellij.vetting.IntellijVettingProviders
import com.sal7one.wrapperguard.intellij.vetting.displayName
import com.sal7one.wrapperguard.rules.RepositoryScanner
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/** All grants are user-owned application state, never project files or provider output. */
class WrapperGuardConfigurable : Configurable {
    private val hostsField = JBTextArea(6, 40)
    private val configPath = JBTextField(40)
    private val shaCheck = JBCheckBox("Require distributionSha256Sum in gradle-wrapper.properties")
    private val scanOpenCheck = JBCheckBox("Scan when a project is opened")
    private val scanChangeCheck = JBCheckBox("Rescan when wrapper/build/script files change")
    private var rows: List<ProviderControls> = emptyList()
    private var methodRows: Map<String, JBCheckBox> = emptyMap()
    private var methodInitial: Map<String, Boolean> = emptyMap()

    override fun getDisplayName(): String = "WrapperGuard"

    override fun createComponent(): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        }
        panel.add(JBLabel("Additional allowed hosts (one per line):").apply { labelFor = hostsField })
        hostsField.lineWrap = false
        panel.add(JScrollPane(hostsField).apply { alignmentX = 0f })
        panel.add(shaCheck)
        panel.add(scanOpenCheck)
        panel.add(scanChangeCheck)
        panel.add(Box.createVerticalStrut(12))
        panel.add(JBLabel("Analysis methods (application preferences, never repository policy):"))
        panel.add(JBLabel("Use the WrapperGuard Tools / Research tabs for SDK installation, connection setup and permissions."))
        methodRows = SecurityMethodCatalog.methods.associate { method -> method.id to JBCheckBox("${method.title} · ${method.category.name.lowercase().replace('_', ' ')}").also { box -> panel.add(box) } }
        panel.add(Box.createVerticalStrut(12))
        panel.add(JBLabel("Trusted provider configuration (absolute JSON file path, outside each scanned project):").apply {
            labelFor = configPath
        })
        configPath.accessibleContext.accessibleDescription = "Optional user-owned provider JSON file. Repository configurations are rejected."
        panel.add(configPath)
        panel.add(JBLabel("This file defines command providers and overrides the built-in grants below."))
        panel.add(JBLabel("Only enable providers you trust. Their code can read files and access the network."))
        panel.add(JBLabel("Third-party providers are paused while an IDE project is in Safe Mode."))
        panel.add(Box.createVerticalStrut(12))
        val untrustedOpen = ProjectManager.getInstance().openProjects.any { !TrustedProjects.isProjectTrusted(it) }
        val descriptors = listOf(BuiltInVettingProvider(RepositoryScanner()).descriptor.copy(displayName = "WrapperGuard (built-in)")) +
            (if (untrustedOpen) emptyList() else IntellijVettingProviders.installedProviders().map { it.descriptor }) +
            WrapperGuardSettings.getInstance().state.methodConnections.mapNotNull { connection -> SecurityMethodCatalog.find(connection.methodId)?.let { method ->
                method.providerId?.let { ProviderDescriptor(it, method.title, "0.4.0") }
            } } + WrapperGuardSettings.getInstance().state.customProviders.map { it.descriptor() }
        if (untrustedOpen) panel.add(JBLabel("SDK settings are paused while an open project is in Safe Mode."))
        rows = descriptors.distinctBy { it.id }.map { ProviderControls(it).also { row -> panel.add(row.panel) } }
        panel.add(Box.createVerticalGlue())
        reset()
        return JScrollPane(panel).apply { border = BorderFactory.createEmptyBorder() }
    }

    override fun isModified(): Boolean {
        val settings = WrapperGuardSettings.getInstance()
        val s = settings.state
        return hostsField.text.trim() != s.allowedHostsText.trim() ||
            configPath.text.trim() != s.providerConfigurationPath ||
            shaCheck.isSelected != s.requireDistributionSha256 ||
            scanOpenCheck.isSelected != s.scanOnOpen ||
            scanChangeCheck.isSelected != s.scanOnFileChange ||
            rows.any { it.currentState().permissions() != settings.permissions(it.descriptor.id) } ||
            methodRows.any { (id, box) -> box.isSelected != methodInitial[id] }
    }

    override fun apply() {
        val pathText = configPath.text.trim()
        if (pathText.isNotEmpty()) {
            try {
                val path = Path.of(pathText)
                require(path.isAbsolute && Files.isRegularFile(path))
                // Parsing validates schema, executable paths and the outside-project boundary.
                val roots = ProjectManager.getInstance().openProjects.mapNotNull { it.basePath?.let(Path::of) }
                roots.forEach { ProviderConfiguration.load(path, it) }
                if (roots.isEmpty()) require(Files.size(path) <= 65_536)
            } catch (_: Exception) {
                throw ConfigurationException("Choose a valid provider JSON file outside all open projects. Executable paths must be absolute.")
            }
        }
        val s = WrapperGuardSettings.getInstance().state
        s.allowedHostsText = hostsField.text
        s.providerConfigurationPath = pathText
        s.requireDistributionSha256 = shaCheck.isSelected
        s.scanOnOpen = scanOpenCheck.isSelected
        s.scanOnFileChange = scanChangeCheck.isSelected
        methodRows.forEach { (id, box) -> if (box.isSelected != methodInitial[id] || s.methodFlags.containsKey(id)) s.methodFlags[id] = box.isSelected }
        methodInitial = methodRows.mapValues { it.value.isSelected }
        // Retain settings for SDK plugins that are currently uninstalled.
        val visibleIds = rows.map { it.descriptor.id }.toSet()
        s.providers = (s.providers.filterNot { it.id in visibleIds } + rows.map { it.currentState() }).toMutableList()
        ProjectManager.getInstance().openProjects.forEach {
            it.getService(WrapperGuardProjectService::class.java)?.settingsChanged()
        }
    }

    override fun reset() {
        val settings = WrapperGuardSettings.getInstance()
        val s = settings.state
        hostsField.text = s.allowedHostsText
        configPath.text = s.providerConfigurationPath
        shaCheck.isSelected = s.requireDistributionSha256
        scanOpenCheck.isSelected = s.scanOnOpen
        scanChangeCheck.isSelected = s.scanOnFileChange
        rows.forEach { it.reset(settings.permissions(it.descriptor.id)) }
        methodRows.forEach { (id, box) -> box.isSelected = s.methodFlags[id] ?: SecurityMethodCatalog.find(id)?.let {
            if (it.providerId == "wrapperguard") it.defaultEnabled else settings.permissions(it.providerId.orEmpty()).enabled
        } ?: false }
        methodInitial = methodRows.mapValues { it.value.isSelected }
    }

    private class ProviderControls(val descriptor: ProviderDescriptor) {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createTitledBorder("${descriptor.displayName} · ${descriptor.id} · ${descriptor.version}")
            alignmentX = 0f
        }
        private val enabled = JBCheckBox("Enable provider")
        private val required = JBCheckBox("Require successful result before permitted build / sync")
        private val actions = VettingAction.entries.associateWith { action -> JBCheckBox(action.displayName()).apply {
            accessibleContext.accessibleName = "${descriptor.displayName}: ${action.displayName()} permission"
            if (descriptor.id in setOf("ai.external-model", "research.script-semantics", "research.container-python") && action in setOf(VettingAction.BLOCK_BUILD, VettingAction.BLOCK_SYNC)) isEnabled = false
        } }
        private val timeout = JSpinner(SpinnerNumberModel(30_000L, 100L, 300_000L, 1_000L))

        init {
            enabled.accessibleContext.accessibleName = "Enable ${descriptor.displayName} provider"
            required.isEnabled = descriptor.id !in setOf("ai.external-model", "research.script-semantics", "research.container-python")
            required.accessibleContext.accessibleName = "Require successful ${descriptor.displayName} result before permitted operations"
            timeout.accessibleContext.accessibleName = "${descriptor.displayName} timeout in milliseconds"
            panel.add(enabled)
            panel.add(required)
            panel.add(JBLabel("Permitted actions:"))
            panel.add(JPanel(FlowLayout(FlowLayout.LEADING)).apply { actions.values.forEach(::add) })
            panel.add(JPanel(BorderLayout(8, 0)).apply {
                add(JBLabel("Timeout (milliseconds, 100–300000):").apply { labelFor = timeout }, BorderLayout.WEST)
                add(timeout, BorderLayout.CENTER)
            })
        }

        fun reset(permissions: com.sal7one.wrapperguard.core.vetting.ProviderPermissions) {
            enabled.isSelected = permissions.enabled
            required.isSelected = permissions.required
            timeout.value = permissions.timeoutMs
            actions.forEach { (action, box) -> box.isSelected = action in permissions.actions }
        }

        fun currentState() = WrapperGuardSettings.ProviderState(
            id = descriptor.id, enabled = enabled.isSelected,
            actions = actions.filterValues { it.isSelected }.keys.map { it.name }.toMutableList(),
            required = required.isSelected, timeoutMs = (timeout.value as Number).toLong(),
            overrideConfiguredPermissions = WrapperGuardSettings.getInstance().state.providers.firstOrNull { it.id == descriptor.id }?.overrideConfiguredPermissions ?: false
        )
    }
}
