package com.sal7one.wrapperguard.intellij.ui.components

import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.sal7one.wrapperguard.core.model.Finding
import java.awt.BorderLayout

class FindingDetailsPanel : JBPanel<FindingDetailsPanel>(BorderLayout()) {
    private val text = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(8)
    }

    init { add(text, BorderLayout.CENTER) }

    fun render(finding: Finding?) {
        text.text = if (finding == null) {
            "Select a finding to inspect evidence and remediation."
        } else buildString {
            appendLine("${finding.ruleId}  ${finding.title}")
            appendLine("${finding.severity} · ${finding.confidence} · ${finding.policyAction}")
            appendLine(finding.location.display)
            appendLine()
            appendLine(finding.message)
            finding.evidence?.let { appendLine("\nEvidence\n${it.text}") }
            if (finding.callChain.isNotEmpty()) {
                appendLine("\nExecution path")
                finding.callChain.forEach { appendLine("${it.source.display} → ${it.viaCommand}") }
            }
            finding.remediation?.let { appendLine("\nRemediation\n$it") }
        }
        text.caretPosition = 0
    }
}
