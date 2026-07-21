package com.sal7one.wrapperguard.intellij.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.wrapperguard.core.model.Finding
import com.sal7one.wrapperguard.core.model.PolicyAction
import com.sal7one.wrapperguard.core.model.Severity
import com.sal7one.wrapperguard.intellij.ui.components.EmptyHint
import com.sal7one.wrapperguard.intellij.ui.components.LabeledValue
import com.sal7one.wrapperguard.intellij.ui.components.StatusBadge
import org.jetbrains.jewel.ui.component.Text

private enum class SeverityFilter(val label: String) {
    ALL("All Severities"), ERROR("Errors Only"), WARN("Warnings Only"), INFO("Info Only")
}

private enum class PolicyFilter(val label: String) {
    ALL("All Actions"), BLOCK("Blocking Only"), REVIEW("Review Only"), ALLOW("Allowed")
}

/**
 * Findings tab: a filter bar, a compact findings list with severity dots + badges, and a rich
 * detail inspector (rule id, severity/confidence/policy, location, evidence, execution path,
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
    var selectedIndex by remember(findings) { mutableStateOf<Int?>(null) }

    val filtered = remember(findings, severityFilter, policyFilter) {
        findings.filter { f ->
            (severityFilter == SeverityFilter.ALL || f.severity.name == severityFilter.name) &&
                (policyFilter == PolicyFilter.ALL || f.policyAction.name == policyFilter.name)
        }
    }

    val selected = selectedIndex?.let { idx ->
        val inFiltered = filtered.getOrNull(idx)
        // The index is relative to `filtered`; if it drifts past the list, clear it.
        if (inFiltered != null && (selectedIndex ?: -1) < filtered.size) inFiltered else null
    }

    Column(modifier = modifier.fillMaxWidth()) {
        FilterBar(
            total = findings.size,
            shown = filtered.size,
            severityFilter = severityFilter,
            policyFilter = policyFilter,
            onSeverityChange = { severityFilter = it; selectedIndex = null },
            onPolicyChange = { policyFilter = it; selectedIndex = null }
        )

        if (findings.isEmpty()) {
            EmptyHint("No findings — this project is clean.")
        } else {
            Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FindingsList(
                    findings = filtered,
                    selectedIndex = selectedIndex,
                    onSelect = { index ->
                        selectedIndex = if (selectedIndex == index) null else index
                        filtered.getOrNull(index)?.let(onOpenFinding)
                    },
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

@Composable
private fun FilterBar(
    total: Int,
    shown: Int,
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
            Box(
                modifier = Modifier
                    .background(if (isSelected) verdictColor(com.sal7one.wrapperguard.core.model.ScanVerdict.REVIEW_REQUIRED).copy(alpha = 0.18f) else Color.Unspecified)
                    .clickable { onChange(option) }
                    .padding(PaddingValues(horizontal = 8.dp, vertical = 3.dp))
            ) {
                Text(
                    text = labelOf(option),
                    fontSize = 11.sp,
                    color = if (isSelected) verdictColor(com.sal7one.wrapperguard.core.model.ScanVerdict.REVIEW_REQUIRED) else mutedTextColor(),
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
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
    modifier: Modifier = Modifier
) {
    if (findings.isEmpty()) {
        Box(
            modifier = modifier.border(1.dp, outlineColor(), RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) { EmptyHint("No findings match the current filters.") }
    } else {
        LazyColumn(
            modifier = modifier.border(1.dp, outlineColor(), RoundedCornerShape(6.dp)),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            items(findings.size) { index ->
                val finding = findings[index]
                FindingsRow(
                    finding = finding,
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) }
                )
            }
        }
    }
}

@Composable
private fun FindingsRow(finding: Finding, selected: Boolean, onClick: () -> Unit) {
    val tone = severityColor(finding.severity)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) tone.copy(alpha = 0.12f) else Color.Unspecified)
            .clickable(onClick = onClick)
            .padding(PaddingValues(horizontal = 12.dp, vertical = 8.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(50))
                .background(tone)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(finding.title, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
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
                    color = mutedTextColor()
                )
            }
        }
        StatusBadge(
            text = policyLabel(finding.policyAction),
            tone = policyActionColor(finding.policyAction)
        )
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
                }
                Text(finding.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)

                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    LabeledValue("Rule ID", finding.ruleId, mono = true)
                    LabeledValue("Confidence", finding.confidence.name)
                    LabeledValue("Category", finding.category.name)
                }
                LabeledValue("Location", finding.location.display, mono = true)

                if (finding.message.isNotBlank()) {
                    SectionLabel("Message")
                    Text(finding.message, fontSize = 12.sp)
                }
                finding.evidence?.let { evidence ->
                    SectionLabel("Evidence")
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(cardSurfaceColor().copy(alpha = 0.6f))
                            .border(1.dp, border, RoundedCornerShape(4.dp))
                            .padding(8.dp)
                    ) {
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
