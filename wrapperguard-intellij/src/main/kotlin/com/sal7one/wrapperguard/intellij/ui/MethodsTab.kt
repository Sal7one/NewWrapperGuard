package com.sal7one.wrapperguard.intellij.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.wrapperguard.core.methods.*
import com.sal7one.wrapperguard.core.model.ScanReport
import com.sal7one.wrapperguard.core.policy.ScanSettings
import com.sal7one.wrapperguard.core.vetting.ProviderRunStatus
import com.sal7one.wrapperguard.intellij.settings.*
import com.sal7one.wrapperguard.intellij.ui.components.*
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import java.nio.file.Files
import java.nio.file.Path

/** Catalog preferences and setup are application-owned, independent from repository policy. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MethodsTab(settings: ScanSettings, report: ScanReport?, revision: Int, research: Boolean, demo: Boolean,
    onSetup: (SecurityMethod) -> Unit, onEnabled: (SecurityMethod, Boolean) -> Unit,
    onPermissions: (SecurityMethod) -> Unit, onInstallSdk: () -> Unit, onGuide: (SecurityMethod) -> Unit) {
    var category by remember { mutableStateOf<MethodCategory?>(null) }
    val state = remember(revision, demo, settings) {
        if (demo) WrapperGuardSettings.State(methodFlags = settings.methodFlags.toMutableMap())
        else WrapperGuardSettings.getInstance().state.copy(methodFlags = WrapperGuardSettings.getInstance().state.methodFlags.toMutableMap(),
            methodConnections = WrapperGuardSettings.getInstance().state.methodConnections.map { it.copy() }.toMutableList(),
            providers = WrapperGuardSettings.getInstance().state.providers.map { it.copy() }.toMutableList())
    }
    val all = SecurityMethodCatalog.methods.filter { (it.category in setOf(MethodCategory.RESEARCH, MethodCategory.AI)) == research }
    val methods = all.filter { if (research) true else if (category == null) it.category != MethodCategory.BUILT_IN else it.category == category }
    WorkspacePage {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(if (research) "Research & local AI" else "Analysis tools", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(if (research) "Optional advisory methods. Choose the analyzer and review its limits." else "Connect independent analyzers and choose the methods used in your workspace.", color = mutedTextColor(), fontSize = 12.sp)
            }
            OutlinedButton(onClick = onInstallSdk, enabled = !demo) { Text("Install SDK…", fontSize = 12.sp) }
        }
        if (!research) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ChoiceChip("Connections", category == null) { category = null }
            listOf(MethodCategory.BUILT_IN to "Built-in", MethodCategory.SAST to "SAST", MethodCategory.SUPPLY_CHAIN to "Supply chain", MethodCategory.DAST to "DAST").forEach { (value, label) ->
                ChoiceChip("$label  ${all.count { it.category == value }}", category == value) { category = value }
            }
        }
        if (research) WorkspacePanel("Advisory by design", subtitle = "No trained detector or model is bundled") {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Script behavior signals use bounded static decoding and linked patterns. Local AI reviews a minimized finding report through a model server you select. Neither method executes project source or creates build/sync blockers.", fontSize = 12.sp)
                Text("Experimental signals need your review. Research references provide context; this integration does not reproduce, train or claim the accuracy of a paper's model.", color = mutedTextColor(), fontSize = 11.sp)
            }
        } else if (category != MethodCategory.BUILT_IN) {
            Row(Modifier.fillMaxWidth().background(insetSurfaceColor()).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("SETUP", color = accentColor(), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                Text("Install the bundled SDK, connect an installed analyzer or existing report, then enable warnings. Permissions are reviewed separately.", color = mutedTextColor(), fontSize = 11.sp, modifier = Modifier.weight(1f))
            }
        }
        if (category == MethodCategory.BUILT_IN && !research) {
            WorkspacePanel("Built-in methods", subtitle = "Included locally · changes apply to all projects") {
                methods.forEachIndexed { index, method ->
                    val enabled = SecurityMethodCatalog.isEnabled(method.id, state.methodFlags)
                    Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(method.title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(method.summary, color = mutedTextColor(), fontSize = 11.sp)
                        }
                        StatusBadge(if (enabled) "Enabled" else "Disabled", if (enabled) successColor() else mutedTextColor())
                        OutlinedButton(onClick = { onEnabled(method, !enabled) }, enabled = !demo) { Text(if (enabled) "Disable" else "Enable", fontSize = 11.sp) }
                    }
                    if (index < methods.lastIndex) WorkspaceDivider()
                }
            }
            Text("Disabling a method reduces the configured scope. If every built-in method is off, built-in coverage is incomplete. Repository files cannot change these application preferences.", fontSize = 11.sp, color = mutedTextColor())
        } else BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = if (maxWidth >= 1050.dp) 2 else 1
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                methods.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        row.forEach { method -> Box(Modifier.weight(1f)) {
                            MethodConnectionCard(method, state, report, demo, onSetup, onEnabled, onPermissions, onGuide)
                        } }
                        if (row.size < columns) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        Text(if (demo) "Demo controls are read-only. Connections, settings and model services are not invoked." else "Application settings · connections and profiles stay outside your projects · enabled provider code runs with your permissions",
            fontSize = 10.sp, color = mutedTextColor())
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MethodConnectionCard(method: SecurityMethod, state: WrapperGuardSettings.State, report: ScanReport?, demo: Boolean,
    onSetup: (SecurityMethod) -> Unit, onEnabled: (SecurityMethod, Boolean) -> Unit,
    onPermissions: (SecurityMethod) -> Unit, onGuide: (SecurityMethod) -> Unit) {
    val connection = state.methodConnections.firstOrNull { it.methodId == method.id }
    val lastRun = report?.providerRuns?.firstOrNull { it.descriptor.id == method.providerId }
    val enabled = state.methodFlags[method.id] ?: lastRun?.permissions?.enabled
        ?: state.providers.firstOrNull { it.id == method.providerId }?.enabled ?: method.defaultEnabled
    val pathsReady = connection?.let {
        runCatching { Files.isExecutable(Path.of(it.pythonExecutable)) && Files.isRegularFile(Path.of(it.profilePath)) &&
            (it.mode != "report" && method.id != "ai.external-model" || Files.isRegularFile(Path.of(it.reportPath))) &&
            (it.mode != "runner" || method.id in setOf("research.script-semantics", "ai.external-model") || Files.isExecutable(Path.of(it.runnerExecutable))) &&
            (method.id != "sast.powershell" || it.mode != "runner" || Files.exists(Path.of(it.modulePath))) &&
            (method.id != "sast.semgrep" || it.mode != "runner" || it.rulesPath.isBlank() || Files.isRegularFile(Path.of(it.rulesPath))) }.getOrDefault(false)
    } ?: false
    val unsupported = System.getProperty("os.name").startsWith("Windows") && method.id == "research.script-semantics"
    val legacy = connection == null && lastRun != null
    val status = when {
        demo -> "Not connected · demo"
        unsupported -> "Requires a POSIX host"
        legacy -> "Existing provider"
        connection == null -> "Setup required"
        !pathsReady -> "Connection needs attention"
        !enabled -> "Connected · disabled"
        lastRun?.status == ProviderRunStatus.FAILED -> "Last scan failed"
        lastRun?.status == ProviderRunStatus.TIMED_OUT -> "Last scan timed out"
        method.id == "ai.external-model" && lastRun == null -> "SDK verified · model not tested"
        lastRun?.status == ProviderRunStatus.COMPLETED -> "Last scan completed"
        else -> "SDK verified · ready to scan"
    }
    val tone = when { !enabled -> mutedTextColor(); !pathsReady && !legacy -> warningColor(); lastRun?.status in setOf(ProviderRunStatus.FAILED, ProviderRunStatus.TIMED_OUT) -> warningColor(); else -> successColor() }
    WorkspacePanel(method.title, subtitle = when (method.category) {
        MethodCategory.SAST -> "Source analysis"; MethodCategory.SUPPLY_CHAIN -> "Dependency analysis"; MethodCategory.DAST -> "Application report"
        MethodCategory.RESEARCH -> "Experimental static signals"; MethodCategory.AI -> "Local model review"; else -> "Built-in"
    }, trailing = { StatusBadge(if (enabled) "Enabled" else "Off", if (enabled) accentColor() else mutedTextColor()) }) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(method.summary, fontSize = 12.sp, modifier = Modifier.heightIn(min = 34.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(Modifier.size(5.dp).background(tone)); Text(status, color = tone, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
            if (connection != null) {
                Text(when {
                    method.id == "ai.external-model" -> "${connection.model} · ${connection.endpoint}"
                    method.id == "research.script-semantics" -> "Installed SDK static analyzer · advisory"
                    connection.mode == "runner" -> "Installed analyzer · ${connection.allowedOperations.joinToString(" / ")}"
                    else -> "Report import · current source freshness is not established"
                }, fontSize = 10.sp, color = mutedTextColor(), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (connection != null) Text(if (connection.preferManaged) "Uses this managed connection when enabled." else "Existing registrations with the same ID take precedence.",
                fontSize = 10.sp, color = mutedTextColor())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { onSetup(method) }, enabled = !demo && !unsupported) { Text(if (connection == null) "Connect…" else "Configure…", fontSize = 11.sp) }
                OutlinedButton(onClick = { onEnabled(method, !enabled) }, enabled = !demo && (!unsupported || enabled)) { Text(if (enabled) "Disable" else "Enable", fontSize = 11.sp) }
                OutlinedButton(onClick = { onPermissions(method) }, enabled = !demo) { Text("Permissions…", fontSize = 11.sp) }
                OutlinedButton(onClick = { onGuide(method) }, enabled = !demo) { Text("Setup guide ↗", fontSize = 11.sp) }
            }
        }
    }
}
