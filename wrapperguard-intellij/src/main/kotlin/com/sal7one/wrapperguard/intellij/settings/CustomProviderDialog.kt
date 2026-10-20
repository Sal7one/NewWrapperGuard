package com.sal7one.wrapperguard.intellij.settings

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.*
import com.intellij.ui.table.JBTable
import com.sal7one.wrapperguard.intellij.vetting.IntellijVettingProviders
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Rectangle
import java.nio.file.Path
import java.util.Locale
import javax.swing.*
import javax.swing.table.DefaultTableModel

/** Literal argv editing and explicit input declarations; never discovers commands in a repository. */
internal class CustomProviderDialog(private val project: Project, private val previous: CustomProviderState? = null) : DialogWrapper(project) {
    private val sdkProviders = if (TrustedProjects.isProjectTrusted(project)) IntellijVettingProviders.installedProviders() else emptyList()
    private val kind = JComboBox(arrayOf("Command / SDK adapter", "Installed IDE SDK extension"))
    private val sdk = JComboBox(sdkProviders.map { "${it.descriptor.displayName} · ${it.descriptor.id}" }.toTypedArray())
    private val id = JBTextField(previous?.id.orEmpty(), 42)
    private val name = JBTextField(previous?.displayName.orEmpty(), 42)
    private val version = JBTextField(previous?.version ?: "1.0.0", 20)
    private val executable = JBTextField(previous?.executable.orEmpty(), 42)
    private val category = JComboBox(arrayOf("SAST", "SUPPLY_CHAIN", "DAST", "RESEARCH", "AI", "OTHER")).apply { isEditable = true; selectedItem = previous?.category ?: "OTHER" }
    private val summary = JBTextField(previous?.summary.orEmpty(), 42)
    private val tags = JBTextField(previous?.tags?.joinToString(", ").orEmpty(), 42)
    private val arguments = DefaultTableModel(arrayOf<Any>("Literal argument · one value per row"), 0)
    private val argumentTable = JBTable(arguments).apply {
        putClientProperty("terminateEditOnFocusLost", true)
        accessibleContext.accessibleName = "Provider arguments, in invocation order"
    }
    private val allProjects = JBCheckBox("Allow this provider in all projects", previous?.projectRoots?.isEmpty() ?: false)
    private val projects = JBTextArea(previous?.projectRoots?.joinToString("\n") ?: project.basePath?.let { Path.of(it).toRealPath().toString() }.orEmpty(), 2, 45)
    private val declared = JBCheckBox("Declare the inputs this provider reads", previous?.scopeDeclared ?: false)
    private val includes = JBTextArea(previous?.includes?.joinToString("\n").orEmpty(), 3, 45)
    private val excludes = JBTextArea(previous?.excludes?.joinToString("\n").orEmpty(), 2, 45)
    private val external = JBTextArea(previous?.externalFiles?.joinToString("\n").orEmpty(), 3, 45)
    private val commandPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val sdkPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val scopePanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

    init {
        previous?.arguments?.forEach { arguments.addRow(arrayOf(it)) }
        kind.selectedIndex = if (previous?.kind == "sdk") 1 else 0
        previous?.let { item -> sdkProviders.indexOfFirst { it.descriptor.id == item.id }.takeIf { it >= 0 }?.let { sdk.selectedIndex = it } }
        title = if (previous == null) "Add security provider" else "Edit ${previous.displayName}"
        setOKButtonText("Save provider")
        init()
        kind.addActionListener { updateKind() }; sdk.addActionListener { updateSdk() }
        declared.addActionListener { updateScope() }; allProjects.addActionListener { projects.isEnabled = !allProjects.isSelected }
        projects.isEnabled = !allProjects.isSelected
        updateKind(); updateScope()
    }

    private fun note(text: String) = JBLabel("<html><div style='width:540px'>$text</div></html>")
    private fun field(label: String, component: JComponent): JPanel = JPanel(BorderLayout(6, 4)).apply {
        add(JBLabel(label).apply { labelFor = component }, BorderLayout.NORTH); add(component, BorderLayout.CENTER)
        alignmentX = 0f; maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        component.accessibleContext.accessibleName = label
        if (component is JScrollPane) component.viewport.view?.accessibleContext?.accessibleName = label
    }
    private fun chooser(field: JBTextField): JButton = JButton("Browse…").apply { addActionListener {
        FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFileDescriptor().withTitle("Choose installed provider executable"), project, null)?.let { field.text = it.path }
    } }

    override fun createCenterPanel(): JComponent {
        val panel = object : JPanel(), Scrollable {
            override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
            override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = 16
            override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
                ((if (orientation == SwingConstants.VERTICAL) visibleRect.height else visibleRect.width) - 24).coerceAtLeast(16)
            override fun getScrollableTracksViewportWidth(): Boolean = true
            override fun getScrollableTracksViewportHeight(): Boolean = false
        }.apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(note("Bring an API 1 adapter or an installed IDE SDK. Commands receive a structured request on standard input and return a provider report on standard output. Saving does not invoke the provider."))
            add(Box.createVerticalStrut(10)); add(field("Connection type", kind))
            sdkPanel.add(field("Installed provider", sdk))
            sdkPanel.add(JButton("Open IDE Plugins…").apply { addActionListener { ShowSettingsUtil.getInstance().showSettingsDialog(project, "Plugins") } })
            sdkPanel.add(note(if (TrustedProjects.isProjectTrusted(project)) "Install your SDK's IDE plugin first. Its own identity and version are retained." else "SDK discovery is paused in Safe Mode. Trust this project through the IDE before selecting an installed SDK; a command connection can still be configured without running it."))
            add(sdkPanel)
            add(field("Stable provider ID (for example company.security)", id)); add(field("Display name", this@CustomProviderDialog.name)); add(field("Provider version", version))
            commandPanel.add(JPanel(BorderLayout(8, 0)).apply { add(field("Installed executable outside all projects", executable), BorderLayout.CENTER); add(chooser(executable), BorderLayout.EAST) })
            commandPanel.add(note("Arguments are passed exactly as entered. Spaces and quotes are literal; there is no shell splitting or variable expansion. Provider code runs with your permissions and is not sandboxed."))
            commandPanel.add(JBScrollPane(argumentTable).apply { preferredSize = Dimension(580, 115); maximumSize = Dimension(Int.MAX_VALUE, 140) })
            commandPanel.add(JPanel(FlowLayout(FlowLayout.LEADING, 6, 4)).apply {
                add(JButton("Add argument").apply { addActionListener { arguments.addRow(arrayOf("")); val row = arguments.rowCount - 1; argumentTable.changeSelection(row, 0, false, false); argumentTable.editCellAt(row, 0); argumentTable.editorComponent?.requestFocusInWindow() } })
                add(JButton("Remove argument").apply { addActionListener { val row = argumentTable.selectedRow; if (row >= 0) arguments.removeRow(row) } })
                add(JButton("Move up").apply { addActionListener { val row = argumentTable.selectedRow; if (row > 0) { arguments.moveRow(row, row, row - 1); argumentTable.setRowSelectionInterval(row - 1, row - 1) } } })
                add(JButton("Move down").apply { addActionListener { val row = argumentTable.selectedRow; if (row >= 0 && row < arguments.rowCount - 1) { arguments.moveRow(row, row, row + 1); argumentTable.setRowSelectionInterval(row + 1, row + 1) } } })
            })
            add(commandPanel)
            add(Box.createVerticalStrut(8)); add(field("Category", category)); add(field("Description (optional, up to 512 characters)", summary)); add(field("Tags (optional, comma separated)", tags))
            add(Box.createVerticalStrut(8)); add(allProjects); add(field("Allowed project roots, one absolute directory per line", JBScrollPane(projects)))
            add(JButton("Add project directory…").apply { addActionListener { FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFolderDescriptor(), project, null)?.let { projects.text = (lines(projects.text) + Path.of(it.path).toRealPath().toString()).distinct().joinToString("\n") } } })
            add(note("The default binds this connection to the current project. Other roots are disabled without running provider code. All projects is an explicit reusable connection choice."))
            add(Box.createVerticalStrut(10)); add(declared)
            add(note("A declaration tracks changes; it does not restrict what trusted provider code can read. Without a declaration, freshness covers only host-known inputs. An explicitly empty declaration means the provider reads no project or external inputs."))
            scopePanel.add(field("Project include globs, one per line (for example src/**/*.kt)", JBScrollPane(includes)))
            scopePanel.add(field("Project exclude globs, one per line (optional)", JBScrollPane(excludes)))
            scopePanel.add(field("External input files outside every open project, one absolute path per line", JBScrollPane(external)))
            scopePanel.add(JButton("Add external file…").apply { addActionListener {
                FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFileDescriptor().withTitle("Choose a trusted external input"), project, null)?.let {
                    val selected = Path.of(it.path).toRealPath().toString()
                    external.text = (lines(external.text) + selected).distinct().joinToString("\n")
                }
            } })
            scopePanel.add(note("Portable globs support *, ? and whole-path segments **. Use forward slashes and paths relative to the project. No absolute paths, .. segments, negation or shell syntax."))
            add(scopePanel); add(Box.createVerticalStrut(10))
            add(note("New providers start disabled, optional, with Warning permission only. Enable and review permissions from the provider card. Enabled adapters must support the operation supplied in their request: scan, build or sync."))
        }
        // Mixed BoxLayout alignments otherwise reserve an empty left column and clip the picker.
        listOf(panel, commandPanel, sdkPanel, scopePanel).forEach { container ->
            container.components.filterIsInstance<JComponent>().forEach { it.alignmentX = Component.LEFT_ALIGNMENT }
        }
        return JBScrollPane(panel).apply {
            preferredSize = Dimension(700, 710); border = BorderFactory.createEmptyBorder()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }
    }

    private fun updateKind() {
        val sdkMode = kind.selectedIndex == 1
        commandPanel.isVisible = !sdkMode; sdkPanel.isVisible = sdkMode
        id.isEditable = !sdkMode && previous == null; name.isEditable = !sdkMode; version.isEditable = !sdkMode
        if (sdkMode) updateSdk()
        contentPanel.revalidate(); contentPanel.repaint()
    }
    private fun updateSdk() {
        if (kind.selectedIndex != 1) return
        sdkProviders.getOrNull(sdk.selectedIndex)?.descriptor?.let { descriptor -> id.text = descriptor.id; name.text = descriptor.displayName; version.text = descriptor.version }
    }
    private fun updateScope() { scopePanel.isVisible = declared.isSelected; contentPanel.revalidate(); contentPanel.repaint() }
    private fun lines(text: String) = text.lineSequence().map(String::trim).filter(String::isNotEmpty).distinct().toList()
    override fun getPreferredFocusedComponent(): JComponent = if (kind.selectedIndex == 1) sdk else if (previous == null) id else name
    override fun doOKAction() {
        if (argumentTable.isEditing && argumentTable.cellEditor.stopCellEditing().not()) return
        try {
            if (kind.selectedIndex == 1) require(sdk.selectedIndex >= 0) { "No installed SDK is available. Install its IDE plugin and reopen this dialog." }
            val input = CustomProviderState(id.text.trim(), name.text.trim(), version.text.trim(), if (kind.selectedIndex == 1) "sdk" else "command",
                executable.text.trim(), (0 until arguments.rowCount).map { arguments.getValueAt(it, 0)?.toString().orEmpty() }.toMutableList(),
                category.selectedItem.toString().trim().uppercase(Locale.ROOT), summary.text.trim(),
                tags.text.split(',').map { it.trim().lowercase(Locale.ROOT) }.filter(String::isNotBlank).distinct().toMutableList(),
                declared.isSelected, lines(includes.text).toMutableList(), lines(excludes.text).toMutableList(), lines(external.text).toMutableList(), if (allProjects.isSelected) mutableListOf() else lines(projects.text).toMutableList())
            require(allProjects.isSelected || input.projectRoots.isNotEmpty()) { "Choose at least one project or explicitly allow all projects." }
            CustomProviders.save(input, OptionalMethodConnections.roots(project), previous?.id)
            super.doOKAction()
        } catch (e: Exception) { setErrorText(e.message ?: "The provider could not be saved.") }
    }
}
