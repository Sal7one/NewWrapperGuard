package com.sal7one.wrapperguard.intellij.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.sal7one.wrapperguard.core.policy.ScanSettings
import com.sal7one.wrapperguard.intellij.ui.components.*
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

@Composable
internal fun PolicyTab(settings: ScanSettings, onSettings: () -> Unit) {
    WorkspacePage {
        WorkspaceSection("Execution guard") {
            Text("Before a supported IDE run, WrapperGuard scans in Enforce mode. Active, reachable errors and explicitly blocked rules can stop execution. INFO and audit evidence never block.", fontSize = 12.sp)
            Text("Background scans use Warn mode. Their review actions can differ from a pre-run Enforce scan.", fontSize = 12.sp, color = mutedTextColor())
            OutlinedButton(onClick = onSettings) { Text("Edit WrapperGuard settings") }
        }
        WorkspaceSection("Current scanner settings") {
            LabeledValue("Distribution SHA-256", if (settings.requireDistributionSha256) "Required" else "Optional")
            LabeledValue("Script recursion", "Up to ${settings.maxScriptDepth} levels")
            LabeledValue("Maximum file size", "${settings.maxFileBytes} bytes")
            LabeledValue("File limit", settings.maxFilesPerScan.toString())
        }
        WorkspaceSection("Allowed network hosts") {
            Text("Built-in hosts plus your saved additions. Host entries also match subdomains.", color = mutedTextColor(), fontSize = 12.sp)
            settings.allowedHosts.sorted().forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
        }
        if (settings.allowedUrlPatterns.isNotEmpty()) WorkspaceSection("Allowed URL patterns") {
            settings.allowedUrlPatterns.sorted().forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
        }
        WorkspaceSection("Trust and suppression") {
            Text("Ignore markers and baselines shipped inside a repository cannot approve its own findings. Suppression requires a trusted baseline outside the repository.", fontSize = 12.sp)
            Text("Baseline approval is not yet available in this IntelliJ interface.", color = mutedTextColor(), fontSize = 12.sp)
        }
    }
}
