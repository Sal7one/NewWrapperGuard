package com.sal7one.wrapperguard.intellij.ui

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.*
import com.intellij.util.ui.JBUI
import com.sal7one.wrapperguard.core.model.ScanReport
import com.sal7one.wrapperguard.intellij.WrapperGuardProjectService
import com.sal7one.wrapperguard.intellij.ui.components.*
import java.awt.*
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JSplitPane

class WrapperGuardPanel(private val project: Project) : JBPanel<WrapperGuardPanel>(BorderLayout()) {
    private val service = project.getService(WrapperGuardProjectService::class.java)
    private val verdict = VerdictPanel()
    private val blockers = MetricTile("Blocking Findings")
    private val warnings = MetricTile("Warnings")
    private val audit = MetricTile("Audit Evidence")
    private val files = MetricTile("Files Scanned")
    private val tableModel = FindingTableModel()
    private val table = JBTable(tableModel)
    private val details = FindingDetailsPanel()

    private val listener: (ScanReport?) -> Unit = { report ->
        javax.swing.SwingUtilities.invokeLater { render(report) }
    }

    init {
        border = JBUI.Borders.empty()
        add(buildTabs(), BorderLayout.CENTER)
        service.addListener(listener)
    }

    private fun buildTabs(): JBTabbedPane = JBTabbedPane().apply {
        addTab("Overview", buildOverview())
        addTab("Findings", buildFindings())
        addTab("Execution Graph", JBScrollPane(JBTextArea("Call-chain tree goes here first. Reuse ScanReport.edges.")))
        addTab("Policy", JBScrollPane(JBTextArea("OSS Defaults\nMode: Warn\nOpen full settings from the toolbar.")))
    }

    private fun buildOverview(): JPanel {
        val left = JPanel(BorderLayout(0, 8)).apply {
            border = JBUI.Borders.empty(12)
            add(verdict, BorderLayout.NORTH)
            add(JButton("Rescan Project").apply { addActionListener { rescan() } }, BorderLayout.SOUTH)
        }
        val metrics = JPanel(GridLayout(2, 2, 8, 8)).apply {
            border = JBUI.Borders.empty(12)
            add(blockers); add(warnings); add(audit); add(files)
        }
        return JPanel(BorderLayout()).apply {
            add(left, BorderLayout.WEST)
            add(metrics, BorderLayout.CENTER)
        }
    }

    private fun buildFindings(): JPanel {
        table.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION)
        table.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) details.render(tableModel.findingAt(table.selectedRow))
        }
        table.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(event: java.awt.event.MouseEvent) {
                if (event.clickCount == 2) openSelectedFinding()
            }
        })
        val split = JSplitPane(JSplitPane.VERTICAL_SPLIT, JBScrollPane(table), JBScrollPane(details)).apply {
            resizeWeight = 0.65
            dividerLocation = 220
        }
        return JPanel(BorderLayout()).apply { add(split, BorderLayout.CENTER) }
    }

    private fun rescan() {
        object : Task.Backgroundable(project, "WrapperGuard: Scanning project", false) {
            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                service.scan()
            }
        }.queue()
    }

    private fun openSelectedFinding() {
        val finding = tableModel.findingAt(table.selectedRow) ?: return
        val absolute = java.nio.file.Path.of(project.basePath ?: return).resolve(finding.location.path).normalize()
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(absolute) ?: return
        OpenFileDescriptor(project, file, (finding.location.line ?: 1) - 1, 0).navigate(true)
    }

    private fun render(report: ScanReport?) {
        verdict.render(report)
        blockers.setValue(report?.blockers?.size ?: 0)
        warnings.setValue(report?.warnings?.size ?: 0)
        audit.setValue(report?.auditEvidence?.size ?: 0)
        files.setValue(report?.scannedFiles ?: 0)
        tableModel.setFindings(report?.findings ?: emptyList())
        details.render(null)
    }
}
