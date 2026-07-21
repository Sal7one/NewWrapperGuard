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

/**
 * A metric tile: tiny uppercase label, a large count, and an optional sublabel.
 * Designed to tile 2×N across the Overview grid.
 */
@Composable
fun MetricTile(
    label: String,
    count: Int,
    tone: Color,
    modifier: Modifier = Modifier,
    sublabel: String? = null,
    accentBorder: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(cardSurfaceColor())
            .border(
                width = if (accentBorder) 1.dp else 0.dp,
                color = if (accentBorder) tone.copy(alpha = 0.35f) else Color.Transparent,
                shape = RoundedCornerShape(6.dp)
            )
            .padding(PaddingValues(horizontal = 14.dp, vertical = 12.dp))
    ) {
        Text(
            text = label.uppercase(),
            color = mutedTextColor(),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = count.toString(),
                color = tone,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )
            if (sublabel != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = sublabel,
                    color = mutedTextColor(),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
        }
    }
}

/** A small bordered card with content; used for the verdict banner and detail panes. */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    borderColor: Color? = null,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(cardSurfaceColor())
            .border(1.dp, borderColor ?: outlineColor(), RoundedCornerShape(6.dp))
    ) {
        content()
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
