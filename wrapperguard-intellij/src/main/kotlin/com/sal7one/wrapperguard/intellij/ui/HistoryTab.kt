package com.sal7one.wrapperguard.intellij.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.sal7one.wrapperguard.core.history.*
import com.sal7one.wrapperguard.intellij.settings.*
import com.sal7one.wrapperguard.intellij.ui.components.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Summaries are local review context, never an approval or enforcement baseline. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HistoryTab(project: Project, scanGeneration: Long?, demo: Boolean, saveError: String?) {
    val settings = WrapperGuardSettings.getInstance().state
    var enabled by remember { mutableStateOf(settings.historyEnabled) }
    var retention by remember { mutableStateOf(settings.historyLimit) }
    var revision by remember { mutableStateOf(0) }
    var entries by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val coroutine = rememberCoroutineScope()
    LaunchedEffect(scanGeneration, revision, demo) {
        if (demo) return@LaunchedEffect
        busy = true
        val result = withContext(Dispatchers.IO) { runCatching { NativeHistory.list(project) } }
        result.onSuccess { entries = it; if (selectedId !in it.map { value -> value.id }) selectedId = it.firstOrNull()?.id }
            .onFailure { error = "Local history could not be read. Review the IDE configuration directory and retry." }
        busy = false
    }
    WorkspacePage {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Local scan history", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("Review changes in what providers reported for this project.", fontSize = 12.sp, color = mutedTextColor())
            }
            OutlinedButton(enabled = !demo, onClick = { enabled = !enabled; settings.historyEnabled = enabled }) { Text(if (enabled) "Pause recording" else "Enable local history", fontSize = 11.sp) }
        }
        WorkspacePanel(if (enabled) "Recording enabled" else "Recording is off", subtitle = "Local to this IDE · separate from approvals and build/sync decisions") {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("Opting in saves finding fingerprints, issue titles, relative paths, statuses, provider versions and scan outcomes in your IDE configuration. Titles may contain data supplied by a provider. Source text, finding messages, evidence and call chains are excluded.", fontSize = 12.sp)
                Text(if (enabled) "The next scan, build or sync records a bounded summary. Pausing stops new records and retains existing summaries." else "Enable recording to save future summaries. No previous scans are reconstructed.", fontSize = 11.sp, color = mutedTextColor())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Keep per project", fontSize = 11.sp, color = mutedTextColor(), modifier = Modifier.padding(top = 6.dp, end = 6.dp))
                    listOf(10, 20, 50, 100).forEach { count -> ChoiceChip("$count scans", retention == count) { if (!demo) { retention = count; settings.historyLimit = count } } }
                    OutlinedButton(enabled = !demo && !busy, onClick = { revision++ }) { Text("Refresh", fontSize = 11.sp) }
                    OutlinedButton(enabled = !demo && !busy && entries.isNotEmpty(), onClick = {
                        if (Messages.showYesNoDialog(project, "Remove saved WrapperGuard summaries for this project? Current findings, approvals and provider settings are unchanged.", "Clear local history", Messages.getQuestionIcon()) == Messages.YES) {
                            busy = true
                            coroutine.launch {
                                withContext(Dispatchers.IO) { runCatching { NativeHistory.clear(project) } }
                                    .onSuccess { revision++ }.onFailure { error = "History could not be cleared. Existing summaries were preserved where possible." }
                                busy = false
                            }
                        }
                    }) { Text("Clear this project…", fontSize = 11.sp) }
                }
            }
        }
        if (demo) Text("Demo history is read-only. Simulated scans never create saved records.", fontSize = 11.sp, color = mutedTextColor())
        (error ?: saveError)?.let { Text(it, color = warningColor(), fontSize = 12.sp) }
        if (busy) Text("Reading local summaries…", color = mutedTextColor(), fontSize = 12.sp)
        if (entries.isEmpty()) WorkspacePanel("No saved summaries") { Text("Enable recording and run a new scan. History stays separate for each canonical project root.", Modifier.padding(14.dp), fontSize = 12.sp, color = mutedTextColor()) }
        else {
            WorkspacePanel("Saved scans", subtitle = "${entries.size} summaries · newest first") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("WHEN / OPERATION", Modifier.weight(1f), color = mutedTextColor(), fontSize = 10.sp)
                    Text("RESULT", Modifier.width(150.dp), color = mutedTextColor(), fontSize = 10.sp)
                    Text("ISSUES", Modifier.width(52.dp), color = mutedTextColor(), fontSize = 10.sp)
                }
                entries.forEach { entry ->
                    WorkspaceDivider()
                    Row(Modifier.fillMaxWidth().background(if (selectedId == entry.id) accentColor().copy(alpha = 0.08f) else cardSurfaceColor())
                        .clickable { selectedId = entry.id }.padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(historyTime(entry.createdAt), fontSize = 12.sp)
                            Text(entry.operation.lowercase().replaceFirstChar(Char::titlecase) + if (entry.scopeKnown) " · declared scope" else " · scope unknown", fontSize = 10.sp, color = mutedTextColor())
                        }
                        Text(entry.verdict.replace('_', ' ').lowercase().replaceFirstChar(Char::titlecase), Modifier.width(150.dp), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(entry.findings.size.toString() + if (entry.truncated) "+" else "", Modifier.width(52.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
            }
            val selected = entries.firstOrNull { it.id == selectedId } ?: entries.first()
            val previous = entries.dropWhile { it.id != selected.id }.drop(1).firstOrNull { it.operation == selected.operation }
            val delta = previous?.let { HistoryDelta.compare(it, selected) }
            WorkspacePanel("Reported changes", subtitle = "Compared with the preceding ${selected.operation.lowercase()} summary") {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Comparison requires a verified scope. Imported reports and private SDK, Python package or model settings may make scope unknown. Absence means no longer reported in a comparable scan; it does not prove a vulnerability is fixed. Approval changes retain the same finding identity.", fontSize = 11.sp, color = mutedTextColor())
                    when {
                        delta == null -> Text("A preceding summary for this operation is needed.", fontSize = 12.sp, color = mutedTextColor())
                        !delta.available -> Text(delta.reason ?: "These scans cannot be compared safely.", fontSize = 12.sp, color = warningColor())
                        else -> {
                            Text("${delta.newlyReported.size} newly reported · ${delta.noLongerReported.size} no longer reported · ${delta.unchanged} retained", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            HistoryFindingList("Newly reported", delta.newlyReported)
                            HistoryFindingList("No longer reported", delta.noLongerReported)
                        }
                    }
                }
            }
        }
    }
}

private fun historyTime(value: Long): String = DateTimeFormatter.ofPattern("MMM d, HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(value))
@Composable private fun HistoryFindingList(label: String, findings: List<HistoryFinding>) {
    if (findings.isEmpty()) return
    Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    findings.take(50).forEach { finding ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(finding.severity, fontSize = 10.sp, color = mutedTextColor(), modifier = Modifier.width(44.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(finding.title, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${finding.providerId} · ${finding.ruleId} · ${finding.path}", fontSize = 10.sp, color = mutedTextColor(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (findings.size > 50) Text("${findings.size - 50} additional entries in the saved summary.", fontSize = 11.sp, color = mutedTextColor())
}
