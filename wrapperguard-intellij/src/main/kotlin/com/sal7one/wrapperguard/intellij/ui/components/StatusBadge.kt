package com.sal7one.wrapperguard.intellij.ui.components

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import javax.swing.JLabel
import javax.swing.border.CompoundBorder

class StatusBadge(text: String, foreground: Color) : JLabel(text) {
    init {
        isOpaque = true
        this.foreground = foreground
        background = JBColor(foreground.brighter(), foreground.darker())
        border = CompoundBorder(
            JBUI.Borders.customLine(foreground, 1),
            JBUI.Borders.empty(2, 6)
        )
    }
}
