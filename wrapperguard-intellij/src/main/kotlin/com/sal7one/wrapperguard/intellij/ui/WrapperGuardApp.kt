package com.sal7one.wrapperguard.intellij.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.wrapperguard.core.model.Finding
import com.sal7one.wrapperguard.core.model.ScanReport
import com.sal7one.wrapperguard.core.model.ScanVerdict
import com.sal7one.wrapperguard.intellij.WrapperGuardProjectService
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

/**
 * Jewel proof-of-life UI. Intentionally small — the full Overview/Findings/Graph/Policy
 * layout is a later iteration. This view proves:
 *  - Compose mounts under SwingBridgeTheme (light + dark IDE themes).
 *  - The tool window reads the shared scanner report from [WrapperGuardProjectService].
 *  - Selecting a finding updates a details area.
 *  - A "Rescan" button triggers a scan and the new state flows back into Compose.
 */
@Composable
fun WrapperGuardApp(service: WrapperGuardProjectService) {
    // Observe the service's report as Compose state.
    var report by remember { mutableStateOf<ScanReport?>(service.report) }
    DisposableEffect(service) {
        val listener: (ScanReport?) -> Unit = { report = it }
        service.addListener(listener)
        onDispose { service.removeListener(listener) }
    }
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    val selected: Finding? = selectedIndex?.let { report?.findings?.getOrNull(it) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(PaddingValues(16.dp))
    ) {
        VerdictHeader(report)

        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                selectedIndex = null
                // Runs off the EDT; the service notifies listeners when done, which
                // updates `report` above and re-renders Compose.
                service.scanAsync()
            }) {
                Text("Rescan")
            }
            Spacer(Modifier.width(12.dp))
            Text(
                report?.let { "${it.findings.size} finding(s) · ${it.scannedFiles} file(s) scanned" }
                    ?: "Not scanned yet",
                fontSize = 12.sp
            )
        }

        Spacer(Modifier.height(16.dp))

        // Two-pane: findings list (left) + selected finding detail (right).
        Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FindingsList(
                findings = report?.findings ?: emptyList(),
                selectedIndex = selectedIndex,
                onSelect = { index ->
                    selectedIndex = if (selectedIndex == index) null else index
                },
                modifier = Modifier.weight(1f).fillMaxSize()
            )
            FindingDetail(
                finding = selected,
                modifier = Modifier.weight(1f).fillMaxSize()
            )
        }
    }
}

@Composable
private fun VerdictHeader(report: ScanReport?) {
    val verdict = report?.verdict ?: ScanVerdict.NOT_SCANNED
    Column {
        Text(
            verdictTitle(verdict),
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = verdictTone(verdict)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            verdictSubtitle(report),
            fontSize = 12.sp
        )
    }
}

@Composable
private fun FindingsList(
    findings: List<Finding>,
    selectedIndex: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (findings.isEmpty()) {
        Box(
            modifier = modifier
                .border(1.dp, outlineColor(), RoundedCornerShape(4.dp))
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("No findings", fontSize = 12.sp)
        }
        return
    }
    LazyColumn(
        modifier = modifier.border(1.dp, outlineColor(), RoundedCornerShape(4.dp)),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        items(
            count = findings.size,
            key = { index -> findings[index].fingerprint }
        ) { index ->
            val finding = findings[index]
            val selected = index == selectedIndex
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (selected) selectionColor() else Color.Unspecified)
                    .clickable { onSelect(index) }
                    .padding(PaddingValues(horizontal = 12.dp, vertical = 8.dp)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SeverityDot(severity = finding.severity.name)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(finding.title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text(
                        "${finding.ruleId} · ${finding.location.display}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun FindingDetail(finding: Finding?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .border(1.dp, outlineColor(), RoundedCornerShape(4.dp))
            .padding(PaddingValues(16.dp))
    ) {
        if (finding == null) {
            Text("Select a finding to inspect evidence and remediation.", fontSize = 12.sp)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(finding.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "${finding.severity} · ${finding.confidence} · ${finding.policyAction}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    finding.location.display,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(6.dp))
                Text(finding.message, fontSize = 12.sp)
                finding.evidence?.let { evidence ->
                    Spacer(Modifier.height(6.dp))
                    Text("Evidence", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        evidence.text,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, outlineColor(), RoundedCornerShape(4.dp))
                            .padding(8.dp)
                    )
                }
                finding.remediation?.let {
                    Spacer(Modifier.height(6.dp))
                    Text("Remediation", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text(it, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun SeverityDot(severity: String) {
    val color = when (severity) {
        "ERROR" -> Color(0xFFBA1A1A)
        "WARN" -> Color(0xFFB45309)
        else -> Color(0xFF6B7280)
    }
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(color, RoundedCornerShape(50))
    )
}

private fun verdictTitle(verdict: ScanVerdict): String = when (verdict) {
    ScanVerdict.NOT_SCANNED -> "Not Scanned"
    ScanVerdict.CLEAN -> "Clean"
    ScanVerdict.REVIEW_REQUIRED -> "Review Required"
    ScanVerdict.EXECUTION_BLOCKED -> "Execution Blocked"
    ScanVerdict.INCOMPLETE -> "Scan Incomplete"
    ScanVerdict.STALE -> "Scan Stale"
    ScanVerdict.FAILED -> "Scan Failed"
}

private fun verdictSubtitle(report: ScanReport?): String = when {
    report == null -> "Run a scan to inspect this project."
    !report.complete -> "${report.notices.size} scanner notice(s) require review."
    report.blockers.isNotEmpty() -> "${report.blockers.size} finding(s) block execution."
    report.warnings.isNotEmpty() -> "${report.warnings.size} finding(s) need review."
    else -> "No actionable findings."
}

@Composable
private fun verdictTone(verdict: ScanVerdict): Color = when (verdict) {
    ScanVerdict.EXECUTION_BLOCKED -> Color(0xFFBA1A1A)
    ScanVerdict.REVIEW_REQUIRED -> Color(0xFFB45309)
    ScanVerdict.FAILED, ScanVerdict.INCOMPLETE -> Color(0xFFB45309)
    ScanVerdict.CLEAN -> Color(0xFF1E6B3C)
    else -> Color(0xFF6B7280)
}

@Composable
private fun outlineColor(): Color = Color(0xFF918FA1)

@Composable
private fun selectionColor(): Color = Color(0xFF2A2933)
