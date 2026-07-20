package com.sal7one.wrapperguard.intellij.ui.components

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Font

class MetricTile(
    title: String,
    private val onClick: (() -> Unit)? = null
) : JBPanel<MetricTile>(BorderLayout()) {
    private val valueLabel = JBLabel("0")

    init {
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(Color.GRAY, 1),
            JBUI.Borders.empty(8)
        )
        add(JBLabel(title.uppercase()).apply { font = font.deriveFont(11f) }, BorderLayout.NORTH)
        add(valueLabel.apply { font = font.deriveFont(Font.BOLD, 28f) }, BorderLayout.CENTER)
        if (onClick != null) {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent?) = onClick.invoke()
            })
        }
    }

    fun setValue(value: Int) {
        valueLabel.text = value.toString()
    }
}
