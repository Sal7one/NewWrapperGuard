package com.sal7one.wrapperguard.intellij.ui.components

import com.sal7one.wrapperguard.core.model.Finding
import javax.swing.table.AbstractTableModel

class FindingTableModel : AbstractTableModel() {
    private var rows: List<Finding> = emptyList()
    private val columns = listOf("Severity", "Issue", "Location", "Reachability", "Action")

    fun setFindings(findings: List<Finding>) {
        rows = findings
        fireTableDataChanged()
    }

    fun findingAt(row: Int): Finding? = rows.getOrNull(row)
    override fun getRowCount(): Int = rows.size
    override fun getColumnCount(): Int = columns.size
    override fun getColumnName(column: Int): String = columns[column]

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val finding = rows[rowIndex]
        return when (columnIndex) {
            0 -> finding.severity
            1 -> finding.title
            2 -> finding.location.display
            3 -> if (finding.reachable) "Reachable" else "Unreachable"
            4 -> finding.policyAction
            else -> ""
        }
    }
}
