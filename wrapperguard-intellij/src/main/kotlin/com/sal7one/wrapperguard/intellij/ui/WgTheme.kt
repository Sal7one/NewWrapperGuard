package com.sal7one.wrapperguard.intellij.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.sal7one.wrapperguard.core.model.PolicyAction
import com.sal7one.wrapperguard.core.model.ScanVerdict
import com.sal7one.wrapperguard.core.model.Severity
import org.jetbrains.jewel.foundation.theme.JewelTheme

/**
 * Maps WrapperGuard semantic concepts (severity, verdict, policy action) onto Compose [Color]s
 * that adapt to the active IDE theme. We prefer Jewel's own [JewelTheme.globalColors].text
 * semantic colors (error/warning/info) where present, and fall back to a light/dark pair
 * derived from [JewelTheme.isDark]. Nothing is hardcoded to a single mode.
 */

@Composable
fun severityColor(severity: Severity): Color = when (severity) {
    Severity.ERROR -> JewelTheme.globalColors.text.error ?: semantic(0xFFBA1A1A, 0xFFFFB4AB)
    Severity.WARN -> JewelTheme.globalColors.text.warning ?: semantic(0xFF8A5300, 0xFFFFB695)
    Severity.INFO -> JewelTheme.globalColors.text.info ?: semantic(0xFF1F6B3C, 0xFFA0D9B5)
}

@Composable
fun verdictColor(verdict: ScanVerdict): Color = when (verdict) {
    ScanVerdict.EXECUTION_BLOCKED, ScanVerdict.FAILED ->
        JewelTheme.globalColors.text.error ?: semantic(0xFFBA1A1A, 0xFFFFB4AB)
    ScanVerdict.REVIEW_REQUIRED, ScanVerdict.INCOMPLETE, ScanVerdict.STALE ->
        JewelTheme.globalColors.text.warning ?: semantic(0xFF8A5300, 0xFFFFB695)
    ScanVerdict.CLEAN ->
        JewelTheme.globalColors.text.info ?: semantic(0xFF1F6B3C, 0xFFA0D9B5)
    ScanVerdict.NOT_SCANNED -> mutedTextColor()
}

@Composable
fun policyActionColor(action: PolicyAction): Color = when (action) {
    PolicyAction.BLOCK -> JewelTheme.globalColors.text.error ?: semantic(0xFFBA1A1A, 0xFFFFB4AB)
    PolicyAction.REVIEW -> JewelTheme.globalColors.text.warning ?: semantic(0xFF8A5300, 0xFFFFB695)
    PolicyAction.ALLOW -> JewelTheme.globalColors.text.info ?: semantic(0xFF1F6B3C, 0xFFA0D9B5)
}

/** Translucent fill (for badge backgrounds) derived from the semantic color. */
@Composable
fun Color.asBadgeBackground(alpha: Float = 0.16f): Color = copy(alpha = alpha)

/** Subtle outline that adapts to theme. */
@Composable
fun outlineColor(): Color = JewelTheme.globalColors.borders.normal

/** Surface color for raised cards/tiles. */
@Composable
fun cardSurfaceColor(): Color =
    JewelTheme.globalColors.panelBackground

/** Secondary content remains readable even when a custom theme has near-invisible disabled text. */
@Composable
fun mutedTextColor(): Color = JewelTheme.globalColors.text.normal.copy(alpha = 0.78f)

@Composable
private fun semantic(lightFallback: Long, darkFallback: Long): Color =
    Color(if (JewelTheme.isDark) darkFallback else lightFallback)
