package com.sal7one.wrapperguard.intellij.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.wrapperguard.intellij.ui.asBadgeBackground
import com.sal7one.wrapperguard.intellij.ui.mutedTextColor
import org.jetbrains.jewel.ui.component.Text

/**
 * A slim banner shown under the tab bar for non-ordinary scan states: scanning, failed,
 * incomplete, stale. Carries an optional action (e.g. Retry / Rescan).
 */
@Composable
fun ScanStateBanner(
    text: String,
    tone: Color,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(tone.asBadgeBackground(0.14f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            color = tone,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        action?.invoke()
    }
}

/** Quiet hint text used inside banners when no data is available yet. */
@Composable
fun BannerMutedText(text: String) {
    Text(text = text, color = mutedTextColor(), fontSize = 11.sp)
}
