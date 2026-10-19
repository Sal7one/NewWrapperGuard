package com.sal7one.wrapperguard.intellij.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.sal7one.wrapperguard.intellij.demo.WrapperGuardDemo
import com.sal7one.wrapperguard.intellij.ui.components.*
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private enum class WgTab(val label: String) {
    OVERVIEW("Summary"), FINDINGS("Findings"), GRAPH("Execution"), POLICY("Policy")
}

private data class WorkspaceSnapshot(val report: ScanReport?, val scanning: Boolean, val error: String?)

private fun requestedDemoTab(value: String?): WgTab = when (value) {
    "findings" -> WgTab.FINDINGS
    "execution", "graph" -> WgTab.GRAPH
    "policy" -> WgTab.POLICY
    else -> WgTab.OVERVIEW
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WrapperGuardApp(service: WrapperGuardProjectService, project: Project) {
    fun snapshot(report: ScanReport?) = WorkspaceSnapshot(
        report?.let { if (service.stale) it.copy(stale = true) else it }, service.isScanning, service.scanError)
    var state by remember { mutableStateOf(snapshot(service.report)) }
    var activeTab by remember { mutableStateOf(requestedDemoTab(WrapperGuardDemo.initialTab())) }
    var findingsState by remember { mutableStateOf(FindingsViewState()) }
    fun displaySettings() = (if (service.isDemo) WrapperGuardDemo.loadDisplaySettings() else null) ?: buildScanSettings()
    var settings by remember { mutableStateOf(displaySettings()) }
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
    LaunchedEffect(report) {
        if (service.isDemo && findingsState.selected == null) {
            findingsState = findingsState.copy(selected = report?.let { visibleFindings(it.findings, FindingsViewState()).firstOrNull() })
        }
    }
    LaunchedEffect(service.isDemo) {
        if (!service.isDemo) return@LaunchedEffect
        var lastCommand: String? = null
        while (true) {
            val command = withContext(Dispatchers.IO) { WrapperGuardDemo.requestedTab() }
            if (command != null && command != lastCommand) {
                activeTab = requestedDemoTab(command)
                lastCommand = command
            }
            delay(250)
        }
    }
    val verdict = displayVerdict(report)
    val openSettings: () -> Unit = {
        if (!service.isDemo) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, "WrapperGuard")
            settings = buildScanSettings()
        }
    }
    val showFinding: (com.sal7one.wrapperguard.core.model.Finding) -> Unit = { finding ->
        findingsState = FindingsViewState(selected = finding)
        activeTab = WgTab.FINDINGS
    }

    val statusTone = if (state.error != null) dangerColor() else verdictColor(verdict)
    val identity: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            val tone = accentColor()
            Canvas(Modifier.size(22.dp)) {
                val w = size.width; val h = size.height
                val shield = Path().apply {
                    moveTo(w * .5f, h * .06f); lineTo(w * .86f, h * .2f); lineTo(w * .82f, h * .56f)
                    quadraticBezierTo(w * .77f, h * .8f, w * .5f, h * .95f)
                    quadraticBezierTo(w * .23f, h * .8f, w * .18f, h * .56f)
                    lineTo(w * .14f, h * .2f); close()
                }
                drawPath(shield, tone, style = Stroke(width = w * .065f))
                drawLine(tone, Offset(w * .33f, h * .48f), Offset(w * .46f, h * .6f), strokeWidth = w * .075f, cap = StrokeCap.Round)
                drawLine(tone, Offset(w * .46f, h * .6f), Offset(w * .69f, h * .36f), strokeWidth = w * .075f, cap = StrokeCap.Round)
            }
            Text(if (service.isDemo) WrapperGuardDemo.projectName else project.name,
                fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    val tabs: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WgTab.entries.forEach { tab ->
                WorkspaceTab(tab.label, activeTab == tab, if (tab == WgTab.FINDINGS) report?.findings?.size else null) {
                    activeTab = tab
                    if (tab == WgTab.POLICY) settings = displaySettings()
                }
            }
        }
    }
    val status: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(6.dp).background(statusTone, RoundedCornerShape(3.dp)))
            Text(if (state.scanning) "Scanning…" else if (state.error != null) "Scan failed" else verdictTitle(verdict),
                color = statusTone, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
    val actions: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { report?.let { exportReportJson(project, it) } }, enabled = report != null) { Text("Export", fontSize = 11.sp) }
            OutlinedButton(onClick = openSettings, enabled = !service.isDemo) { Text("Settings", fontSize = 11.sp) }
            WorkspacePrimaryButton(if (service.isDemo) "↻  Reload demo" else if (state.scanning) "Scanning…" else "↻  Scan project",
                enabled = !state.scanning, onClick = { service.scanAsync() })
        }
    }
    Column(Modifier.fillMaxSize().background(workspaceBackground())) {
        BoxWithConstraints(Modifier.fillMaxWidth().background(cardSurfaceColor())) {
            if (maxWidth >= 1120.dp) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Box(Modifier.widthIn(max = 185.dp).padding(vertical = 9.dp)) { identity() }
                    Box(Modifier.width(1.dp).height(22.dp).background(outlineColor()))
                    tabs()
                    Spacer(Modifier.weight(1f))
                    status()
                    actions()
                }
            } else Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { identity() }; actions()
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    tabs(); Spacer(Modifier.weight(1f)); Box(Modifier.padding(end = 14.dp)) { status() }
                }
            }
        }
        WorkspaceDivider()
        if (state.error != null || report != null && (report.stale || state.scanning)) {
            Text(state.error ?: "Showing previous scan results until a new scan completes.",
                modifier = Modifier.fillMaxWidth().background(statusTone.copy(alpha = 0.06f)).padding(horizontal = 16.dp, vertical = 6.dp),
                fontSize = 11.sp, color = statusTone)
            WorkspaceDivider()
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                activeTab == WgTab.POLICY -> PolicyTab(settings, report, openSettings, demo = service.isDemo)
                report == null -> WorkspacePage {
                    WorkspacePanel(if (state.scanning) "Inspecting project files" else "Start with a project scan") {
                        Text("WrapperGuard checks Gradle wrappers, build files and the scripts they call. Findings and execution paths appear here after scanning.", fontSize = 13.sp, color = mutedTextColor(), modifier = Modifier.padding(16.dp))
                    }
                }
                else -> when (activeTab) {
                    WgTab.OVERVIEW -> OverviewTab(report) { scope ->
                        findingsState = FindingsViewState(scope = scope)
                        activeTab = WgTab.FINDINGS
                    }
                    WgTab.FINDINGS -> FindingsTab(report.findings, findingsState, { findingsState = it },
                        { openFindingInEditor(project, it, report.projectPath) },
                        providerNames = report.providerRuns.associate { it.descriptor.id to it.descriptor.displayName })
                    WgTab.GRAPH -> ExecutionGraphTab(report.edges, report.findings, onOpenFinding = showFinding,
                        onOpenFile = { openFindingInEditor(project, it, report.projectPath) })
                    WgTab.POLICY -> Unit
                }
            }
        }
        if (service.isDemo) {
            WorkspaceDivider()
            Row(Modifier.fillMaxWidth().background(cardSurfaceColor()).padding(horizontal = 16.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("DEMO WORKSPACE", fontSize = 10.sp, fontWeight = FontWeight.Medium, color = accentColor())
                Text("  ·  Simulated data  ·  Execution disabled", fontSize = 10.sp, color = mutedTextColor())
                Spacer(Modifier.weight(1f))
                Text("Atlas Payments / baseline", fontSize = 10.sp, color = mutedTextColor())
            }
        }
    }
}
