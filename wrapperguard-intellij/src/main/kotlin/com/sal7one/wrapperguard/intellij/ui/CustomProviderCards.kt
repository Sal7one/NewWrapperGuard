package com.sal7one.wrapperguard.intellij.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.wrapperguard.core.model.ScanReport
import com.sal7one.wrapperguard.core.vetting.ProviderRunStatus
import com.sal7one.wrapperguard.intellij.settings.CustomProviderState
import com.sal7one.wrapperguard.intellij.settings.WrapperGuardSettings
import com.sal7one.wrapperguard.intellij.ui.components.*
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import java.nio.file.Files
import java.nio.file.Path

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CustomProviderCards(providers: List<CustomProviderState>, state: WrapperGuardSettings.State, report: ScanReport?, demo: Boolean,
    onEdit: (CustomProviderState) -> Unit, onEnabled: (CustomProviderState, Boolean) -> Unit,
    onPermissions: (CustomProviderState) -> Unit, onRemove: (CustomProviderState) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Custom providers", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text("User-owned connections · independent of first-party method switches", color = mutedTextColor(), fontSize = 11.sp)
        providers.forEach { provider ->
            val permissions = state.providers.firstOrNull { it.id == provider.id }?.permissions()
            val enabled = permissions?.enabled == true
            val run = report?.providerRuns?.firstOrNull { it.descriptor.id == provider.id }
            val missing = provider.kind == "command" && !runCatching { Files.isExecutable(Path.of(provider.executable)) }.getOrDefault(false)
            WorkspacePanel(provider.displayName, subtitle = "${provider.category.replace('_', ' ')} · ${if (provider.kind == "sdk") "IDE SDK" else "Command adapter"}",
                trailing = { StatusBadge(if (enabled) "Enabled" else "Off", if (enabled) accentColor() else mutedTextColor()) }) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${provider.id} · ${run?.descriptor?.version ?: provider.version}", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = mutedTextColor())
                    if (provider.summary.isNotBlank()) Text(provider.summary, fontSize = 12.sp)
                    val status = when {
                        missing -> "Executable unavailable · edit this connection"
                        !enabled -> "Disabled · no provider code is invoked"
                        run == null -> "Enabled · awaiting the next scan"
                        run.status == ProviderRunStatus.COMPLETED -> "Last scan completed · ${run.findingCount} findings · ${run.durationMs} ms"
                        else -> "Last scan: ${providerStatusLabel(run.status).lowercase()}"
                    }
                    Text(status, fontSize = 11.sp, color = if (missing || run?.status in setOf(ProviderRunStatus.FAILED, ProviderRunStatus.TIMED_OUT)) warningColor() else mutedTextColor())
                    val scope = provider.inputScope() ?: run?.descriptor?.inputScope
                    Text(when {
                        scope == null -> "Input scope unknown · freshness covers host-known inputs only"
                        scope.includes.isEmpty() && scope.externalFiles.isEmpty() -> "Declared metadata-only · no file inputs"
                        else -> "Declared inputs · ${scope.includes.size} project includes · ${scope.excludes.size} excludes · ${scope.externalFiles.size} external files"
                    }, color = if (scope == null) warningColor() else mutedTextColor(), fontSize = 11.sp)
                    Text(if (provider.projectRoots.isEmpty()) "Project binding · all projects (explicit reusable connection)" else "Project binding · ${provider.projectRoots.size} selected project${if (provider.projectRoots.size == 1) "" else "s"}", fontSize = 11.sp, color = mutedTextColor())
                    if (provider.tags.isNotEmpty()) Text(provider.tags.joinToString("  ") { "#$it" }, fontSize = 10.sp, color = accentColor())
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { onEdit(provider) }, enabled = !demo) { Text("Edit…", fontSize = 11.sp) }
                        OutlinedButton(onClick = { onEnabled(provider, !enabled) }, enabled = !demo) { Text(if (enabled) "Disable" else "Enable", fontSize = 11.sp) }
                        OutlinedButton(onClick = { onPermissions(provider) }, enabled = !demo) { Text("Permissions…", fontSize = 11.sp) }
                        OutlinedButton(onClick = { onRemove(provider) }, enabled = !demo) { Text("Remove connection", fontSize = 11.sp) }
                    }
                }
            }
        }
    }
}
