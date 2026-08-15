package com.sal7one.wrapperguard.intellij.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.wrapperguard.core.model.Finding
import com.sal7one.wrapperguard.core.model.ScriptEdge
import com.sal7one.wrapperguard.intellij.ui.components.Card
import com.sal7one.wrapperguard.intellij.ui.components.EmptyHint
import com.sal7one.wrapperguard.intellij.ui.components.ScrollableColumn
import com.sal7one.wrapperguard.intellij.ui.components.StatusBadge
import org.jetbrains.jewel.ui.component.Text

/**
 * Execution Graph tab. The weekend deliverable per AI_START_HERE is a *readable call-chain
 * tree*, not a graphical canvas. We render two things from the scan report:
 *   - Edges: the discovered script→script call graph (who calls whom, via which command).
 *   - Risky paths: each finding's [Finding.callChain], the root-to-finding trace.
 * A full canvas can follow later. Scrolls vertically.
 */
@Composable
fun ExecutionGraphTab(
    edges: List<ScriptEdge>,
    findings: List<Finding>,
    onOpenFinding: (Finding) -> Unit,
    modifier: Modifier = Modifier
) {
    ScrollableColumn(modifier = modifier) {
        val riskyPaths = findings.filter { it.callChain.isNotEmpty() }

        if (edges.isEmpty() && riskyPaths.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                EmptyHint("No script call chains discovered. Recursion is enabled by default.")
            }
        } else {
            if (riskyPaths.isNotEmpty()) {
                Section("Risky Paths (${riskyPaths.size})")
                riskyPaths.forEach { finding ->
                    CallChainCard(finding, onOpenFinding)
                }
            }

            if (edges.isNotEmpty()) {
                Section("Discovered Edges (${edges.size})")
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(PaddingValues(16.dp)), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        edges.forEach { edge ->
                            Row {
                                Text(
                                    "${edge.from.display}",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = mutedTextColor()
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "→ ${edge.viaCommand} →",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = mutedTextColor()
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    edge.to.display,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Column {
        Text(title, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = mutedTextColor())
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun CallChainCard(finding: Finding, onOpenFinding: (Finding) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        borderColor = severityColor(finding.severity).copy(alpha = 0.4f)
    ) {
        Column(modifier = Modifier.padding(PaddingValues(14.dp)), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(finding.severity.name, severityColor(finding.severity))
                Spacer(Modifier.width(8.dp))
                Text(finding.title, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                org.jetbrains.jewel.ui.component.OutlinedButton(onClick = { onOpenFinding(finding) }) {
                    Text("Open Trace", fontSize = 11.sp)
                }
            }
            finding.callChain.forEachIndexed { i, link ->
                Row {
                    Spacer(Modifier.width((i * 12).dp))
                    Text(
                        if (i == 0) "• " else "└ • ",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = mutedTextColor()
                    )
                    Text(
                        "${link.source.display}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        "  via  ${link.viaCommand}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = mutedTextColor()
                    )
                }
            }
        }
    }
}
