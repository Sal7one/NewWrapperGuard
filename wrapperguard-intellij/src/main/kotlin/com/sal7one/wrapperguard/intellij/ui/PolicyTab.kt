package com.sal7one.wrapperguard.intellij.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.wrapperguard.core.policy.ScanSettings
import com.sal7one.wrapperguard.core.model.ScanReport
import com.sal7one.wrapperguard.core.vetting.ProviderRun
import com.sal7one.wrapperguard.core.vetting.ProviderRunStatus
import com.sal7one.wrapperguard.core.vetting.VettingAction
import com.sal7one.wrapperguard.intellij.settings.WrapperGuardSettings
import com.sal7one.wrapperguard.intellij.ui.components.*
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

/** A read-only permission snapshot. Changes belong to the host settings dialog. */
@Composable
internal fun PolicyTab(settings: ScanSettings, report: ScanReport?, onSettings: () -> Unit, demo: Boolean = false) {
    WorkspacePage {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Policy & providers", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("Control which providers can interrupt a build or sync.", color = mutedTextColor(), fontSize = 12.sp)
            }
            OutlinedButton(onClick = onSettings, enabled = !demo) { Text("Edit settings", fontSize = 12.sp) }
        }
        EnforcementStrip()
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 1050.dp) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ProviderPermissions(report)
                        ProviderDiagnostics(report)
                        TrustBoundaryNote()
                    }
                    ScannerConfiguration(settings, demo, Modifier.width(260.dp))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ProviderPermissions(report)
                    ProviderDiagnostics(report)
                    ScannerConfiguration(settings, demo, Modifier.fillMaxWidth())
                    TrustBoundaryNote()
                }
            }
        }
    }
}

@Composable
private fun EnforcementStrip() {
    Row(Modifier.fillMaxWidth().background(insetSurfaceColor(), RoundedCornerShape(4.dp))
        .border(1.dp, outlineColor(), RoundedCornerShape(4.dp)).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        Text("ENFORCEMENT", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = mutedTextColor(), letterSpacing = 0.6.sp)
        EnforcementMode("Build / run", "Enforce", dangerColor())
        EnforcementMode("Gradle sync", "Enforce", dangerColor())
        EnforcementMode("Background", "Warn", warningColor())
    }
}

@Composable
private fun EnforcementMode(operation: String, mode: String, tone: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(operation, fontSize = 12.sp)
        Box(Modifier.size(5.dp).background(tone, RoundedCornerShape(2.dp)))
        Text(mode, fontSize = 11.sp, color = tone, fontWeight = FontWeight.Medium)
    }
}

private val permissionColumns = listOf(
    VettingAction.WARNING to "Warning",
    VettingAction.MAJOR_WARNING to "Major",
    VettingAction.ALERT to "Alert",
    VettingAction.BLOCK_BUILD to "Build",
    VettingAction.BLOCK_SYNC to "Sync",
)

@Composable
private fun ProviderPermissions(report: ScanReport?) {
    val runs = report?.providerRuns.orEmpty()
    WorkspacePanel("Provider permissions", subtitle = "${runs.size} providers · grants from host settings") {
        if (runs.isEmpty()) EmptyHint("Run a scan to inspect provider permissions and status.")
        else BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 720.dp) {
                Column {
                    Row(Modifier.fillMaxWidth().background(raisedSurfaceColor()).padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PolicyHeader("PROVIDER", Modifier.weight(1f))
                        PolicyHeader("LAST RUN", Modifier.width(94.dp))
                        permissionColumns.forEach { (_, label) ->
                            Box(Modifier.width(46.dp), contentAlignment = Alignment.Center) { PolicyHeader(label, Modifier) }
                        }
                        PolicyHeader("REQUIREMENT", Modifier.width(87.dp))
                    }
                    WorkspaceDivider()
                    runs.forEachIndexed { index, run ->
                        ProviderTableRow(run)
                        if (index < runs.lastIndex) WorkspaceDivider()
                    }
                }
            } else Column {
                runs.forEachIndexed { index, run ->
                    ProviderCompactRow(run)
                    if (index < runs.lastIndex) WorkspaceDivider()
                }
            }
            // The matrix is intentionally read-only, including the synthetic demo snapshot.
        }
        if (runs.isNotEmpty()) {
            WorkspaceDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("✓ Granted", fontSize = 11.sp, color = accentColor())
                Text("— Not granted", fontSize = 11.sp, color = mutedTextColor())
                Text("Build / Sync = permission to stop the operation", fontSize = 11.sp, color = mutedTextColor())
            }
        }
    }
}

@Composable
private fun ProviderTableRow(run: ProviderRun) {
    Row(Modifier.fillMaxWidth().heightIn(min = 62.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(run.descriptor.displayName, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${run.descriptor.id} · ${run.descriptor.version}", color = mutedTextColor(), fontFamily = FontFamily.Monospace,
                fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(Modifier.width(94.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(providerStatusLabel(run.status), color = providerTone(run.status), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(when (run.status) {
                ProviderRunStatus.DISABLED -> "Not run"
                ProviderRunStatus.COMPLETED -> "${run.findingCount} findings · ${run.durationMs}ms"
                else -> "${run.durationMs}ms elapsed"
            }, color = mutedTextColor(), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        permissionColumns.forEach { (action, _) ->
            val granted = action in run.permissions.actions
            val tone = when {
                !granted || !run.permissions.enabled -> mutedTextColor().copy(alpha = 0.5f)
                action == VettingAction.BLOCK_BUILD || action == VettingAction.BLOCK_SYNC -> dangerColor()
                else -> accentColor()
            }
            Box(Modifier.width(46.dp).clearAndSetSemantics {
                contentDescription = "${actionLabel(action)}: ${if (granted) "granted" else "not granted"}"
            }, contentAlignment = Alignment.Center) {
                Text(if (granted) "✓" else "—", color = tone, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
        Column(Modifier.width(87.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (run.permissions.required) "Required" else "Optional", fontSize = 12.sp)
            Text("${timeoutLabel(run.permissions.timeoutMs)} timeout", fontSize = 10.sp, color = mutedTextColor())
        }
    }
}

@Composable
private fun ProviderCompactRow(run: ProviderRun) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(run.descriptor.displayName, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(providerStatusLabel(run.status), fontSize = 12.sp, color = providerTone(run.status))
        }
        Text("${run.descriptor.id} · ${run.descriptor.version} · ${if (run.permissions.required) "Required" else "Optional"} · ${timeoutLabel(run.permissions.timeoutMs)} timeout",
            fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = mutedTextColor())
        Text(run.permissions.actions.sortedBy { it.ordinal }.joinToString(" · ") { actionLabel(it) }.ifEmpty { "No actions granted" },
            fontSize = 11.sp, color = if (run.permissions.enabled) accentColor() else mutedTextColor())
    }
}

@Composable
private fun ProviderDiagnostics(report: ScanReport?) {
    val issues = report?.providerRuns.orEmpty().filter { it.status != ProviderRunStatus.COMPLETED && it.status != ProviderRunStatus.DISABLED }
    if (issues.isEmpty()) return
    Column(Modifier.fillMaxWidth().background(insetSurfaceColor(), RoundedCornerShape(4.dp))
        .border(1.dp, outlineColor(), RoundedCornerShape(4.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Provider diagnostics", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        issues.forEach { run ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(run.descriptor.displayName, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(170.dp))
                Text(run.message ?: providerStatusLabel(run.status), fontSize = 11.sp, color = mutedTextColor(), modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ScannerConfiguration(settings: ScanSettings, demo: Boolean, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        WorkspacePanel("Built-in scanner") {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ScannerValue("Distribution SHA-256", if (settings.requireDistributionSha256) "Required" else "Optional")
                ScannerValue("Recursive scanning", if (settings.enableRecursiveScanning) "Enabled" else "Disabled")
                ScannerValue("Script depth", "${settings.maxScriptDepth}")
                ScannerValue("File size limit", "${settings.maxFileBytes / 1024} KiB")
                ScannerValue("Files per scan", "${settings.maxFilesPerScan}")
                ScannerValue("Directory depth", "${settings.maxDirectoryDepth}")
                ScannerValue("Line length limit", "${settings.maxLineLength}")
            }
        }
        WorkspacePanel("Network allowlist") {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Hosts and their subdomains", color = mutedTextColor(), fontSize = 11.sp)
                if (settings.allowedHosts.isEmpty()) Text("No hosts allowed", color = mutedTextColor(), fontSize = 11.sp)
                settings.allowedHosts.sorted().forEach { host ->
                    Text(host, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
                if (settings.allowedUrlPatterns.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text("URL patterns", color = mutedTextColor(), fontSize = 11.sp)
                    settings.allowedUrlPatterns.sorted().forEach { pattern ->
                        Text(pattern, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    }
                }
            }
        }
        val configurationPath = if (demo) "" else WrapperGuardSettings.getInstance().state.providerConfigurationPath
        Column(Modifier.padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("COMMAND CONFIGURATION", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = mutedTextColor(), letterSpacing = 0.5.sp)
            Text(when {
                demo -> "Simulated providers. No SDK commands run."
                configurationPath.isBlank() -> "No external commands configured."
                else -> configurationPath
            }, fontSize = 11.sp, fontFamily = if (configurationPath.isNotBlank()) FontFamily.Monospace else FontFamily.Default, color = mutedTextColor())
        }
    }
}

@Composable
private fun ScannerValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 11.sp, color = mutedTextColor(), modifier = Modifier.weight(1f))
        Text(value, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun TrustBoundaryNote() {
    Column(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text("TRUST BOUNDARY", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = mutedTextColor(), letterSpacing = 0.5.sp)
        Text("Build and sync require project trust. Third-party providers pause in Safe Mode; provider code runs with your permissions and is not sandboxed.",
            color = mutedTextColor(), fontSize = 11.sp)
        Text("Only a matching blocking grant can stop an operation. Required provider failures use the same grants. Repository ignore markers cannot approve findings; trusted baseline approval is managed outside this interface.",
            color = mutedTextColor(), fontSize = 11.sp)
    }
}

@Composable private fun providerTone(status: ProviderRunStatus): Color = when (status) {
    ProviderRunStatus.COMPLETED -> successColor()
    ProviderRunStatus.DISABLED -> mutedTextColor()
    else -> warningColor()
}

@Composable private fun PolicyHeader(label: String, modifier: Modifier) {
    Text(label, color = mutedTextColor(), fontSize = 10.sp, fontWeight = FontWeight.Medium, modifier = modifier)
}

private fun timeoutLabel(milliseconds: Long): String = when {
    milliseconds < 1000 -> "${milliseconds}ms"
    milliseconds % 1000L == 0L -> "${milliseconds / 1000}s"
    else -> "${java.math.BigDecimal.valueOf(milliseconds, 3).stripTrailingZeros().toPlainString()}s"
}
