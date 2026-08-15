package com.sal7one.wrapperguard.intellij.execution

import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.sal7one.wrapperguard.core.model.Finding
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Modal shown by the run guard when policy-selected findings block a run. Matches
 * `design_reference/execution_blocked_dialog`: header, the intercepted command, the
 * policy-violation list (rule id + title, evidence in mono, location), the assurance
 * line, and Cancel / Run Once / Review Blockers actions.
 *
 * OK ("Run Once") = user explicitly accepts the risk; anything else blocks the run.
 * "Review Blockers" opens the WrapperGuard tool window.
 */
class ExecutionBlockedDialog(
    private val projectRef: Project,
    private val configuration: RunConfiguration,
    private val blockers: List<Finding>
) : DialogWrapper(projectRef, true) {

    init {
        title = "WrapperGuard: Execution Blocked"
        setOKButtonText("Run Once")
        setCancelButtonText("Cancel")
        init()
    }

    /** True only when the user chose "Run Once" (accept risk). */
    fun showAndGetRunOnce(): Boolean {
        show()
        return isOK
    }

    override fun createActions(): Array<javax.swing.Action> = arrayOf(
        getOKAction(),
        getCancelAction(),
        object : DialogWrapperAction("Review Blockers") {
            override fun doAction(e: java.awt.event.ActionEvent) {
                ToolWindowManager.getInstance(projectRef).getToolWindow("WrapperGuard")?.activate(null)
                close(CANCEL_EXIT_CODE, true)
            }
        }
    )

    override fun createContentPaneBorder() = JBUI.Borders.empty(14, 18)

    override fun createCenterPanel(): JComponent = JBPanel<JBPanel<*>>(BorderLayout(0, 12)).apply {
        // ── Interception line ────────────────────────────────────────
        add(JBPanel<JBPanel<*>>(BorderLayout(8, 0)).apply {
            add(JLabel("⛔ Intercepted:"), BorderLayout.WEST)
            add(
                JBLabel("${configuration.name}  (${configuration.type.displayName})").apply {
                    font = font.deriveFont(Font.BOLD, font.size2D + 1f)
                    setCopyable(true)
                },
                BorderLayout.CENTER
            )
        }, BorderLayout.NORTH)

        // ── Policy violations ────────────────────────────────────────
        add(buildViolationsList(), BorderLayout.CENTER)

        // ── Assurance ────────────────────────────────────────────────
        add(muted("ℹ No project code was executed."), BorderLayout.SOUTH)
    }

    private fun buildViolationsList(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        blockers.take(6).forEach { finding ->
            add(violationRow(finding))
            add(Box.createVerticalStrut(6))
        }
        if (blockers.size > 6) {
            add(muted("…and ${blockers.size - 6} more — see the WrapperGuard tool window."))
        }
    }

    private fun violationRow(finding: Finding): JPanel = JBPanel<JBPanel<*>>(BorderLayout(8, 0)).apply {
        add(JLabel("✖"), BorderLayout.WEST)
        add(JBPanel<JBPanel<*>>(BorderLayout(0, 2)).apply {
            add(JBLabel("${finding.ruleId} — ${finding.title}").apply { font = font.deriveFont(Font.BOLD) }, BorderLayout.NORTH)
            finding.evidence?.let { evidence ->
                add(
                    JBLabel(evidence.text.take(160)).apply {
                        font = JBUI.Fonts.label()
                        setCopyable(true)
                    },
                    BorderLayout.CENTER
                )
            }
            finding.location.display.takeIf { it.isNotBlank() }?.let { location ->
                add(muted(location), BorderLayout.SOUTH)
            }
        }, BorderLayout.CENTER)
    }

    private fun muted(text: String): JBLabel = JBLabel(text).apply {
        foreground = com.intellij.util.ui.UIUtil.getContextHelpForeground()
    }
}
