package com.sal7one.wrapperguard.intellij.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.sal7one.wrapperguard.core.model.ScanReport
import com.sal7one.wrapperguard.core.model.ScanVerdict
import com.sal7one.wrapperguard.intellij.WrapperGuardProjectService
import com.sal7one.wrapperguard.intellij.settings.buildScanSettings
import com.sal7one.wrapperguard.intellij.ui.components.*
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

private enum class WgTab(val label: String) {
    OVERVIEW("Summary"), FINDINGS("Findings"), GRAPH("Execution"), POLICY("Policy")
}

private data class WorkspaceSnapshot(val report: ScanReport?, val scanning: Boolean, val error: String?)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WrapperGuardApp(service: WrapperGuardProjectService, project: Project) {
    fun snapshot(report: ScanReport?) = WorkspaceSnapshot(
        report?.let { if (service.stale) it.copy(stale = true) else it }, service.isScanning, service.scanError)
    var state by remember { mutableStateOf(snapshot(service.report)) }
    var activeTab by remember { mutableStateOf(WgTab.OVERVIEW) }
    var findingsState by remember { mutableStateOf(FindingsViewState()) }
    var settings by remember { mutableStateOf(buildScanSettings()) }
    DisposableEffect(service) {
        var disposed = false
        val listener: (ScanReport?) -> Unit = { newReport ->
            val update = snapshot(newReport)
            ApplicationManager.getApplication().invokeLater {
                if (!disposed && !project.isDisposed) state = update
            }
        }
        service.addListener(listener)
        service.scanIfNoReport()
        onDispose { disposed = true; service.removeListener(listener) }
    }
    val report = state.report
    val verdict = displayVerdict(report)
    val openSettings: () -> Unit = {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, "WrapperGuard")
        settings = buildScanSettings()
    }
    val showFinding: (com.sal7one.wrapperguard.core.model.Finding) -> Unit = { finding ->
        findingsState = FindingsViewState(selected = finding)
        activeTab = WgTab.FINDINGS
    }

    Column(Modifier.fillMaxSize().background(JewelTheme.globalColors.toolwindowBackground)) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val actions: @Composable () -> Unit = {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { service.scanAsync() }, enabled = !state.scanning) {
                            Text(if (state.scanning) "Scanning…" else "Scan project")
                        }
                        OutlinedButton(onClick = { report?.let { exportReportJson(project, it) } }, enabled = report != null) { Text("Export") }
                        OutlinedButton(onClick = openSettings) { Text("Settings") }
                    }
                }
                val identity: @Composable () -> Unit = {
                    Text("WrapperGuard · ${project.name}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (maxWidth >= 600.dp) Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(1f)) { identity() }
                    actions()
                } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { identity(); actions() }
            }
            Text(
                when { state.scanning -> "Scanning project files…"; state.error != null -> state.error!!; else -> verdictTitle(verdict) },
                color = if (state.error != null) verdictColor(ScanVerdict.FAILED) else verdictColor(verdict),
                fontSize = 12.sp, fontWeight = FontWeight.Medium)
            if (report != null && (report.stale || state.scanning || state.error != null)) {
                Text("Showing previous scan results until a new scan completes.", fontSize = 11.sp, color = mutedTextColor())
            }
        }
        WorkspaceDivider()
        FlowRow(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            WgTab.entries.forEach { tab ->
                ChoiceChip(tab.label, activeTab == tab) {
                    activeTab = tab
                    if (tab == WgTab.POLICY) settings = buildScanSettings()
                }
            }
        }
        WorkspaceDivider()
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                activeTab == WgTab.POLICY -> PolicyTab(settings, openSettings)
                report == null -> WorkspacePage {
                    Text(if (state.scanning) "Inspecting project files" else "Start with a project scan", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("WrapperGuard checks Gradle wrappers, build files and the scripts they call. Findings and execution paths appear here after scanning.", fontSize = 12.sp)
                }
                else -> when (activeTab) {
                    WgTab.OVERVIEW -> OverviewTab(report) { scope ->
                        findingsState = FindingsViewState(scope = scope)
                        activeTab = WgTab.FINDINGS
                    }
                    WgTab.FINDINGS -> FindingsTab(report.findings, findingsState, { findingsState = it },
                        { openFindingInEditor(project, it) })
                    WgTab.GRAPH -> ExecutionGraphTab(report.edges, report.findings, onOpenFinding = showFinding,
                        onOpenFile = { openFindingInEditor(project, it) })
                    WgTab.POLICY -> Unit
                }
            }
        }
    }
}
