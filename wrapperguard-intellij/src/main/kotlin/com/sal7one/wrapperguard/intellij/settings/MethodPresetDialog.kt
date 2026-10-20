package com.sal7one.wrapperguard.intellij.settings

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.*
import com.sal7one.wrapperguard.core.methods.*
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.swing.*

/** Data-only reviewed selections; no connections, commands, grants, or automatic execution. */
internal class MethodPresetDialog(private val project: Project) : DialogWrapper(project) {
    private val presets = SecurityPresets.presets
    private val selection = JComboBox(presets.map { it.title }.toTypedArray())
    private var imported: OrganizationPreset? = null
    private val description = JBTextArea().apply { isEditable = false; lineWrap = true; wrapStyleWord = true; isOpaque = false }
    init {
        title = "Choose analysis methods"; setOKButtonText("Apply reviewed method selection"); init()
        selection.addActionListener { imported = null; updatePreview() }; updatePreview()
    }
    private fun updatePreview() {
        val preset = presets[selection.selectedIndex]
        val current = WrapperGuardSettings.getInstance().state.methodFlags
        val flags = imported?.methodFlags ?: preset.methodFlags
        description.text = buildString {
            imported?.let { document ->
                append("Imported: ").append(document.title).append("\nID: ").append(document.id)
                append("\nIssuer: ").append(document.issuer).append("\nVersion: ").append(document.version)
                append("\nCreated: ").append(java.time.Instant.ofEpochMilli(document.createdAt))
                append("\nSHA-256: ").append(document.digest)
                append("\n\nDigest verified: local content integrity only. The issuer is supplied by the file; this is not an authenticated signature.\n\n")
            } ?: append(preset.summary).append("\n\n").append(preset.notice).append("\n\n")
            append("Reviewed methods:\n")
            flags.forEach { (id, enabled) ->
                append(if (enabled) "Enable: " else "Disable: ").append(requireNotNull(SecurityMethodCatalog.find(id)).title)
                if (SecurityMethodCatalog.isEnabled(id, current) == enabled) append(" (unchanged)")
                append('\n')
            }
            append("\nApply changes method preferences only. No tool is installed, connected or executed; no permissions are granted. Custom providers are unchanged. Enabled optional methods still need setup and permission review. Automation pauses when preferences change.")
        }
        description.caretPosition = 0
    }
    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, 12)).apply {
        add(JPanel(BorderLayout(8, 8)).apply {
            add(JPanel(BorderLayout(8, 0)).apply { add(JBLabel("Bundled method preset").apply { labelFor = selection }, BorderLayout.WEST); add(selection, BorderLayout.CENTER) }, BorderLayout.NORTH)
            add(JPanel(FlowLayout(FlowLayout.LEADING, 6, 0)).apply {
                add(JButton("Import organization preset…").apply { addActionListener {
                    val file = FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFileDescriptor("json").withTitle("Review data-only method preset"), project, null) ?: return@addActionListener
                    methodProgress(project, "Checking preset integrity") { OrganizationPresets.read(java.nio.file.Path.of(file.path)) }
                        .onSuccess { imported = it; updatePreview(); setErrorText(null) }
                        .onFailure { setErrorText(it.message ?: "The preset could not be verified.") }
                } })
                add(JButton("Export current selection…").apply { addActionListener { ExportMethodPresetDialog(project).showAndGet() } })
                add(JButton("Use bundled selection").apply { addActionListener { imported = null; updatePreview() } })
            }, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        add(JBScrollPane(description), BorderLayout.CENTER); preferredSize = Dimension(680, 510)
        selection.accessibleContext.accessibleName = "Bundled method preset"
        description.accessibleContext.accessibleName = "Reviewed method selection, issuer and integrity limits"
    }
    override fun doOKAction() {
        val state = WrapperGuardSettings.getInstance().state
        val selected = imported?.methodFlags ?: presets[selection.selectedIndex].methodFlags
        val previous = state.methodFlags.toMutableMap()
        state.methodFlags.putAll(selected)
        runCatching { OptionalMethodConnections.refreshGeneratedConfiguration() }.onFailure {
            state.methodFlags = previous; setErrorText("The method selection could not be saved."); return
        }
        OptionalMethodConnections.changed(); super.doOKAction()
    }
}

private class ExportMethodPresetDialog(private val project: Project) : DialogWrapper(project) {
    private val id = JBTextField("organization.security", 40)
    private val name = JBTextField("Organization analysis methods", 40)
    private val version = JBTextField("1.0.0", 40)
    private val issuer = JBTextField(40)
    init { title = "Export method preferences"; setOKButtonText("Review and save preset…"); init() }
    override fun createCenterPanel(): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(JBLabel("Exports only known method preferences. Commands, connections and permissions are excluded."))
        listOf("Preset ID" to id, "Title" to this@ExportMethodPresetDialog.name, "Semantic version" to version, "Issuer (self-declared)" to issuer).forEach { (label, field) ->
            add(JBLabel(label).apply { labelFor = field }); add(field); field.accessibleContext.accessibleName = label
        }
        add(JBLabel("SHA-256 verifies content integrity; it does not authenticate the issuer."))
    }
    override fun getPreferredFocusedComponent(): JComponent = id
    override fun doOKAction() {
        val flags = WrapperGuardSettings.getInstance().state.methodFlags
        val preset = runCatching { OrganizationPresets.create(id.text.trim(), name.text.trim(), version.text.trim(), issuer.text.trim(),
            SecurityMethodCatalog.methods.associate { it.id to SecurityMethodCatalog.isEnabled(it.id, flags) }) }
            .getOrElse { setErrorText(it.message ?: "Review the preset identity."); return }
        val file = FileChooserFactory.getInstance().createSaveFileDialog(FileSaverDescriptor("Export method preset", "Data-only method preferences", "json"), project)
            .save(null as com.intellij.openapi.vfs.VirtualFile?, "wrapperguard-methods.json")?.file?.toPath() ?: return
        methodProgress(project, "Saving method preset") {
            val target = file.toAbsolutePath().normalize()
            require(!Files.isSymbolicLink(target)) { "Choose a regular output file." }
            val temporary = Files.createTempFile(target.parent, ".wrapperguard-preset-", ".json")
            try {
                Files.writeString(temporary, OrganizationPresets.encode(preset))
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally { Files.deleteIfExists(temporary) }
        }.onSuccess { super.doOKAction() }.onFailure { setErrorText(it.message ?: "The preset could not be saved.") }
    }
}
