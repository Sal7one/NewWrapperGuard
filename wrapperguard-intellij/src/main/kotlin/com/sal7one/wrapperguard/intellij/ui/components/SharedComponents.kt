package com.sal7one.wrapperguard.intellij.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.wrapperguard.intellij.ui.asBadgeBackground
import com.sal7one.wrapperguard.intellij.ui.cardSurfaceColor
import com.sal7one.wrapperguard.intellij.ui.mutedTextColor
import com.sal7one.wrapperguard.intellij.ui.outlineColor
import org.jetbrains.jewel.ui.component.Text

private val PillShape = RoundedCornerShape(50)

/**
 * Compact uppercase status pill: a translucent fill of [tone] with a solid [tone] border.
 * Used for verdict, severity, reachability, and policy-action badges.
 */
@Composable
fun StatusBadge(text: String, tone: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(PillShape)
            .background(tone.asBadgeBackground(0.18f))
            .border(1.dp, tone.copy(alpha = 0.55f), PillShape)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = text.uppercase(),
            color = tone,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

/** Labeled value row, monospace for technical values (paths, ids, hashes). */
@Composable
fun LabeledValue(label: String, value: String, mono: Boolean = false, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label.uppercase(),
            color = mutedTextColor(),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            fontSize = 12.sp,
            fontFamily = if (mono) FontFamily.Monospace else null,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = mutedTextColor(), fontSize = 12.sp)
    }
}
