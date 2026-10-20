package com.sal7one.wrapperguard.intellij.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.*
import com.intellij.ui.table.JBTable
import com.sal7one.wrapperguard.core.methods.SecurityMethodCatalog
import com.sal7one.wrapperguard.intellij.vetting.IntellijVettingProviders
import java.awt.BorderLayout
import java.awt.Dimension
import java.nio.file.Path
import javax.swing.*
import javax.swing.table.DefaultTableModel

/** Explicit project/session Start; saving, enabling and reopening never arm automation. */
internal class ScanAutomationDialog(private val project: Project) : DialogWrapper(project) {
    private val settings = WrapperGuardSettings.getInstance()
    private val saved = settings.state.automation.snapshot()
    private val service = project.getService(NativeScanAutomation::class.java)
    private val status = JBLabel(service.view().status)
    private val changes = JBCheckBox("After relevant input changes", saved.onChanges)
    private val debounce = JSpinner(SpinnerNumberModel(saved.debounceMs, 500, 60000, 500))
    private val interval = JSpinner(SpinnerNumberModel(saved.intervalMinutes, 0, 1440, 5))
    private val providers = runCatching { IntellijVettingProviders.registrations(project, Path.of(requireNotNull(project.basePath))) }.getOrDefault(emptyList())
    private val providerIds = (providers.map { it.provider.descriptor.id } + saved.providers.keys).distinct().take(33)
    private val model = object : DefaultTableModel(arrayOf<Any>("Use", "Provider", "Network", "Cost", "Reviewed", "Current state"), 0) {
        override fun getColumnClass(column: Int): Class<*> = if (column in setOf(0, 4)) java.lang.Boolean::class.java else String::class.java
        override fun isCellEditable(row: Int, column: Int) = column in setOf(0, 2, 3, 4)
    }
    private val table = object : JBTable(model) {
        override fun getToolTipText(event: java.awt.event.MouseEvent): String? {
            val row = rowAtPoint(event.point); val column = columnAtPoint(event.point)
            return if (row >= 0 && column >= 0) "Value: ${getValueAt(row, column)}" else null
        }
    }.apply {
        accessibleContext.accessibleName = "Automatic SCAN provider selection, network, cost and acknowledgement"
        setDefaultRenderer(String::class.java, javax.swing.table.DefaultTableCellRenderer().apply { putClientProperty("html.disable", true) })
        putClientProperty("terminateEditOnFocusLost", true)
        listOf(52, 335, 90, 90, 90, 215).forEachIndexed { index, width -> columnModel.getColumn(index).preferredWidth = width }
        columnModel.getColumn(0).maxWidth = 60
        columnModel.getColumn(4).minWidth = 90; columnModel.getColumn(4).maxWidth = 100
        columnModel.getColumn(2).cellEditor = DefaultCellEditor(JComboBox(arrayOf("NONE", "LOCAL", "REMOTE", "UNKNOWN")))
        columnModel.getColumn(3).cellEditor = DefaultCellEditor(JComboBox(arrayOf("LOW", "METERED", "UNKNOWN")))
    }
    init {
        val ready = runCatching { service.readyIds() }.getOrDefault(emptySet())
        providerIds.forEach { id ->
            val provider = providers.firstOrNull { it.provider.descriptor.id == id }
            val defaults = if (id == "wrapperguard") AutomationProviderState("NONE", "LOW") else AutomationProviderState()
            val policy = saved.providers[id] ?: defaults
            model.addRow(arrayOf<Any>(id in saved.providers, "${provider?.provider?.descriptor?.displayName ?: id} · $id", policy.network, policy.cost, policy.acknowledged,
                if (id in ready) "Enabled / applicable" else "Enable / connect separately"))
        }
        title = "Automatic scans · this project"; setOKButtonText("Save paused configuration"); init()
        val timer = Timer(500) { status.text = service.view().status }; timer.start()
        Disposer.register(disposable, com.intellij.openapi.Disposable { timer.stop() })
    }
    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, 12)).apply {
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(status)
            add(JBLabel("<html>Start applies only to this canonical project for this IDE session. Saving, opening or enabling a provider never starts automation.<br>Start waits for the next change or future interval; it never runs tools immediately.</html>"))
            add(changes)
            add(JPanel(BorderLayout(8, 0)).apply { add(JBLabel("Change debounce (milliseconds)").apply { labelFor = debounce }, BorderLayout.WEST); add(debounce, BorderLayout.CENTER) })
            add(JPanel(BorderLayout(8, 0)).apply { add(JBLabel("Interval (minutes; 0 off, 5–1440 on)").apply { labelFor = interval }, BorderLayout.WEST); add(interval, BorderLayout.CENTER) })
            debounce.accessibleContext.accessibleName = "Automatic scan change debounce in milliseconds"
            interval.accessibleContext.accessibleName = "Automatic scan interval in minutes, zero disables"
            components.filterIsInstance<JComponent>().forEach { it.alignmentX = java.awt.Component.LEFT_ALIGNMENT }
        }, BorderLayout.NORTH)
        add(JBScrollPane(table), BorderLayout.CENTER)
        add(JBLabel("<html>Select only separately enabled providers. Review each network/cost declaration and check Reviewed.<br>Declarations do not sandbox provider code. Automatic runs use SCAN only, never build, sync or application startup.<br>Pause cancels an automatic scan and timers. Configuration, trust or provider changes pause the session.</html>"), BorderLayout.SOUTH)
        preferredSize = Dimension(910, 480)
    }
    private fun configuration(): ScanAutomationState {
        if (table.isEditing) require(table.cellEditor.stopCellEditing()) { "Finish editing the provider declaration." }
        return ScanAutomationState(enabled = true, onChanges = changes.isSelected, debounceMs = (debounce.value as Number).toInt(), intervalMinutes = (interval.value as Number).toInt(),
            providers = providerIds.indices.filter { model.getValueAt(it, 0) == true }.associate { row -> providerIds[row] to AutomationProviderState(model.getValueAt(row, 2).toString(), model.getValueAt(row, 3).toString(), model.getValueAt(row, 4) == true) }.toMutableMap()).validate()
    }
    private fun persist(value: ScanAutomationState) {
        settings.state.automation = value.snapshot()
        OptionalMethodConnections.changed() // Invalidates seals and pauses every project/session; invokes no analyzer.
    }
    override fun doOKAction() {
        runCatching { persist(configuration()) }.onSuccess { super.doOKAction() }.onFailure { setErrorText(it.message ?: "Review the automation configuration.") }
    }
    override fun createLeftSideActions(): Array<Action> = arrayOf(
        object : AbstractAction("Start for this project") {
            override fun actionPerformed(event: java.awt.event.ActionEvent?) {
                val value = runCatching { configuration() }.getOrElse { setErrorText(it.message); return }
                val root = runCatching { Path.of(requireNotNull(project.basePath)).toRealPath().toString() }.getOrElse { setErrorText("This project has no local directory."); return }
                val review = buildString {
                    append("Project: ").append(root).append("\n\nAutomatic SCAN providers:\n")
                    value.providers.forEach { (id, policy) -> append(id).append(" · network ").append(policy.network).append(" · cost ").append(policy.cost).append(if (policy.acknowledged) " · reviewed\n" else " · NOT REVIEWED\n") }
                    append("\nProvider code keeps your permissions. Start waits for the next configured trigger and does not grant build/sync authority.")
                }
                if (Messages.showYesNoDialog(project, review, "Review automatic scan scope", "Start", "Cancel", Messages.getQuestionIcon()) != Messages.YES) return
                methodProgress(project, "Checking automatic scan readiness") { persist(value); service.start() }
                    .onSuccess { setErrorText(null); status.text = service.view().status }
                    .onFailure { setErrorText(it.message ?: "Automation could not start. Review enabled providers and declarations.") }
            }
        },
        object : AbstractAction("Pause this project") {
            override fun actionPerformed(event: java.awt.event.ActionEvent?) { service.pause(); status.text = service.view().status }
        }
    )
    override fun getPreferredFocusedComponent(): JComponent = table
}
