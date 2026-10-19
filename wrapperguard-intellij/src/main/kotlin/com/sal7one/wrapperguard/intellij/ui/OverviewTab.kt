package com.sal7one.wrapperguard.intellij.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.wrapperguard.core.model.*
import com.sal7one.wrapperguard.core.vetting.ProviderRunStatus
import com.sal7one.wrapperguard.intellij.ui.components.*
import org.jetbrains.jewel.ui.component.Text

@Composable
internal fun OverviewTab(report: ScanReport, onFindings: (FindingScope) -> Unit) {
    val reviews = report.findings.count { it.status == FindingStatus.ACTIVE && it.policyAction == PolicyAction.REVIEW }
    val suppressed = report.findings.count { it.status == FindingStatus.SUPPRESSED }
    var noticesExpanded by remember { mutableStateOf(false) }
    WorkspacePage {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val context: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("LATEST SCAN", color = mutedTextColor(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
                        Text(verdictTitle(displayVerdict(report)), color = verdictColor(displayVerdict(report)), fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                        Text(verdictSubtitle(report), color = mutedTextColor(), fontSize = 12.sp)
                    }
                    WorkspaceDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                        MetadataRow("Policy", report.policyName)
                        MetadataRow("Files inspected", report.scannedFiles.toString())
                        MetadataRow("Scan duration", if (report.durationMs >= 1000) "${"%.2f".format(report.durationMs / 1000.0)} s" else "${report.durationMs} ms")
                        MetadataRow("Completed", java.time.Instant.ofEpochMilli(report.generatedAtEpochMs)
                            .atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss z")))
                    }
                    if (report.notices.isNotEmpty()) {
                        WorkspaceDivider()
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Coverage", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            report.providerRuns.filter { it.status == ProviderRunStatus.FAILED || it.status == ProviderRunStatus.TIMED_OUT }.forEach { run ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(run.descriptor.displayName, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(providerStatusLabel(run.status), color = warningColor(), fontSize = 11.sp)
                                }
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("${report.notices.size} notices", color = mutedTextColor(), fontSize = 11.sp, modifier = Modifier.weight(1f))
                                Text(if (noticesExpanded) "Hide diagnostics ↑" else "Show diagnostics ↓", color = accentColor(), fontSize = 11.sp,
                                    modifier = Modifier.clickable(role = Role.Button) { noticesExpanded = !noticesExpanded }.padding(vertical = 4.dp))
                            }
                            if (noticesExpanded) report.notices.forEach { notice ->
                                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text(notice.code, color = warningColor(), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                    Text(notice.message, fontSize = 12.sp, color = mutedTextColor())
                                    notice.location?.let { Text(it.display, color = mutedTextColor(), fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                                }
                            }
                        }
                    }
                    WorkspaceDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Protection scope", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Text("IDE runs and Gradle sync are checked before execution. External terminals run outside this guard.", fontSize = 11.sp, color = mutedTextColor())
                        Text("Third-party providers run trusted code with your permissions.", fontSize = 11.sp, color = mutedTextColor())
                    }
                }
            }
            val content: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Project review", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                            Text("${report.scannedFiles} files inspected · ${report.providerRuns.size} providers configured", fontSize = 12.sp, color = mutedTextColor())
                        }
                        StatusBadge(if (report.complete) "Coverage complete" else "Coverage incomplete", if (report.complete) successColor() else warningColor())
                    }
                    Row(Modifier.fillMaxWidth().background(cardSurfaceColor(), RoundedCornerShape(4.dp)).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        SummaryCount("Blocking", report.blockers.size, dangerColor(), Modifier.weight(1f)) { onFindings(FindingScope.BLOCKERS) }
                        Box(Modifier.width(1.dp).height(32.dp).background(outlineColor()))
                        SummaryCount("Needs review", reviews, warningColor(), Modifier.weight(1f)) { onFindings(FindingScope.REVIEW) }
                        Box(Modifier.width(1.dp).height(32.dp).background(outlineColor()))
                        SummaryCount("Suppressed", suppressed, mutedTextColor(), Modifier.weight(1f)) { onFindings(FindingScope.SUPPRESSED) }
                        Box(Modifier.width(1.dp).height(32.dp).background(outlineColor()))
                        SummaryCount("Total findings", report.findings.size, accentColor(), Modifier.weight(1f)) { onFindings(FindingScope.ALL) }
                    }
                    WorkspacePanel("Requires attention", trailing = {
                        Text("All findings →", color = accentColor(), fontSize = 11.sp, modifier = Modifier.clickable { onFindings(FindingScope.ALL) }.padding(4.dp))
                    }) {
                        val attention = visibleFindings(report.findings, FindingsViewState())
                            .filter { it.status == FindingStatus.ACTIVE && it.policyAction != PolicyAction.ALLOW }.take(5)
                        if (attention.isEmpty()) EmptyHint("No active findings require action under the current policy.")
                        attention.forEachIndexed { index, finding ->
                            Row(Modifier.fillMaxWidth().clickable {
                                onFindings(if (finding.policyAction == PolicyAction.BLOCK) FindingScope.BLOCKERS else FindingScope.REVIEW)
                            }.padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(11.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (finding.policyAction == PolicyAction.BLOCK) "⊘" else "△", color = policyActionColor(finding.policyAction), fontSize = 17.sp)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(finding.title, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(finding.location.display, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = mutedTextColor(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                StatusBadge(if (finding.policyAction == PolicyAction.BLOCK) "Block" else "Review", policyActionColor(finding.policyAction))
                            }
                            if (index < attention.lastIndex) WorkspaceDivider()
                        }
                    }
                    WorkspacePanel("Provider health", trailing = {
                        Text("${report.providerRuns.count { it.status == ProviderRunStatus.COMPLETED }} completed", color = mutedTextColor(), fontSize = 11.sp)
                    }) {
                        if (report.providerRuns.isEmpty()) EmptyHint("Provider details are unavailable for this report.")
                        report.providerRuns.forEachIndexed { index, run ->
                            val tone = when (run.status) { ProviderRunStatus.COMPLETED -> successColor(); ProviderRunStatus.DISABLED -> mutedTextColor(); else -> warningColor() }
                            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(Modifier.size(5.dp).background(tone, RoundedCornerShape(3.dp)))
                                Text(run.descriptor.displayName, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(providerStatusLabel(run.status), color = tone, fontSize = 11.sp, modifier = Modifier.width(76.dp))
                                Text(if (run.status == ProviderRunStatus.COMPLETED) "${run.findingCount} findings" else "—", color = mutedTextColor(), fontSize = 11.sp, modifier = Modifier.width(66.dp))
                            }
                            if (index < report.providerRuns.lastIndex) WorkspaceDivider()
                        }
                    }
                }
            }
            if (maxWidth >= 860.dp) Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Box(Modifier.width(250.dp)) { context() }
                Box(Modifier.width(1.dp).heightIn(min = 620.dp).background(outlineColor()))
                Box(Modifier.weight(1f)) { content() }
            } else Column(verticalArrangement = Arrangement.spacedBy(20.dp)) { content(); WorkspaceDivider(); context() }
        }
    }
}

@Composable
private fun SummaryCount(label: String, count: Int, tone: androidx.compose.ui.graphics.Color, modifier: Modifier, onClick: () -> Unit) {
    Row(modifier.clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(count.toString(), color = tone, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Text(label, color = mutedTextColor(), fontSize = 11.sp, maxLines = 2)
    }
}

@Composable internal fun MetadataRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, color = mutedTextColor(), fontSize = 11.sp, modifier = Modifier.weight(1f))
        Text(value, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
    }
}

internal fun providerStatusLabel(status: ProviderRunStatus): String = when (status) {
    ProviderRunStatus.COMPLETED -> "Completed"
    ProviderRunStatus.FAILED -> "Failed"
    ProviderRunStatus.TIMED_OUT -> "Timed out"
    ProviderRunStatus.DISABLED -> "Disabled"
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
    !report.complete -> "Some providers or files could not be examined. Review coverage notices."
    report.blockers.isNotEmpty() -> "${report.blockers.size} active findings require action before execution."
    report.findings.any { it.status == FindingStatus.ACTIVE && it.policyAction == PolicyAction.REVIEW } -> "Review the active findings before running this project."
    else -> "No active findings require action under this scan's policy."
}
