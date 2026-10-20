package com.sal7one.wrapperguard.intellij.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.*
import com.sal7one.wrapperguard.core.methods.*
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*

/** Presets select methods only; connections, custom providers and reviewed grants remain user owned. */
internal class MethodPresetDialog(project: Project) : DialogWrapper(project) {
    private val presets = SecurityPresets.presets
    private val selection = JComboBox(presets.map { it.title }.toTypedArray())
    private val description = JBTextArea().apply { isEditable = false; lineWrap = true; wrapStyleWord = true; isOpaque = false }
    init {
        title = "Choose analysis methods"; setOKButtonText("Apply method selection"); init()
        selection.addActionListener { updatePreview() }; updatePreview()
    }
    private fun updatePreview() {
        val preset = presets[selection.selectedIndex]
        val current = WrapperGuardSettings.getInstance().state.methodFlags
        description.text = buildString {
            append(preset.summary).append("\n\n").append(preset.notice).append("\n\nMethods:\n")
            preset.methodFlags.forEach { (id, enabled) ->
                val method = requireNotNull(SecurityMethodCatalog.find(id))
                append(if (enabled) "Enable: " else "Disable: ").append(method.title)
                if (SecurityMethodCatalog.isEnabled(id, current) == enabled) append(" (unchanged)")
                append('\n')
            }
            append("\nThis changes method preferences only. No tool is installed or connected, no permissions are granted, and custom providers are unchanged. Enabled optional methods still need setup and permission review.")
        }
        description.caretPosition = 0
    }
    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, 12)).apply {
        add(JPanel(BorderLayout(8, 0)).apply { add(JBLabel("Method preset").apply { labelFor = selection }, BorderLayout.WEST); add(selection, BorderLayout.CENTER) }, BorderLayout.NORTH)
        add(JBScrollPane(description), BorderLayout.CENTER); preferredSize = Dimension(630, 440)
        description.accessibleContext.accessibleName = "Method selection preview and limits"
    }
    override fun doOKAction() {
        val state = WrapperGuardSettings.getInstance().state
        state.methodFlags = presets[selection.selectedIndex].applyTo(state.methodFlags).toMutableMap()
        runCatching { OptionalMethodConnections.refreshGeneratedConfiguration() }.onFailure { setErrorText("The method selection could not be saved."); return }
        OptionalMethodConnections.changed(); super.doOKAction()
    }
}
