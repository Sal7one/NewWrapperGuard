package com.sal7one.wrapperguard.intellij.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.wrapperguard.intellij.ui.*
import org.jetbrains.jewel.ui.component.Text

@Composable
internal fun WorkspacePage(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp).padding(end = 6.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
internal fun WorkspaceSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
internal fun WorkspacePanel(title: String, modifier: Modifier = Modifier, subtitle: String? = null,
                            trailing: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.clip(RoundedCornerShape(4.dp)).background(cardSurfaceColor())
        .border(1.dp, outlineColor(), RoundedCornerShape(4.dp))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                subtitle?.let { Text(it, color = mutedTextColor(), fontSize = 11.sp) }
            }
            trailing?.invoke()
        }
        WorkspaceDivider()
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
internal fun WorkspaceTab(label: String, selected: Boolean, count: Int? = null, onClick: () -> Unit) {
    val tone = if (selected) accentColor() else mutedTextColor()
    Column(Modifier.width(IntrinsicSize.Max).selectable(selected = selected, role = Role.Tab, onClick = onClick)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(label, color = tone, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            if (count != null) Box(Modifier.background(tone.copy(alpha = 0.12f), RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp)) {
                Text(count.toString(), color = tone, fontSize = 11.sp)
            }
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(if (selected) accentColor() else Color.Transparent))
    }
}

@Composable
internal fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val tone = if (selected) accentColor() else mutedTextColor()
    Box(Modifier.clip(RoundedCornerShape(4.dp))
        .background(if (selected) tone.copy(alpha = 0.13f) else Color.Transparent)
        .border(1.dp, if (selected) tone.copy(alpha = 0.55f) else outlineColor(), RoundedCornerShape(4.dp))
        .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text(label, color = tone, fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable internal fun WorkspaceDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(outlineColor()))
}

/** Primary action uses the product accent while keeping focus and keyboard semantics. */
@Composable
internal fun WorkspacePrimaryButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val fill = Color(0xFF6357DC)
    Box(Modifier.clip(RoundedCornerShape(4.dp)).background(if (enabled) fill else fill.copy(alpha = 0.45f))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
        Text(label, color = Color.White.copy(alpha = if (enabled) 1f else 0.65f), fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}
