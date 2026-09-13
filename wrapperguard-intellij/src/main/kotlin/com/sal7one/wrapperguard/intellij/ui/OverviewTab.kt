package com.sal7one.wrapperguard.intellij.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.wrapperguard.core.model.*
import com.sal7one.wrapperguard.intellij.ui.components.*
import org.jetbrains.jewel.ui.component.Text

@Composable
internal fun OverviewTab(report: ScanReport, onFindings: (FindingScope) -> Unit) {
    WorkspacePage {
        WorkspaceSection("Scan results") {
            Text(verdictSubtitle(report), fontSize = 14.sp)
            ResultRow("Blocking", report.blockers.size, "Active findings marked BLOCK", FindingScope.BLOCKERS, onFindings)
            ResultRow("Needs review", report.findings.count { it.status == FindingStatus.ACTIVE && it.policyAction == PolicyAction.REVIEW },
                "Active findings marked REVIEW", FindingScope.REVIEW, onFindings)
            ResultRow("Suppressed", report.findings.count { it.status == FindingStatus.SUPPRESSED },
                "Approved through a trusted baseline", FindingScope.SUPPRESSED, onFindings)
            ResultRow("All findings", report.findings.size, "Includes audit evidence and scanner notices", FindingScope.ALL, onFindings)
        }
        WorkspaceSection("Scan details") {
            LabeledValue("Project", report.projectPath, mono = true)
            LabeledValue("Files examined", report.scannedFiles.toString())
            LabeledValue("Coverage", if (report.complete) "Completed within the configured scan scope" else "Incomplete — review the notices below")
            LabeledValue("Last completed", java.time.Instant.ofEpochMilli(report.generatedAtEpochMs)
                .atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")))
            LabeledValue("Duration", "${report.durationMs} ms")
        }
        if (report.notices.isNotEmpty()) WorkspaceSection("Scanner notices (${report.notices.size})") {
            report.notices.forEach { notice ->
                Text(notice.code, fontWeight = FontWeight.Medium, fontSize = 12.sp)
                Text(notice.message, fontSize = 12.sp)
                notice.location?.let { Text(it.display, color = mutedTextColor(), fontSize = 11.sp) }
                WorkspaceDivider()
            }
        }
        WorkspaceSection("Protection scope") {
            Text("The IDE guard rechecks supported run configurations before launch. External terminals and processes launched outside those configurations are not intercepted.", fontSize = 12.sp)
            Text("Scanning reads project files without executing them. A scan cannot undo code that already ran during an earlier import or build.", color = mutedTextColor(), fontSize = 12.sp)
        }
    }
}

@Composable
private fun ResultRow(label: String, count: Int, description: String, scope: FindingScope, onClick: (FindingScope) -> Unit) {
    Row(Modifier.fillMaxWidth().background(cardSurfaceColor()).clickable { onClick(scope) }.padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(count.toString(), fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.widthIn(min = 32.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(description, color = mutedTextColor(), fontSize = 11.sp)
        }
        Text("→", color = mutedTextColor())
    }
}

internal fun verdictTitle(verdict: ScanVerdict): String = when (verdict) {
    ScanVerdict.NOT_SCANNED -> "Not scanned"
    ScanVerdict.CLEAN -> "No actionable findings"
    ScanVerdict.REVIEW_REQUIRED -> "Review required"
    ScanVerdict.EXECUTION_BLOCKED -> "Execution blocked"
    ScanVerdict.INCOMPLETE -> "Scan incomplete"
    ScanVerdict.STALE -> "Results out of date"
    ScanVerdict.FAILED -> "Scan failed"
}

internal fun verdictSubtitle(report: ScanReport?): String = when {
    report == null -> "Scan this project to inspect its build and bootstrap files."
    report.stale -> "Files changed after this scan. Rescan to refresh these results."
    report.verdict == ScanVerdict.FAILED -> "The scan did not finish. Retry to obtain current results."
    !report.complete -> "Some files could not be examined. Review the scanner notices."
    report.blockers.isNotEmpty() -> "${report.blockers.size} active finding(s) are marked to block execution."
    report.findings.any { it.status == FindingStatus.ACTIVE && it.policyAction == PolicyAction.REVIEW } -> "Review the active findings before running this project."
    else -> "No active findings require action under this scan's policy."
}
