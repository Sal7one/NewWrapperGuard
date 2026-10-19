package com.sal7one.wrapperguard.intellij.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.wrapperguard.core.model.*
import com.sal7one.wrapperguard.core.vetting.VettingAction
import com.sal7one.wrapperguard.intellij.ui.components.*
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

/** A dense issue table and bottom evidence dock; narrow toolwindows use a detail route. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FindingsTab(
    findings: List<Finding>, state: FindingsViewState,
    onStateChange: (FindingsViewState) -> Unit, onOpenFinding: (Finding) -> Unit,
    modifier: Modifier = Modifier, providerNames: Map<String, String> = emptyMap()
) {
    var controlsExpanded by remember { mutableStateOf(false) }
    var searchField by remember { mutableStateOf(TextFieldValue(state.query)) }
    LaunchedEffect(state.query) { if (searchField.text != state.query) searchField = TextFieldValue(state.query) }
    val visible = remember(findings, state.query, state.scope, state.order, state.descending) { visibleFindings(findings, state) }
    val selected = state.selected?.let { previous -> visible.firstOrNull { it == previous }
        ?: visible.firstOrNull { it.fingerprint == previous.fingerprint && it.location == previous.location } }
    fun name(finding: Finding) = providerNames[finding.providerId ?: "wrapperguard"] ?: finding.providerId ?: "WrapperGuard"
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= 920.dp
        val dockHeight = (maxHeight * 0.30f).coerceIn(218.dp, 270.dp)
        if (!wide && selected != null) {
            FindingInspector(selected, onOpenFinding, onBack = { onStateChange(state.copy(selected = null)) }, providerName = name(selected))
        } else Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Findings", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Text("${visible.size} results", color = mutedTextColor(), fontSize = 11.sp, modifier = Modifier.weight(1f))
                TextField(value = searchField,
                    onValueChange = { searchField = it; onStateChange(state.copy(query = it.text, selected = null)) },
                    modifier = Modifier.widthIn(min = 130.dp, max = 350.dp).weight(1f),
                    placeholder = { Text("Search findings, paths, providers…", fontSize = 11.sp) })
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                    FindingScope.entries.forEach { scope ->
                        val count = when (scope) {
                            FindingScope.ALL -> findings.size
                            FindingScope.BLOCKERS -> findings.count { it.status == FindingStatus.ACTIVE && it.policyAction == PolicyAction.BLOCK }
                            FindingScope.REVIEW -> findings.count { it.status == FindingStatus.ACTIVE && it.policyAction == PolicyAction.REVIEW }
                            FindingScope.SUPPRESSED -> findings.count { it.status == FindingStatus.SUPPRESSED }
                        }
                        ChoiceChip("${scope.label}  $count", state.scope == scope) { onStateChange(state.copy(scope = scope, selected = null)) }
                    }
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { controlsExpanded = !controlsExpanded }) {
                    Text(if (controlsExpanded) "Hide sort" else "${state.order.label} ↕", fontSize = 11.sp)
                }
            }
            if (controlsExpanded) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FindingOrder.entries.forEach { order ->
                    val active = state.order == order
                    ChoiceChip(order.label + if (active) { if (state.descending) " ↓" else " ↑" } else "", active) {
                        onStateChange(state.copy(order = order, descending = if (active) !state.descending else false))
                    }
                }
            }
            Column(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(4.dp)).background(cardSurfaceColor())
                .border(1.dp, outlineColor(), RoundedCornerShape(4.dp))) {
                FindingList(visible, selected, { onStateChange(state.copy(selected = it)) }, providerNames, Modifier.fillMaxWidth().weight(1f))
                if (wide && selected != null) {
                    WorkspaceDivider()
                    Box(Modifier.fillMaxWidth().height(dockHeight).background(raisedSurfaceColor())) {
                        FindingDock(selected, onOpenFinding, name(selected), onClose = { onStateChange(state.copy(selected = null)) })
                    }
                }
            }
            Text("${visible.size} of ${findings.size} findings  ·  Provider actions reflect the grants used for this scan.", color = mutedTextColor(), fontSize = 11.sp)
        }
    }
}

@Composable
private fun FindingList(findings: List<Finding>, selected: Finding?, onSelect: (Finding) -> Unit,
                        providerNames: Map<String, String>, modifier: Modifier) {
    val list = rememberLazyListState()
    LaunchedEffect(findings) { list.scrollToItem(0) }
    BoxWithConstraints(modifier) {
        val showLocation = maxWidth >= 700.dp
        val showProvider = maxWidth >= 1100.dp
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(31.dp).background(raisedSurfaceColor()).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TableHeader("STATUS", Modifier.width(90.dp))
                TableHeader(if (showLocation) "FINDING" else "FINDING / SOURCE", Modifier.weight(1.05f))
                if (showLocation) TableHeader("LOCATION", Modifier.weight(1f))
                if (showProvider) TableHeader("PROVIDER", Modifier.width(145.dp))
            }
            WorkspaceDivider()
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (findings.isEmpty()) EmptyHint("No findings match this view. Change the filter or search to see more.")
                LazyColumn(state = list, modifier = Modifier.fillMaxSize().padding(end = 9.dp)) {
                    items(findings) { finding ->
                        val isSelected = finding == selected
                        val tone = if (finding.status == FindingStatus.ACTIVE) policyActionColor(finding.policyAction) else mutedTextColor()
                        Row(Modifier.fillMaxWidth().height(if (showLocation) 32.dp else 51.dp)
                            .background(if (isSelected) accentColor().copy(alpha = 0.11f) else cardSurfaceColor())
                            .clickable { onSelect(finding) }.padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(90.dp)) { StatusBadge(findingStatusLabel(finding), tone) }
                            Column(Modifier.weight(1.05f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(finding.title, fontSize = 13.sp, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (!showLocation) Text(finding.location.display, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = mutedTextColor())
                            }
                            if (showLocation) Text(finding.location.display, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, color = mutedTextColor(), modifier = Modifier.weight(1f))
                            if (showProvider) Text(providerNames[finding.providerId ?: "wrapperguard"] ?: finding.providerId ?: "WrapperGuard",
                                fontSize = 11.sp, color = mutedTextColor(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(145.dp))
                        }
                        WorkspaceDivider()
                    }
                }
                VerticalScrollbar(rememberScrollbarAdapter(list), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
    }
}

@Composable private fun TableHeader(label: String, modifier: Modifier) {
    Text(label, modifier = modifier, color = mutedTextColor(), fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FindingDock(finding: Finding, onOpen: (Finding) -> Unit, providerName: String, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Inspector", color = mutedTextColor(), fontSize = 11.sp)
            Box(Modifier.width(1.dp).height(14.dp).background(outlineColor()))
            Text(finding.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            StatusBadge(findingStatusLabel(finding), if (finding.status == FindingStatus.ACTIVE) policyActionColor(finding.policyAction) else mutedTextColor())
            WorkspacePrimaryButton("Open in editor ↗", onClick = { onOpen(finding) })
            Box(Modifier.size(24.dp).clip(RoundedCornerShape(3.dp)).clickable(role = Role.Button, onClick = onClose)
                .semantics { contentDescription = "Close inspector" }, contentAlignment = Alignment.Center) {
                Text("×", fontSize = 18.sp, color = mutedTextColor())
            }
        }
        WorkspaceDivider()
        val scroll = rememberScrollState()
        Box(Modifier.fillMaxWidth().weight(1f)) {
            SelectionContainer {
                Row(Modifier.fillMaxSize().verticalScroll(scroll).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    Column(Modifier.weight(0.38f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text(finding.message, fontSize = 12.sp)
                        Text(finding.location.display, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = accentColor())
                        Text("$providerName  ·  ${finding.ruleId}", fontSize = 11.sp, color = mutedTextColor(), fontFamily = FontFamily.Monospace)
                        Text("${finding.severity.name.lowercase().replaceFirstChar { it.uppercase() }}  ·  ${finding.category.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }}  ·  ${finding.confidence.name.lowercase().replaceFirstChar { it.uppercase() }} confidence  ·  ${if (finding.reachable) "Reachable" else "Not linked to entry point"}", fontSize = 11.sp, color = mutedTextColor())
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            finding.actions.sortedBy { it.ordinal }.forEach { action -> StatusBadge(actionLabel(action), policyActionColor(finding.policyAction)) }
                            if (finding.actions.isEmpty()) Text("No authorized actions", fontSize = 11.sp, color = mutedTextColor())
                        }
                    }
                    Column(Modifier.weight(0.62f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        finding.evidence?.let { evidence ->
                            Text(if (evidence.redacted) "EVIDENCE · SECRETS REDACTED" else "EVIDENCE", color = mutedTextColor(), fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp)
                            Box(Modifier.fillMaxWidth().background(insetSurfaceColor(), RoundedCornerShape(3.dp)).border(1.dp, outlineColor(), RoundedCornerShape(3.dp)).padding(12.dp)) {
                                Text(evidence.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            }
                        }
                        finding.remediation?.let { remediation ->
                            Text("RECOMMENDED ACTION", color = mutedTextColor(), fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp)
                            Text(remediation, fontSize = 12.sp)
                        }
                        if (finding.callChain.isNotEmpty()) {
                            Text("EXECUTION PATH", color = mutedTextColor(), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            finding.callChain.forEach { link ->
                                Text("${link.source.display}  →  ${link.viaCommand}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = mutedTextColor())
                            }
                        }
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FindingInspector(finding: Finding, onOpen: (Finding) -> Unit, onBack: (() -> Unit)? = null, providerName: String? = null) {
    WorkspacePage {
        if (onBack != null) OutlinedButton(onClick = onBack) { Text("← Findings", fontSize = 12.sp) }
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(finding.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusBadge(finding.severity.name, severityColor(finding.severity))
                    StatusBadge(findingStatusLabel(finding), if (finding.status == FindingStatus.ACTIVE) policyActionColor(finding.policyAction) else mutedTextColor())
                }
                Text("${providerName ?: finding.providerId ?: "WrapperGuard"}  ·  ${finding.ruleId}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = mutedTextColor())
                WorkspaceDivider()
                Text(finding.location.display, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = accentColor())
                WorkspacePrimaryButton("Open in editor ↗", onClick = { onOpen(finding) })
                if (finding.message.isNotBlank()) WorkspaceSection("What was found") { Text(finding.message, fontSize = 13.sp) }
                finding.evidence?.let { evidence ->
                    WorkspaceSection(if (evidence.redacted) "Evidence · secrets redacted" else "Evidence") {
                        Box(Modifier.fillMaxWidth().background(insetSurfaceColor(), RoundedCornerShape(4.dp)).border(1.dp, outlineColor(), RoundedCornerShape(4.dp)).padding(12.dp)) {
                            Text(evidence.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }
                    }
                }
                finding.remediation?.let { remediation -> WorkspaceSection("Recommended action") { Text(remediation, fontSize = 13.sp) } }
                WorkspaceDivider()
                WorkspaceSection("Provider permissions") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        finding.actions.sortedBy { it.ordinal }.forEach { action -> StatusBadge(actionLabel(action), policyActionColor(finding.policyAction)) }
                        if (finding.actions.isEmpty()) Text("No authorized actions", fontSize = 11.sp, color = mutedTextColor())
                    }
                    MetadataRow("Confidence", finding.confidence.name.lowercase().replaceFirstChar { it.uppercase() })
                    MetadataRow("Reachability", if (finding.reachable) "Linked to an entry point" else "Not linked to an entry point")
                }
                if (finding.callChain.isNotEmpty()) WorkspaceSection("Execution path") {
                    finding.callChain.forEachIndexed { index, link ->
                        Text("${index + 1}. ${link.source.display}", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        Text(link.viaCommand, color = mutedTextColor(), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

internal fun findingStatusLabel(finding: Finding): String = when (finding.status) {
    FindingStatus.SUPPRESSED -> "Suppressed"
    FindingStatus.ACCEPTED -> "Accepted"
    FindingStatus.FIXED -> "Fixed"
    FindingStatus.ACTIVE -> when (finding.policyAction) { PolicyAction.BLOCK -> "Block"; PolicyAction.REVIEW -> "Review"; PolicyAction.ALLOW -> "Audit" }
}

internal fun actionLabel(action: VettingAction): String = when (action) {
    VettingAction.WARNING -> "Warning"
    VettingAction.MAJOR_WARNING -> "Major warning"
    VettingAction.ALERT -> "Alert"
    VettingAction.BLOCK_BUILD -> "Stop build"
    VettingAction.BLOCK_SYNC -> "Stop sync"
}
