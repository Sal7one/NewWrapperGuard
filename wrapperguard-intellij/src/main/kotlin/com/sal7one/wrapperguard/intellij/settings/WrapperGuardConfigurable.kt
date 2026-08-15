package com.sal7one.wrapperguard.intellij.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane

/**
 * Settings → Tools → WrapperGuard. Edits the user-owned policy inputs: additional allowed
 * hosts, the distribution checksum requirement, and the scan toggles. Findings never modify
 * these — only the user does (repository content cannot weaken the local policy).
 */
class WrapperGuardConfigurable : Configurable {

    private val hostsField = JBTextArea(8, 40)
    private val shaCheck = JBCheckBox("Require distributionSha256Sum in gradle-wrapper.properties")
    private val scanOpenCheck = JBCheckBox("Scan when a project is opened")
    private val scanChangeCheck = JBCheckBox("Rescan when wrapper/build/script files change")

    override fun getDisplayName(): String = "WrapperGuard"

    override fun createComponent(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = BorderFactory.createEmptyBorder(12, 12, 12, 12)

        panel.add(JBLabel("Additional allowed hosts (one per line, merged with the OSS defaults):"))
        hostsField.lineWrap = false
        val scroll = JScrollPane(hostsField)
        scroll.alignmentX = 0f
        scroll.border = BorderFactory.createTitledBorder("Allowed hosts")
        panel.add(scroll)

        panel.add(Box.createVerticalStrut(12))
        panel.add(shaCheck)
        panel.add(scanOpenCheck)
        panel.add(scanChangeCheck)
        return panel
    }

    override fun isModified(): Boolean {
        val s = WrapperGuardSettings.getInstance().state
        return hostsField.text.trim() != s.allowedHostsText.trim() ||
            shaCheck.isSelected != s.requireDistributionSha256 ||
            scanOpenCheck.isSelected != s.scanOnOpen ||
            scanChangeCheck.isSelected != s.scanOnFileChange
    }

    override fun apply() {
        val s = WrapperGuardSettings.getInstance().state
        s.allowedHostsText = hostsField.text
        s.requireDistributionSha256 = shaCheck.isSelected
        s.scanOnOpen = scanOpenCheck.isSelected
        s.scanOnFileChange = scanChangeCheck.isSelected
    }

    override fun reset() {
        val s = WrapperGuardSettings.getInstance().state
        hostsField.text = s.allowedHostsText
        shaCheck.isSelected = s.requireDistributionSha256
        scanOpenCheck.isSelected = s.scanOnOpen
        scanChangeCheck.isSelected = s.scanOnFileChange
    }
}
