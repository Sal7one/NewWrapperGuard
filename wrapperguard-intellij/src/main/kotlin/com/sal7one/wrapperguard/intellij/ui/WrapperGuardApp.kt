package com.sal7one.wrapperguard.intellij.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sal7one.wrapperguard.core.policy.EnforcementMode
import com.sal7one.wrapperguard.core.policy.ScanPolicy
import com.sal7one.wrapperguard.core.policy.ScanSettings
import com.sal7one.wrapperguard.intellij.WrapperGuardProjectService
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text

private enum class WgTab(val label: String) {
    OVERVIEW("Overview"), FINDINGS("Findings"), GRAPH("Execution Graph"), POLICY("Policy")
}

/**
 * WrapperGuard tool window root. A lightweight editor-style tab bar (the design system's
 * "editor-style tabs": squared off with a colored bottom border on the active tab) drives four
 * tabs. Scan state (report + scanning flag) is observed from [WrapperGuardProjectService].
 *
 * All colors come from [WgTheme] (Jewel theme tokens), so the whole UI adapts to the active
 * IDE light/dark theme automatically.
 */
@Composable
fun WrapperGuardApp(service: WrapperGuardProjectService) {
    var report by remember { mutableStateOf(service.report) }
    var isScanning by remember { mutableStateOf(false) }
    DisposableEffect(service) {
        val listener: (com.sal7one.wrapperguard.core.model.ScanReport?) -> Unit = { newReport ->
            report = newReport
            isScanning = false
        }
        service.addListener(listener)
        onDispose { service.removeListener(listener) }
    }

    var activeTab by remember { mutableStateOf(WgTab.OVERVIEW) }

    Column(modifier = Modifier.fillMaxSize().background(JewelTheme.globalColors.toolwindowBackground)) {
        TabBar(selected = activeTab, onSelect = { activeTab = it })
        Box(modifier = Modifier.fillMaxSize()) {
            when (activeTab) {
                WgTab.OVERVIEW -> OverviewTab(
                    report = report,
                    isScanning = isScanning,
                    onRescan = {
                        isScanning = true
                        service.scanAsync()
                    },
                    onJumpToFindings = { activeTab = WgTab.FINDINGS }
                )
                WgTab.FINDINGS -> FindingsTab(
                    findings = report?.findings ?: emptyList(),
                    onOpenFinding = { /* open-in-editor wired in a later iteration via the Editor service */ }
                )
                WgTab.GRAPH -> ExecutionGraphTab(
                    edges = report?.edges ?: emptyList(),
                    findings = report?.findings ?: emptyList(),
                    onOpenFinding = { }
                )
                WgTab.POLICY -> PolicyTab(
                    policy = ScanPolicy(mode = EnforcementMode.WARN),
                    settings = ScanSettings()
                )
            }
        }
    }
}

@Composable
private fun TabBar(selected: WgTab, onSelect: (WgTab) -> Unit) {
    val accent = verdictAccent()
    // The tab row scrolls horizontally so long labels ("Execution Graph") never truncate,
    // even in a narrow side-pane tool window.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .background(JewelTheme.globalColors.panelBackground)
            .horizontalScroll(rememberScrollState())
    ) {
        WgTab.values().forEach { tab ->
            val isSelected = tab == selected
            Column(
                modifier = Modifier
                    .height(34.dp)
                    .clickable { onSelect(tab) }
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.Bottom,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = tab.label,
                    color = if (isSelected) accent else mutedTextColor(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(top = 6.dp)
                )
                Box(
                    modifier = Modifier
                        .width(IntrinsicSize.Max)
                        .height(if (isSelected) 2.dp else 1.dp)
                        .background(if (isSelected) accent else JewelTheme.globalColors.borders.normal)
                )
            }
        }
    }
}

/** Primary indigo accent from the platform theme, falling back to a theme-aware indigo. */
@Composable
private fun verdictAccent(): Color {
    // The New UI primary is indigo in both modes; use a light/dark-appropriate pair.
    return if (JewelTheme.isDark) Color(0xFFC3C0FF) else Color(0xFF4F46E5)
}
