package com.sal7one.wrapperguard.intellij.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.wrapperguard.core.model.Finding
import com.sal7one.wrapperguard.core.model.FindingCategory
import com.sal7one.wrapperguard.core.model.PolicyAction
import com.sal7one.wrapperguard.intellij.ui.components.EmptyHint
import com.sal7one.wrapperguard.intellij.ui.components.LabeledValue
import com.sal7one.wrapperguard.intellij.ui.components.StatusBadge
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

private enum class SeverityFilter(val label: String) {
    ALL("All Severities"), ERROR("Errors Only"), WARN("Warnings Only"), INFO("Info Only")
}

private enum class PolicyFilter(val label: String) {
    ALL("All Actions"), BLOCK("Blocking Only"), REVIEW("Review Only"), ALLOW("Allowed")
}

/**
 * Findings tab: search + severity/policy/category filters, a compact findings table with
 * severity dot + badges + column headers, and a rich detail inspector (rule id, severity/
 * confidence/policy, location, evidence snippet with line numbers, execution path,
 * remediation). Empty states for no-filter-matches and clean.
 */
@Composable
fun FindingsTab(
    findings: List<Finding>,
    onOpenFinding: (Finding) -> Unit,
    modifier: Modifier = Modifier
) {
    var severityFilter by remember { mutableStateOf(SeverityFilter.ALL) }
    var policyFilter by remember { mutableStateOf(PolicyFilter.ALL) }
    var categoryFilter by remember { mutableStateOf<FindingCategory?>(null) }
    var query by remember { mutableStateOf(TextFieldValue("")) }
    var selectedIndex by remember(findings) { mutableStateOf<Int?>(null) }

    val categoriesPresent = remember(findings) {
        findings.map { it.category }.distinct().sortedBy { it.name }
    }

    val filtered = remember(findings, severityFilter, policyFilter, categoryFilter, query) {
        val q = query.text.trim().lowercase()
        findings.filter { f ->
            (severityFilter == SeverityFilter.ALL || f.severity.name == severityFilter.name) &&
                (policyFilter == PolicyFilter.ALL || f.policyAction.name == policyFilter.name) &&
                (categoryFilter == null || f.category == categoryFilter) &&
                (q.isEmpty() || f.title.lowercase().contains(q) ||
                    f.ruleId.lowercase().contains(q) ||
                    f.location.path.lowercase().contains(q) ||
                    (f.evidence?.text?.lowercase()?.contains(q) ?: false))
        }
    }

    val selected = selectedIndex?.let { idx -> filtered.getOrNull(idx) }

    Column(modifier = modifier.fillMaxWidth()) {
        FilterBar(
            total = findings.size,
            shown = filtered.size,
            query = query,
            onQueryChange = { query = it; selectedIndex = null },
            severityFilter = severityFilter,
            policyFilter = policyFilter,
            onSeverityChange = { severityFilter = it; selectedIndex = null },
            onPolicyChange = { policyFilter = it; selectedIndex = null }
        )

        // Category chips — visible, horizontally scrollable, better than a dropdown for the
        // small set of categories actually present in the report.
        if (categoriesPresent.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CategoryChip(
                    label = "All Categories",
                    selected = categoryFilter == null,
                    onClick = { categoryFilter = null; selectedIndex = null }
                )
                categoriesPresent.forEach { category ->
                    CategoryChip(
                        label = categoryLabel(category),
                        selected = categoryFilter == category,
                        onClick = { categoryFilter = category; selectedIndex = null }
                    )
                }
            }
        }

        if (findings.isEmpty()) {
            EmptyHint("No findings — this project is clean.")
        } else {
            Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FindingsList(
                    findings = filtered,
                    selectedIndex = selectedIndex,
                    onSelect = { index ->
                        selectedIndex = if (selectedIndex == index) null else index
                    },
                    onOpen = onOpenFinding,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
                FindingDetail(
                    finding = selected,
                    onOpen = onOpenFinding,
                    modifier = Modifier.weight(1.1f).fillMaxHeight()
                )
            }
        }
    }
}

private fun categoryLabel(category: FindingCategory): String = category.name
    .lowercase().replace('_', ' ')
    .replaceFirstChar { it.uppercase() }

@Composable
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val accent = verdictAccentColor()
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) accent.asBadgeBackgroundSafe(0.18f) else Color.Unspecified)
            .border(1.dp, if (selected) accent.copy(alpha = 0.55f) else outlineColor(), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = if (selected) accent else mutedTextColor(),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1
        )
    }
}

// Local alias so chips don't import from the app file.
@Composable
private fun verdictAccentColor(): Color =
    if (org.jetbrains.jewel.foundation.theme.JewelTheme.isDark) Color(0xFFC3C0FF) else Color(0xFF4F46E5)

@Composable
private fun Color.asBadgeBackgroundSafe(alpha: Float): Color = copy(alpha = alpha)

@Composable
private fun FilterBar(
    total: Int,
    shown: Int,
    query: TextFieldValue,
    onQueryChange: (TextFieldValue) -> Unit,
    severityFilter: SeverityFilter,
    policyFilter: PolicyFilter,
    onSeverityChange: (SeverityFilter) -> Unit,
    onPolicyChange: (PolicyFilter) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(PaddingValues(horizontal = 16.dp, vertical = 10.dp)),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("$shown / $total findings", fontSize = 12.sp, color = mutedTextColor())
        Spacer(Modifier.weight(1f))
        TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.width(180.dp),
            placeholder = { Text("Search findings…", fontSize = 11.sp, color = mutedTextColor()) }
        )
        SegmentedControl(
            options = SeverityFilter.values().toList(),
            selected = severityFilter,
            onChange = onSeverityChange,
            labelOf = { it.label }
        )
        SegmentedControl(
            options = PolicyFilter.values().toList(),
            selected = policyFilter,
            onChange = onPolicyChange,
            labelOf = { it.label }
        )
    }
}

@Composable
private fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    onChange: (T) -> Unit,
    labelOf: (T) -> String
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, outlineColor(), RoundedCornerShape(4.dp))
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val accent = verdictAccentColor()
            Box(
                modifier = Modifier
                    .background(if (isSelected) accent.copy(alpha = 0.18f) else Color.Unspecified)
                    .clickable { onChange(option) }
                    .padding(PaddingValues(horizontal = 8.dp, vertical = 3.dp))
            ) {
                Text(
                    text = labelOf(option),
                    fontSize = 11.sp,
                    color = if (isSelected) accent else mutedTextColor(),
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun FindingsList(
    findings: List<Finding>,
    selectedIndex: Int?,
    onSelect: (Int) -> Unit,
    onOpen: (Finding) -> Unit,
    modifier: Modifier = Modifier
) {
    if (findings.isEmpty()) {
        Box(
            modifier = modifier.border(1.dp, outlineColor(), RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) { EmptyHint("No findings match the current filters.") }
    } else {
        Column(modifier = modifier.border(1.dp, outlineColor(), RoundedCornerShape(6.dp))) {
            // Column headers, per the design's table.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(cardSurfaceColor())
                    .border(1.dp, outlineColor())
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HeaderCell("SEV", Modifier.size(20.dp))
                HeaderCell("ISSUE", Modifier.weight(1f))
                HeaderCell("CATEGORY", Modifier.width(110.dp))
                HeaderCell("ACTION", Modifier.width(110.dp), alignEnd = true)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                items(findings.size) { index ->
                    val finding = findings[index]
                    FindingsRow(
                        finding = finding,
                        selected = index == selectedIndex,
                        onClick = { onSelect(index) },
                        onDoubleClick = { onOpen(finding) }
                    )
                }
            }
        }
    }
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier = Modifier, alignEnd: Boolean = false) {
    Box(modifier = modifier, contentAlignment = if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart) {
        Text(
            text = text,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = mutedTextColor()
        )
    }
}

@Composable
private fun FindingsRow(
    finding: Finding,
    selected: Boolean,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit
) {
    val tone = severityColor(finding.severity)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) tone.copy(alpha = 0.12f) else Color.Unspecified)
            .clickable(onClick = onClick)
            .padding(PaddingValues(horizontal = 12.dp, vertical = 7.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Severity dot column.
        Box(
            modifier = Modifier
                .size(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(tone)
            )
        }
        // Issue + location.
        Column(modifier = Modifier.weight(1f)) {
            Text(finding.title, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    finding.ruleId,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = mutedTextColor()
                )
                Text(
                    finding.location.display,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = mutedTextColor(),
                    maxLines = 1
                )
                if (!finding.reachable) {
                    StatusBadge("Unreachable", mutedTextColor())
                }
            }
        }
        // Category column.
        Box(modifier = Modifier.width(110.dp)) {
            Text(
                categoryLabel(finding.category),
                fontSize = 10.sp,
                color = mutedTextColor(),
                maxLines = 1
            )
        }
        // Action badge column.
        Box(modifier = Modifier.width(110.dp), contentAlignment = Alignment.CenterEnd) {
            StatusBadge(
                text = policyShortLabel(finding.policyAction),
                tone = policyActionColor(finding.policyAction)
            )
        }
    }
}

@Composable
private fun FindingDetail(finding: Finding?, onOpen: (Finding) -> Unit, modifier: Modifier = Modifier) {
    val border = outlineColor()
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(cardSurfaceColor())
            .border(1.dp, border, RoundedCornerShape(6.dp))
    ) {
        if (finding == null) {
            EmptyHint("Select a finding to inspect evidence and remediation.")
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(PaddingValues(16.dp)),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusBadge(finding.severity.name, severityColor(finding.severity))
                    StatusBadge(policyLabel(finding.policyAction), policyActionColor(finding.policyAction))
                    if (!finding.reachable) StatusBadge("Unreachable", mutedTextColor())
                }
                Text(finding.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)

                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    LabeledValue("Rule ID", finding.ruleId, mono = true)
                    LabeledValue("Confidence", finding.confidence.name)
                    LabeledValue("Category", categoryLabel(finding.category))
                }
                LabeledValue("Location", finding.location.display, mono = true)

                if (finding.message.isNotBlank()) {
                    SectionLabel("Message")
                    Text(finding.message, fontSize = 12.sp)
                }

                // Evidence snippet with a line-number chip and the offending line highlighted,
                // mirroring the design's code-snippet block.
                finding.evidence?.let { evidence ->
                    SectionLabel("Evidence")
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(severityColor(finding.severity).copy(alpha = 0.10f))
                            .border(1.dp, border, RoundedCornerShape(4.dp))
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val lineNo = finding.location.line?.toString() ?: "—"
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(3.dp))
                                .background(severityColor(finding.severity).copy(alpha = 0.22f))
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = lineNo,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = severityColor(finding.severity),
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            evidence.text,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                if (finding.callChain.isNotEmpty()) {
                    SectionLabel("Execution Path")
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        finding.callChain.forEach { link ->
                            Text(
                                "${link.source.display}  →  ${link.viaCommand}",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = mutedTextColor()
                            )
                        }
                    }
                }

                finding.remediation?.let {
                    SectionLabel("Remediation")
                    Text(it, fontSize = 12.sp)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    org.jetbrains.jewel.ui.component.DefaultButton(onClick = { onOpen(finding) }) {
                        Text("Open in Editor")
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        color = mutedTextColor()
    )
}

private fun policyLabel(action: PolicyAction): String = when (action) {
    PolicyAction.BLOCK -> "Blocks Execution"
    PolicyAction.REVIEW -> "Review"
    PolicyAction.ALLOW -> "Allowed"
}

private fun policyShortLabel(action: PolicyAction): String = when (action) {
    PolicyAction.BLOCK -> "BLOCK"
    PolicyAction.REVIEW -> "REVIEW"
    PolicyAction.ALLOW -> "ALLOW"
}
