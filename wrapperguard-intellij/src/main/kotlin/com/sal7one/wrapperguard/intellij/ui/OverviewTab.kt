package com.sal7one.wrapperguard.intellij.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.wrapperguard.core.model.ScanReport
import com.sal7one.wrapperguard.core.model.ScanVerdict
import com.sal7one.wrapperguard.intellij.ui.components.Card
import com.sal7one.wrapperguard.intellij.ui.components.MetricTile
import com.sal7one.wrapperguard.intellij.ui.components.ScrollableColumn
import com.sal7one.wrapperguard.intellij.ui.components.StatusBadge
import org.jetbrains.jewel.ui.component.Text

/**
 * Overview tab: verdict banner, last-scan/policy metadata, and a 2×2 metric grid
 * (blocking findings, warnings, audit evidence, files scanned). Wrapped in a scroll
 * container so it never clips at small tool-window heights.
 */
@Composable
fun OverviewTab(
    report: ScanReport?,
    isScanning: Boolean,
    onRescan: () -> Unit,
    onJumpToFindings: () -> Unit
) {
    ScrollableColumn {
        VerdictBanner(report, isScanning, onRescan)

        val blockers = report?.blockers?.size ?: 0
        val warnings = report?.warnings?.size ?: 0
        val audit = report?.auditEvidence?.size ?: 0
        val files = report?.scannedFiles ?: 0

        val rows = listOf(
            Triple("Blocking Findings", blockers, verdictColor(ScanVerdict.EXECUTION_BLOCKED)),
            Triple("Warnings", warnings, verdictColor(ScanVerdict.REVIEW_REQUIRED)),
            Triple("Audit Evidence", audit, verdictColor(ScanVerdict.CLEAN)),
            Triple("Files Scanned", files, policyActionColor(com.sal7one.wrapperguard.core.model.PolicyAction.ALLOW))
        )
        // 2×2 metric grid.
        for (rowIdx in rows.indices step 2) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricTile(
                    label = rows[rowIdx].first, count = rows[rowIdx].second, tone = rows[rowIdx].third,
                    modifier = Modifier.weight(1f).height(96.dp),
                    onClick = if (rows[rowIdx].second > 0 && rowIdx == 0) onJumpToFindings else null
                )
                if (rowIdx + 1 < rows.size) {
                    MetricTile(
                        label = rows[rowIdx + 1].first, count = rows[rowIdx + 1].second, tone = rows[rowIdx + 1].third,
                        modifier = Modifier.weight(1f).height(96.dp)
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
        }

        report?.let {
            ScanMetaRow(it)
        }
    }
}

@Composable
private fun VerdictBanner(report: ScanReport?, isScanning: Boolean, onRescan: () -> Unit) {
    val verdict = report?.verdict ?: ScanVerdict.NOT_SCANNED
    Card(
        modifier = Modifier.fillMaxWidth(),
        borderColor = verdictColor(verdict).copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(PaddingValues(16.dp)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = verdictTitle(verdict),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = verdictColor(verdict)
                    )
                    StatusBadge(
                        text = if (isScanning) "Scanning" else if (report == null) "Idle" else "Ready",
                        tone = if (isScanning) verdictColor(ScanVerdict.REVIEW_REQUIRED) else mutedTextColor()
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = verdictSubtitle(report),
                    color = mutedTextColor(),
                    fontSize = 12.sp
                )
            }
            Spacer(Modifier.width(16.dp))
            org.jetbrains.jewel.ui.component.OutlinedButton(onClick = onRescan, enabled = !isScanning) {
                Text(if (isScanning) "Scanning…" else "Rescan")
            }
        }
    }
}

@Composable
private fun ScanMetaRow(report: ScanReport) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(PaddingValues(horizontal = 16.dp, vertical = 12.dp)),
            horizontalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            MetaItem("Policy", report.policyName)
            MetaItem("Duration", "${report.durationMs} ms")
            MetaItem("Files", report.scannedFiles.toString())
            MetaItem("Edges", report.edges.size.toString(), mono = true)
        }
    }
}

@Composable
private fun MetaItem(label: String, value: String, mono: Boolean = false) {
    Column {
        Text(
            text = label.uppercase(),
            color = mutedTextColor(),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            fontSize = 12.sp,
            fontFamily = if (mono) FontFamily.Monospace else null
        )
    }
}

internal fun verdictTitle(verdict: ScanVerdict): String = when (verdict) {
    ScanVerdict.NOT_SCANNED -> "Not Scanned"
    ScanVerdict.CLEAN -> "Clean"
    ScanVerdict.REVIEW_REQUIRED -> "Review Required"
    ScanVerdict.EXECUTION_BLOCKED -> "Execution Blocked"
    ScanVerdict.INCOMPLETE -> "Scan Incomplete"
    ScanVerdict.STALE -> "Scan Stale"
    ScanVerdict.FAILED -> "Scan Failed"
}

internal fun verdictSubtitle(report: ScanReport?): String = when {
    report == null -> "Run a scan to inspect this project."
    !report.complete -> "${report.notices.size} scanner notice(s) require review."
    report.blockers.isNotEmpty() -> "${report.blockers.size} finding(s) block execution."
    report.warnings.isNotEmpty() -> "${report.warnings.size} finding(s) need review."
    else -> "No actionable findings."
}
