package com.sal7one.wrapperguard.intellij.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.wrapperguard.core.model.*
import com.sal7one.wrapperguard.intellij.ui.components.*
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

/** A list/inspector on wide windows, a list/detail route on narrow ones. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FindingsTab(
    findings: List<Finding>,
    state: FindingsViewState,
    onStateChange: (FindingsViewState) -> Unit,
    onOpenFinding: (Finding) -> Unit,
    modifier: Modifier = Modifier
) {
    var controlsExpanded by remember { mutableStateOf(false) }
    var searchField by remember { mutableStateOf(TextFieldValue(state.query)) }
    LaunchedEffect(state.query) { if (searchField.text != state.query) searchField = TextFieldValue(state.query) }
    val visible = remember(findings, state.query, state.scope, state.order, state.descending) {
        visibleFindings(findings, state)
    }
    val selected = state.selected?.let { previous -> visible.firstOrNull { it == previous }
        ?: visible.firstOrNull { it.fingerprint == previous.fingerprint && it.location == previous.location } }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= 760.dp
        if (!wide && selected != null) {
            FindingInspector(selected, onOpenFinding, onBack = { onStateChange(state.copy(selected = null)) })
        } else {
            Column(Modifier.fillMaxSize()) {
                // Wrap controls at their natural width; never squeeze a table and two filter bars into one row.
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextField(value = searchField,
                            onValueChange = { searchField = it; onStateChange(state.copy(query = it.text, selected = null)) },
                            modifier = Modifier.weight(1f), placeholder = { Text("Search findings") })
                        OutlinedButton(onClick = { controlsExpanded = !controlsExpanded }) {
                            Text(if (controlsExpanded) "Hide filters" else "Filter / sort")
                        }
                    }
                    Text("${visible.size} of ${findings.size} · ${state.scope.label} · ${state.order.label}${if (state.descending) " ↓" else " ↑"}",
                        color = mutedTextColor(), fontSize = 11.sp)
                    if (controlsExpanded) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FindingScope.entries.forEach { scope ->
                            ChoiceChip(scope.label, state.scope == scope) { onStateChange(state.copy(scope = scope, selected = null)) }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FindingOrder.entries.forEach { order ->
                            val active = state.order == order
                            ChoiceChip(order.label + if (active) { if (state.descending) " ↓" else " ↑" } else "", active) {
                                onStateChange(state.copy(order = order, descending = if (active) !state.descending else false))
                            }
                        }
                    }
                    }
                }
                WorkspaceDivider()
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    FindingList(visible, selected, { onStateChange(state.copy(selected = it)) },
                        Modifier.weight(1f).fillMaxHeight())
                    if (wide) {
                        Box(Modifier.width(1.dp).fillMaxHeight().background(outlineColor()))
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            if (selected == null) EmptyHint("Select a finding to inspect its evidence and remediation.")
                            else FindingInspector(selected, onOpenFinding)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FindingList(findings: List<Finding>, selected: Finding?, onSelect: (Finding) -> Unit, modifier: Modifier) {
    val list = rememberLazyListState()
    // A changed filter/sort must not leave the user looking at an arbitrary old scroll position.
    LaunchedEffect(findings) { list.scrollToItem(0) }
    Box(modifier) {
        if (findings.isEmpty()) EmptyHint("No findings match this view. Change the filter or search to see more.")
        LazyColumn(state = list, modifier = Modifier.fillMaxSize().padding(end = 12.dp)) {
            items(findings) { finding ->
                val tone = severityColor(finding.severity)
                Column(Modifier.fillMaxWidth()
                    .background(if (finding == selected) tone.copy(alpha = 0.10f) else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onSelect(finding) }
                    .padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(3.dp).height(16.dp).background(tone))
                        Text(finding.title, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium,
                            fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Text(finding.location.display, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, color = mutedTextColor())
                    Text("${finding.ruleId} · ${finding.severity.name.lowercase()} · ${finding.status.name.lowercase()} · ${finding.policyAction.name.lowercase()}",
                        fontSize = 10.sp, color = mutedTextColor())
                }
                WorkspaceDivider()
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(list), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FindingInspector(finding: Finding, onOpen: (Finding) -> Unit, onBack: (() -> Unit)? = null) {
    WorkspacePage {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onBack != null) OutlinedButton(onClick = onBack) { Text("← Findings") }
            OutlinedButton(onClick = { onOpen(finding) }) { Text("Open in editor") }
        }
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(finding.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(finding.ruleId, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = mutedTextColor())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusBadge(finding.severity.name, severityColor(finding.severity))
                    StatusBadge(finding.policyAction.name, policyActionColor(finding.policyAction))
                    StatusBadge(finding.status.name, mutedTextColor())
                }
                LabeledValue("Location", finding.location.display, mono = true)
                LabeledValue("Confidence", finding.confidence.name.lowercase())
                LabeledValue("Reachability", if (finding.reachable) "Reachable from an entry point" else "Not linked to an entry point")
                if (finding.message.isNotBlank()) WorkspaceSection("What was found") { Text(finding.message, fontSize = 12.sp) }
                finding.evidence?.let { evidence ->
                    WorkspaceSection(if (evidence.redacted) "Evidence · secrets redacted" else "Evidence") {
                        Box(Modifier.fillMaxWidth().background(cardSurfaceColor()).border(1.dp, outlineColor()).padding(12.dp)) {
                            Text(evidence.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }
                    }
                }
                finding.remediation?.let { remediation ->
                    WorkspaceSection("Recommended action") { Text(remediation, fontSize = 12.sp) }
                }
                if (finding.callChain.isNotEmpty()) WorkspaceSection("Execution path") {
                    finding.callChain.forEachIndexed { index, link ->
                        Text("${index + 1}. ${link.source.display}\n${link.viaCommand}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}
