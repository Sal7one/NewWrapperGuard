package com.sal7one.wrapperguard.intellij.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp).padding(end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp), content = content)
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
internal fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val tone = org.jetbrains.jewel.foundation.theme.JewelTheme.globalColors.text.normal
    Box(Modifier.clipToBounds()
        .background(if (selected) tone.copy(alpha = 0.09f) else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(4.dp))
        .border(1.dp, if (selected) tone.copy(alpha = 0.4f) else outlineColor(), RoundedCornerShape(4.dp))
        .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text(label, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
internal fun WorkspaceDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(outlineColor()))
}
