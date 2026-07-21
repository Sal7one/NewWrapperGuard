package com.sal7one.wrapperguard.intellij.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.wrapperguard.core.policy.EnforcementMode
import com.sal7one.wrapperguard.core.policy.ScanPolicy
import com.sal7one.wrapperguard.core.policy.ScanSettings
import com.sal7one.wrapperguard.intellij.ui.components.Card
import com.sal7one.wrapperguard.intellij.ui.components.LabeledValue
import com.sal7one.wrapperguard.intellij.ui.components.ScrollableColumn
import org.jetbrains.jewel.ui.component.Text

/**
 * Policy tab: a compact summary of the active [ScanPolicy] and [ScanSettings]. Read-only for now
 * (editing comes with the settings dialog later); this satisfies the "Policy" tab requirement
 * and shows what's actually driving block/review decisions. Scrolls vertically.
 */
@Composable
fun PolicyTab(policy: ScanPolicy, settings: ScanSettings, modifier: Modifier = Modifier) {
    ScrollableColumn(modifier = modifier) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(PaddingValues(16.dp)), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Active Profile", fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = mutedTextColor())
                Text(policy.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    LabeledValue("Enforcement", enforcementLabel(policy.mode))
                    LabeledValue("Min Blocking Severity", policy.minimumBlockingSeverity.name)
                    LabeledValue("Require Reachable", if (policy.requireReachableToBlock) "Yes" else "No")
                }
                Text(
                    "Policy decides each finding's action (Allow/Review/Block). In Audit mode every actionable finding becomes Review; in Enforce, reachable ERRORs and explicitly blocked rule IDs become Block.",
                    fontSize = 11.sp,
                    color = mutedTextColor()
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(PaddingValues(16.dp)), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Scan Settings", fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = mutedTextColor())
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    LabeledValue("Max Recursion Depth", settings.maxScriptDepth.toString())
                    LabeledValue("Max File Size", "${settings.maxFileBytes / 1024} KB")
                    LabeledValue("Require SHA-256", if (settings.requireDistributionSha256) "Yes" else "No")
                    LabeledValue("Recursive Scan", if (settings.enableRecursiveScanning) "On" else "Off")
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Allowed Hosts", fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = mutedTextColor())
                    settings.allowedHosts.forEach { host ->
                        Text("• $host", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                if (settings.blockedCommands.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Blocked Commands", fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = mutedTextColor())
                        Text(settings.blockedCommands.joinToString(", "), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

private fun enforcementLabel(mode: EnforcementMode): String = when (mode) {
    EnforcementMode.AUDIT -> "Audit Only (Log)"
    EnforcementMode.WARN -> "Warn (Review)"
    EnforcementMode.ENFORCE -> "Enforce (Block)"
}
