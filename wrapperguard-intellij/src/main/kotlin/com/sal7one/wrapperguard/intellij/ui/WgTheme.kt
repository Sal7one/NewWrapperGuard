package com.sal7one.wrapperguard.intellij.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.sal7one.wrapperguard.core.model.PolicyAction
import com.sal7one.wrapperguard.core.model.ScanVerdict
import com.sal7one.wrapperguard.core.model.Severity
import org.jetbrains.jewel.foundation.theme.JewelTheme

/** Technical Precision's tonal layers, paired for the active IDE light/dark theme. */
@Composable fun workspaceBackground(): Color = semantic(0xFFF5F6FA, 0xFF13121B)
@Composable fun cardSurfaceColor(): Color = semantic(0xFFFFFFFF, 0xFF1B1B24)
@Composable fun insetSurfaceColor(): Color = semantic(0xFFF2F3F8, 0xFF101018)
@Composable fun raisedSurfaceColor(): Color = semantic(0xFFF9FAFC, 0xFF23232F)
@Composable fun accentColor(): Color = semantic(0xFF4F46E5, 0xFFA5A0FF)
@Composable fun outlineColor(): Color = semantic(0xFFDDE0E8, 0xFF383744)
@Composable fun mutedTextColor(): Color = JewelTheme.globalColors.text.normal.copy(alpha = 0.72f)
@Composable fun successColor(): Color = semantic(0xFF177849, 0xFF7DD6A7)
@Composable fun warningColor(): Color = semantic(0xFF925900, 0xFFE9B568)
@Composable fun dangerColor(): Color = semantic(0xFFB62F3D, 0xFFFF9B9E)

@Composable fun severityColor(severity: Severity): Color = when (severity) {
    Severity.ERROR -> dangerColor()
    Severity.WARN -> warningColor()
    Severity.INFO -> accentColor()
}

@Composable fun verdictColor(verdict: ScanVerdict): Color = when (verdict) {
    ScanVerdict.EXECUTION_BLOCKED, ScanVerdict.FAILED -> dangerColor()
    ScanVerdict.REVIEW_REQUIRED, ScanVerdict.INCOMPLETE, ScanVerdict.STALE -> warningColor()
    ScanVerdict.CLEAN -> successColor()
    ScanVerdict.NOT_SCANNED -> mutedTextColor()
}

@Composable fun policyActionColor(action: PolicyAction): Color = when (action) {
    PolicyAction.BLOCK -> dangerColor()
    PolicyAction.REVIEW -> warningColor()
    PolicyAction.ALLOW -> successColor()
}

@Composable fun Color.asBadgeBackground(alpha: Float = 0.16f): Color = copy(alpha = alpha)
@Composable private fun semantic(light: Long, dark: Long): Color = Color(if (JewelTheme.isDark) dark else light)
