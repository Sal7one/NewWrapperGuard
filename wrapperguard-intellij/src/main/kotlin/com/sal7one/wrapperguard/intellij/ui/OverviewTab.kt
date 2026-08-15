package com.sal7one.wrapperguard.intellij.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
 * (blocking findings, warnings, audit evidence, files scanned) plus a risk score.
 * Wrapped in a scroll container so it never clips at small tool-window heights.
 */
@Composable
fun OverviewTab(
    report: ScanReport?,
    isScanning: Boolean,
    onRescan: () -> Unit,
    onJumpToFindings: () -> Unit,
    onExport: () -> Unit = {}
) {
    ScrollableColumn {
        VerdictBanner(report, isScanning, onRescan, onExport)

        val blockers = report?.blockers?.size ?: 0
        val warnings = report?.warnings?.size ?: 0
        val audit = report?.auditEvidence?.size ?: 0
        val files = report?.scannedFiles ?: 0
        val risk = riskScore(report)
        val (riskLabel, riskTone) = riskLabel(risk)

        // Risk strip, then 2×2 metric grid with sublabels (per the design's tiles).
        MetricTile(
            label = "Risk Score — $riskLabel",
            count = risk,
            tone = riskTone,
            modifier = Modifier.fillMaxWidth().height(96.dp),
            sublabel = "errors×30 + warnings×10 + info×2"
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricTile(
                label = "Blocking Findings", count = blockers,
                tone = verdictColor(ScanVerdict.EXECUTION_BLOCKED),
                modifier = Modifier.weight(1f).height(96.dp),
                sublabel = "would block execution",
                onClick = if (blockers > 0) onJumpToFindings else null
            )
            MetricTile(
                label = "Warnings", count = warnings,
                tone = verdictColor(ScanVerdict.REVIEW_REQUIRED),
                modifier = Modifier.weight(1f).height(96.dp),
                sublabel = "need review"
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricTile(
                label = "Audit Evidence", count = audit,
                tone = verdictColor(ScanVerdict.CLEAN),
                modifier = Modifier.weight(1f).height(96.dp),
                sublabel = "non-actionable"
            )
            MetricTile(
                label = "Files Scanned", count = files,
                tone = policyActionColor(com.sal7one.wrapperguard.core.model.PolicyAction.ALLOW),
                modifier = Modifier.weight(1f).height(96.dp),
                sublabel = if (report?.complete == false) "incomplete" else "complete",
                trailing = {
                    // Thin scan-coverage bar (design shows a full-width progress bar here).
                    val progressTone = policyActionColor(com.sal7one.wrapperguard.core.model.PolicyAction.ALLOW)
                    val fraction = if (report?.complete == false) 0.85f else 1f
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                            .background(mutedTextColor().copy(alpha = 0.15f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction)
                                .height(4.dp)
                                .background(progressTone)
                        )
                    }
                }
            )
        }

        report?.let {
            ScanMetaRow(it)
        }
    }
}

@Composable
private fun VerdictBanner(report: ScanReport?, isScanning: Boolean, onRescan: () -> Unit, onExport: () -> Unit) {
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
                report?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Last scan: ${relativeTime(it.generatedAtEpochMs)} · ${it.policyName} · ${it.durationMs} ms",
                        color = mutedTextColor(),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            org.jetbrains.jewel.ui.component.OutlinedButton(onClick = onExport, enabled = report != null) {
                Text("Export JSON")
            }
            Spacer(Modifier.width(8.dp))
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

/**
 * Risk score, ported from the old UI: ERROR×30 + WARN×10 + INFO×2, clamped to 0–100.
 * Audit evidence (INFO, non-actionable) is included — it reflects the project's overall
 * risk surface, not the verdict.
 */
internal fun riskScore(report: ScanReport?): Int {
    if (report == null) return 0
    val errors = report.findings.count { it.severity == com.sal7one.wrapperguard.core.model.Severity.ERROR }
    val warns = report.findings.count { it.severity == com.sal7one.wrapperguard.core.model.Severity.WARN }
    val infos = report.findings.count { it.severity == com.sal7one.wrapperguard.core.model.Severity.INFO }
    return (errors * 30 + warns * 10 + infos * 2).coerceIn(0, 100)
}

@Composable
internal fun riskLabel(score: Int): Pair<String, androidx.compose.ui.graphics.Color> = when {
    score >= 70 -> "High" to verdictColor(ScanVerdict.EXECUTION_BLOCKED)
    score >= 25 -> "Medium" to verdictColor(ScanVerdict.REVIEW_REQUIRED)
    score > 0 -> "Low" to verdictColor(ScanVerdict.CLEAN)
    else -> "Clean" to mutedTextColor()
}

/** Compact relative time like "just now", "2m ago", "1h ago". */
internal fun relativeTime(epochMs: Long): String {
    val delta = System.currentTimeMillis() - epochMs
    return when {
        delta < 5_000 -> "just now"
        delta < 60_000 -> "${delta / 1000}s ago"
        delta < 3_600_000 -> "${delta / 60_000}m ago"
        delta < 86_400_000 -> "${delta / 3_600_000}h ago"
        else -> "${delta / 86_400_000}d ago"
    }
}
