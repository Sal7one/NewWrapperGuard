package com.sal7one.wrapperguard.intellij.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sal7one.wrapperguard.core.model.ScanReport
import com.sal7one.wrapperguard.core.model.ScanVerdict
import com.sal7one.wrapperguard.core.policy.EnforcementMode
import com.sal7one.wrapperguard.core.policy.ScanPolicy
import com.sal7one.wrapperguard.core.policy.ScanSettings
import com.sal7one.wrapperguard.intellij.WrapperGuardProjectService
import com.sal7one.wrapperguard.intellij.ui.components.ScanStateBanner
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text

private enum class WgTab(val label: String) {
    OVERVIEW("Overview"), FINDINGS("Findings"), GRAPH("Execution Graph"), POLICY("Policy")
}

/**
 * WrapperGuard tool window root. A lightweight editor-style tab bar drives four tabs.
 * Scan state (report + scanning flag) is observed from [WrapperGuardProjectService]; the
 * first composition triggers a scan if none exists, so the UI populates on open.
 *
 * All colors come from [WgTheme] (Jewel theme tokens), so the whole UI adapts to the active
 * IDE light/dark theme automatically.
 */
@Composable
fun WrapperGuardApp(service: WrapperGuardProjectService, project: com.intellij.openapi.project.Project) {
    var report by remember { mutableStateOf<ScanReport?>(service.report) }
    var isScanning by remember { mutableStateOf(false) }
    DisposableEffect(service) {
        val listener: (ScanReport?) -> Unit = { newReport ->
            report = newReport
            isScanning = false
        }
        service.addListener(listener)
        service.scanIfNoReport()   // populate immediately on first open
        onDispose { service.removeListener(listener) }
    }

    var activeTab by remember { mutableStateOf(WgTab.OVERVIEW) }

    Column(modifier = Modifier.fillMaxSize().background(JewelTheme.globalColors.toolwindowBackground)) {
        TabBar(selected = activeTab, onSelect = { activeTab = it })

        // Scan-state banners for non-ordinary states, with retry.
        when {
            isScanning -> ScanStateBanner(
                text = "Scanning project…",
                tone = verdictColor(ScanVerdict.REVIEW_REQUIRED)
            )
            report == null -> ScanStateBanner(
                text = "Not scanned yet — run a scan to inspect this project.",
                tone = mutedTextColor(),
                action = { org.jetbrains.jewel.ui.component.OutlinedButton(onClick = { service.scanAsync() }) { Text("Scan Now") } }
            )
            report?.verdict == ScanVerdict.FAILED -> ScanStateBanner(
                text = "Scan failed — ${report?.notices?.firstOrNull()?.message ?: "unknown error"}",
                tone = verdictColor(ScanVerdict.FAILED),
                action = { org.jetbrains.jewel.ui.component.OutlinedButton(onClick = { service.scanAsync() }) { Text("Retry") } }
            )
            report?.verdict == ScanVerdict.INCOMPLETE -> ScanStateBanner(
                text = "Scan incomplete — some files were skipped (${report?.notices?.size ?: 0} notice(s)).",
                tone = verdictColor(ScanVerdict.INCOMPLETE),
                action = { org.jetbrains.jewel.ui.component.OutlinedButton(onClick = { service.scanAsync() }) { Text("Rescan") } }
            )
            report?.verdict == ScanVerdict.STALE -> ScanStateBanner(
                text = "Scan is stale — project files changed since the last scan.",
                tone = verdictColor(ScanVerdict.STALE),
                action = { org.jetbrains.jewel.ui.component.OutlinedButton(onClick = { service.scanAsync() }) { Text("Rescan") } }
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when (activeTab) {
                WgTab.OVERVIEW -> OverviewTab(
                    report = report,
                    isScanning = isScanning,
                    onRescan = {
                        isScanning = true
                        service.scanAsync()
                    },
                    onJumpToFindings = { activeTab = WgTab.FINDINGS },
                    onExport = {
                        report?.let { current ->
                            val saved = exportReportJson(project, current)
                            // Surface the result via a follow-up banner state if needed; for now the
                            // file dialog itself confirms success to the user.
                            saved?.let { }
                        }
                    }
                )
                WgTab.FINDINGS -> FindingsTab(
                    findings = report?.findings ?: emptyList(),
                    onOpenFinding = { finding -> openFindingInEditor(project, finding) }
                )
                WgTab.GRAPH -> ExecutionGraphTab(
                    edges = report?.edges ?: emptyList(),
                    findings = report?.findings ?: emptyList(),
                    onOpenFinding = { finding -> openFindingInEditor(project, finding) }
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
    val ruleColor = JewelTheme.globalColors.borders.normal
    // The tab row scrolls horizontally so long labels ("Execution Graph") never truncate,
    // even in a narrow side-pane tool window. The active-tab underline is drawn edge-to-edge
    // under the tab (drawBehind), matching the design's 2px editor-style bottom border.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .background(JewelTheme.globalColors.panelBackground)
            .horizontalScroll(rememberScrollState())
    ) {
        WgTab.values().forEach { tab ->
            val isSelected = tab == selected
            Box(
                modifier = Modifier
                    .height(34.dp)
                    .clickable { onSelect(tab) }
                    .padding(horizontal = 14.dp)
                    .drawBehind {
                        val underlineHeight = if (isSelected) 2.dp.toPx() else 1.dp.toPx()
                        drawRect(
                            color = if (isSelected) accent else ruleColor,
                            topLeft = Offset(0f, size.height - underlineHeight),
                            size = Size(size.width, underlineHeight)
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = tab.label,
                    color = if (isSelected) accent else mutedTextColor(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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
