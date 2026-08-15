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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.sal7one.wrapperguard.intellij.ui.components.StatusBadge
import org.jetbrains.jewel.ui.component.Text

/**
 * Policy tab, structured after `design_reference/policy_settings`: profile header card with
 * a profile badge, enforcement summary, allowed-hosts table with type pills, the active rule
 * families with severity, an Advanced section (recursion depth, excluded folders), and the
 * honest "Protected / Not protected" scope card. Read-only for now — editing ships with the
 * settings dialog later; this shows what's actually driving block/review decisions.
 */
@Composable
fun PolicyTab(policy: ScanPolicy, settings: ScanSettings, modifier: Modifier = Modifier) {
    ScrollableColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {

        // ── Profile header ─────────────────────────────────────────────
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(PaddingValues(16.dp)), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(policy.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    StatusBadge("Profile", verdictAccentColor())
                    Box(Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(policyActionColor(com.sal7one.wrapperguard.core.model.PolicyAction.ALLOW))
                    )
                    Text("Active", fontSize = 11.sp, color = mutedTextColor())
                }
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    LabeledValue("Enforcement", enforcementLabel(policy.mode))
                    LabeledValue("Min Blocking Severity", policy.minimumBlockingSeverity.name)
                    LabeledValue("Require Reachable", if (policy.requireReachableToBlock) "Yes" else "No")
                }
                Text(
                    "In Audit mode every actionable finding becomes Review. In Enforce, reachable ERROR findings " +
                        "and explicitly blocked rule IDs become Block. INFO/audit evidence never blocks.",
                    fontSize = 11.sp,
                    color = mutedTextColor()
                )
            }
        }

        // ── Allowed network destinations ───────────────────────────────
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(PaddingValues(16.dp)), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "ALLOWED NETWORK DESTINATIONS",
                    fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace, color = mutedTextColor()
                )
                settings.allowedHosts.forEach { host ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(policyActionColor(com.sal7one.wrapperguard.core.model.PolicyAction.ALLOW))
                        )
                        Text(host, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                        StatusBadge("Exact", mutedTextColor())
                    }
                }
                if (settings.allowedUrlPatterns.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "ALLOWED URL PATTERNS",
                        fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace, color = mutedTextColor()
                    )
                    settings.allowedUrlPatterns.forEach { pattern ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(policyActionColor(com.sal7one.wrapperguard.core.model.PolicyAction.REVIEW))
                            )
                            Text(pattern, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f), maxLines = 1)
                            StatusBadge("Regex", mutedTextColor())
                        }
                    }
                }
            }
        }

        // ── Active rule families ───────────────────────────────────────
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(PaddingValues(16.dp)), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "ACTIVE RULES",
                    fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace, color = mutedTextColor()
                )
                RuleRow("WG-SCRIPT-*", "Wrapper & script analysis", "ERROR/WARN")
                RuleRow("WG-BUILD-*", "Gradle build logic analysis", "ERROR/WARN")
                RuleRow("WG-WRAPPER-*", "Wrapper properties & integrity", "ERROR/WARN")
                RuleRow("WG-CATALOG-*", "Version catalog pinning", "WARN")
                RuleRow("WG-STRUCT-*", "Project structure checks", "WARN/INFO")
                RuleRow("WG-SCAN-*", "Recursion & scanner notices", "ERROR/WARN/INFO")
            }
        }

        // ── Advanced (collapsible) ─────────────────────────────────────
        var advancedOpen by remember { mutableStateOf(false) }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(PaddingValues(16.dp)), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { advancedOpen = !advancedOpen },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "ADVANCED SETTINGS",
                        fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace, color = mutedTextColor(),
                        modifier = Modifier.weight(1f)
                    )
                    Text(if (advancedOpen) "▾" else "▸", fontSize = 11.sp, color = mutedTextColor())
                }
                if (advancedOpen) {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        LabeledValue("Recursive Depth", settings.maxScriptDepth.toString())
                        LabeledValue("Max File Size", "${settings.maxFileBytes / 1024} KB")
                        LabeledValue("Require SHA-256", if (settings.requireDistributionSha256) "Yes" else "No")
                        LabeledValue("Recursive Scan", if (settings.enableRecursiveScanning) "On" else "Off")
                    }
                    if (settings.blockedCommands.isNotEmpty()) {
                        LabeledValue("Blocked Commands", settings.blockedCommands.joinToString(", "), mono = true)
                    }
                    LabeledValue(
                        "Excluded Folders",
                        ".git .gradle .idea build out .intellijPlatform .kotlin node_modules",
                        mono = true
                    )
                }
            }
        }

        // ── Protection scope (honest boundaries) ───────────────────────
        Card(modifier = Modifier.fillMaxWidth(), borderColor = mutedTextColor().copy(alpha = 0.4f)) {
            Column(modifier = Modifier.padding(PaddingValues(16.dp)), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "PROTECTION SCOPE",
                    fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace, color = mutedTextColor()
                )
                Text("Protected once the run guard + sync gate ship:", fontSize = 11.sp, color = mutedTextColor())
                Text("• IDE Gradle run configurations", fontSize = 11.sp)
                Text("• Supported IDE script (shell) run configurations", fontSize = 11.sp)
                Text("• Gradle sync when the sync gate is active", fontSize = 11.sp)
                Text("Not protected:", fontSize = 11.sp, color = mutedTextColor())
                Text("• External terminals and processes outside the IDE", fontSize = 11.sp, color = mutedTextColor())
                Text("• Commands typed in the IDE terminal", fontSize = 11.sp, color = mutedTextColor())
                Text("• Processes launched by other plugins", fontSize = 11.sp, color = mutedTextColor())
                Text(
                    "Until then, WrapperGuard is a scanner and findings viewer (see BACKLOG.md).",
                    fontSize = 10.sp, color = mutedTextColor()
                )
            }
        }
    }
}

@Composable
private fun RuleRow(id: String, description: String, severity: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(id, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, modifier = Modifier.width(100.dp))
        Text(description, fontSize = 11.sp, modifier = Modifier.weight(1f))
        StatusBadge(severity, mutedTextColor())
    }
}

@Composable
private fun verdictAccentColor(): Color =
    if (org.jetbrains.jewel.foundation.theme.JewelTheme.isDark) Color(0xFFC3C0FF) else Color(0xFF4F46E5)

private fun enforcementLabel(mode: EnforcementMode): String = when (mode) {
    EnforcementMode.AUDIT -> "Audit Only (Log)"
    EnforcementMode.WARN -> "Warn (Review)"
    EnforcementMode.ENFORCE -> "Enforce (Block)"
}
