package com.sal7one.wrapperguard.intellij.ui.components

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.sal7one.wrapperguard.core.model.ScanReport
import com.sal7one.wrapperguard.core.model.ScanVerdict
import java.awt.BorderLayout
import java.awt.Font

class VerdictPanel : JBPanel<VerdictPanel>(BorderLayout(8, 4)) {
    private val title = JBLabel("Not Scanned", AllIcons.General.Information, JBLabel.LEFT)
    private val detail = JBLabel("Run a scan to inspect this project.")

    init {
        border = JBUI.Borders.compound(JBUI.Borders.customLine(java.awt.Color.GRAY, 1), JBUI.Borders.empty(8))
        title.font = title.font.deriveFont(Font.BOLD, 16f)
        add(title, BorderLayout.NORTH)
        add(detail, BorderLayout.CENTER)
    }

    fun render(report: ScanReport?) {
        title.text = when (report?.verdict) {
            ScanVerdict.CLEAN -> "Clean"
            ScanVerdict.REVIEW_REQUIRED -> "Review Required"
            ScanVerdict.EXECUTION_BLOCKED -> "Execution Blocked"
            ScanVerdict.INCOMPLETE -> "Scan Incomplete"
            ScanVerdict.STALE -> "Scan Stale"
            ScanVerdict.FAILED -> "Scan Failed"
            else -> "Not Scanned"
        }
        detail.text = when {
            report == null -> "Run a scan to inspect this project."
            !report.complete -> "${report.notices.size} scanner notice(s) require review."
            report.blockers.isNotEmpty() -> "${report.blockers.size} finding(s) block execution."
            report.warnings.isNotEmpty() -> "${report.warnings.size} finding(s) need review."
            else -> "No actionable findings."
        }
    }
}
