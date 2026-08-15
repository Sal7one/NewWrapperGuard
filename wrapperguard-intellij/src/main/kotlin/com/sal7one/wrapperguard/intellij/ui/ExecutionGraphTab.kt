package com.sal7one.wrapperguard.intellij.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.wrapperguard.core.model.Confidence
import com.sal7one.wrapperguard.core.model.Finding
import com.sal7one.wrapperguard.core.model.FindingCategory
import com.sal7one.wrapperguard.core.model.FindingKind
import com.sal7one.wrapperguard.core.model.FindingStatus
import com.sal7one.wrapperguard.core.model.PolicyAction
import com.sal7one.wrapperguard.core.model.ScriptEdge
import com.sal7one.wrapperguard.core.model.Severity
import com.sal7one.wrapperguard.core.model.SourceLocation
import com.sal7one.wrapperguard.intellij.ui.components.Card
import com.sal7one.wrapperguard.intellij.ui.components.EmptyHint
import com.sal7one.wrapperguard.intellij.ui.components.ScrollableColumn
import com.sal7one.wrapperguard.intellij.ui.components.StatusBadge
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text

/**
 * Execution Graph tab: a real layered call-graph canvas.
 *
 * Layout: roots on the left, called scripts to the right, one column per depth. Layer order
 * is computed with a barycenter pass (top-down, then bottom-up) to reduce edge crossings.
 * Edges are cubic beziers with arrowheads; hops that land on a BLOCK finding are red and
 * dashed. Node cards carry the design's category chip / suspicious badge / rule footer and
 * open the finding (or the file) on click. Below the canvas: detached files with findings
 * and the risky-path explorer.
 */
@Composable
fun ExecutionGraphTab(
    edges: List<ScriptEdge>,
    findings: List<Finding>,
    onOpenFinding: (Finding) -> Unit,
    modifier: Modifier = Modifier
) {
    ScrollableColumn(modifier = modifier) {
        val nodesByPath = remember(findings) { findings.groupBy { it.location.path } }
        val blockerByPath = remember(findings) {
            findings.filter { it.policyAction == PolicyAction.BLOCK }
                .groupBy { it.location.path }
                .mapValues { (_, list) -> list.maxBy { it.severity.ordinal } }
        }
        val suspLines = remember(findings) {
            findings.filter { it.policyAction == PolicyAction.BLOCK }
                .map { it.location.path + ":" + (it.location.line ?: "") }
                .toSet()
        }

        if (edges.isEmpty() && findings.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                EmptyHint("No script call chains discovered. Recursion is enabled by default.")
            }
            return@ScrollableColumn
        }

        if (edges.isNotEmpty()) {
            val layout = remember(edges, suspLines) { GraphLayout.build(edges, suspLines) }
            Section("Call-chain graph (${layout.nodes.size} nodes · ${edges.size} edges)")
            CallGraphCanvas(layout, blockerByPath, nodesByPath, onOpenFinding)
        }

        // Detached files: findings whose files are not part of any call chain.
        val detached = findings.map { it.location.path }.distinct()
            .filter { p -> edges.none { it.from.path == p || it.to.path == p } }
        if (detached.isNotEmpty()) {
            Section("Detached files (${detached.size})")
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                detached.sorted().forEach { path ->
                    val finding = blockerByPath[path] ?: nodesByPath[path]?.maxBy { it.severity.ordinal }
                    DetachedChip(path, isBlocker = blockerByPath.containsKey(path), onClick = {
                        onOpenFinding(finding ?: syntheticFinding(path))
                    })
                }
            }
        }

        val riskyPaths = findings.filter { it.callChain.isNotEmpty() }
        if (riskyPaths.isNotEmpty()) {
            Section("Paths to blockers (${riskyPaths.size})")
            riskyPaths.forEach { finding -> CallChainCard(finding, onOpenFinding) }
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusBadge("▶ entry", graphAccent())
            StatusBadge("script hop", outlineColor())
            StatusBadge("blocker", severityColor(Severity.ERROR))
        }
    }
}

/* ── Canvas ─────────────────────────────────────────────────────────────────── */

@Composable
private fun CallGraphCanvas(
    layout: GraphLayout,
    blockerByPath: Map<String, Finding>,
    nodesByPath: Map<String, List<Finding>>,
    onOpenFinding: (Finding) -> Unit
) {
    val nodeRects = remember { mutableStateMapOf<String, Rect>() }
    val canvasOrigin = remember { mutableStateOf(Offset.Zero) }
    val textMeasurer = rememberTextMeasurer()

    // Resolve colors outside the draw scope (theme colors are @Composable).
    val normalEdge = outlineColor()
    val errorEdge = severityColor(Severity.ERROR)
    val labelColor = mutedTextColor()
    val errorLabel = severityColor(Severity.ERROR)
    val gridColor = outlineColor().copy(alpha = 0.18f)

    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Box(modifier = Modifier.padding(vertical = 12.dp)) {
            Canvas(
                modifier = Modifier
                    .matchParentSize()
                    .onGloballyPositioned { canvasOrigin.value = it.boundsInRoot().topLeft }
            ) {
                // Dot grid background.
                val step = 22.dp.toPx()
                var gx = 0f
                while (gx < size.width) {
                    var gy = 0f
                    while (gy < size.height) {
                        drawCircle(gridColor, radius = 1.4f, center = Offset(gx, gy))
                        gy += step
                    }
                    gx += step
                }

                layout.edgeList.forEach { edge ->
                    val a = nodeRects[edge.from] ?: return@forEach
                    val b = nodeRects[edge.to] ?: return@forEach
                    val origin = canvasOrigin.value
                    val start = Offset(a.right, a.center.y) - origin
                    val end = Offset(b.left, b.center.y) - origin
                    val c1 = start + Offset(30.dp.toPx(), 0f)
                    val c2 = end - Offset(30.dp.toPx(), 0f)
                    val color = if (edge.isError) errorEdge else normalEdge

                    val path = Path().apply {
                        moveTo(start.x, start.y)
                        cubicTo(c1.x, c1.y, c2.x, c2.y, end.x, end.y)
                    }
                    drawPath(
                        path = path,
                        color = color,
                        style = if (edge.isError)
                            Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 7f)))
                        else
                            Stroke(width = 1.5f)
                    )

                    // Arrowhead oriented along the final tangent.
                    val rawDir = end - c2
                    val len = rawDir.getDistance()
                    val dir = if (len == 0f) Offset(1f, 0f) else rawDir / len
                    val perp = Offset(-dir.y, dir.x)
                    val tip = Path().apply {
                        moveTo(end.x, end.y)
                        lineTo(end.x - dir.x * 9f + perp.x * 4.5f, end.y - dir.y * 9f + perp.y * 4.5f)
                        lineTo(end.x - dir.x * 9f - perp.x * 4.5f, end.y - dir.y * 9f - perp.y * 4.5f)
                        close()
                    }
                    drawPath(tip, color)

                    // Edge label at the midpoint.
                    val label = edge.line?.let { "line:$it" } ?: edge.via.take(22)
                    val mid = Offset((start.x + end.x) / 2f, (start.y + end.y) / 2f - 8.dp.toPx())
                    drawText(
                        textMeasurer = textMeasurer,
                        text = label,
                        topLeft = mid,
                        style = TextStyle(
                            color = if (edge.isError) errorLabel else labelColor,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(64.dp)) {
                layout.layers.forEach { layer ->
                    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        layer.forEach { name ->
                            val blocker = blockerByPath[name]
                            val finding = blocker ?: nodesByPath[name]?.maxBy { it.severity.ordinal }
                            GraphNodeCard(
                                name = name,
                                isEntry = name in layout.roots,
                                blocker = blocker,
                                onClick = { onOpenFinding(finding ?: syntheticFinding(name)) },
                                modifier = Modifier.onGloballyPositioned { nodeRects[name] = it.boundsInRoot() }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GraphNodeCard(
    name: String,
    isEntry: Boolean,
    blocker: Finding?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val error = severityColor(Severity.ERROR)
    val ext = name.substringAfterLast('.', "").ifEmpty { "script" }
    Column(
        modifier = modifier
            .width(180.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (blocker != null) error.copy(alpha = 0.10f) else cardSurfaceColor())
            .border(
                width = if (blocker != null) 2.dp else 1.dp,
                color = if (blocker != null) error else outlineColor(),
                shape = RoundedCornerShape(6.dp)
            )
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = if (isEntry) "▶ ENTRY POINT" else ext.uppercase(),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = if (isEntry) graphAccent() else mutedTextColor()
        )
        if (blocker != null) {
            StatusBadge("⛔ Suspicious execution", error)
        }
        Text(
            text = name,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = if (blocker != null) error else JewelTheme.globalColors.text.normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        blocker?.let {
            Text(
                text = it.title,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = mutedTextColor(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DetachedChip(path: String, isBlocker: Boolean, onClick: () -> Unit) {
    val error = severityColor(Severity.ERROR)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(cardSurfaceColor())
            .border(
                width = if (isBlocker) 2.dp else 1.dp,
                color = if (isBlocker) error else outlineColor(),
                shape = RoundedCornerShape(6.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = path,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = if (isBlocker) error else JewelTheme.globalColors.text.normal
        )
    }
}

/* ── Graph layout ───────────────────────────────────────────────────────────── */

private data class EdgeLine(
    val from: String,
    val to: String,
    val via: String,
    val line: Int?,
    val isError: Boolean
)

private class GraphLayout(
    val nodes: List<String>,
    val roots: Set<String>,
    val layers: List<List<String>>,
    val edgeList: List<EdgeLine>
) {
    companion object {
        fun build(edges: List<ScriptEdge>, suspLines: Set<String>): GraphLayout {
            val children = HashMap<String, MutableList<String>>()
            val parents = HashMap<String, MutableList<String>>()
            val nodeSet = LinkedHashSet<String>()
            edges.forEach { e ->
                children.getOrPut(e.from.path) { mutableListOf() }.add(e.to.path)
                parents.getOrPut(e.to.path) { mutableListOf() }.add(e.from.path)
                nodeSet.add(e.from.path)
                nodeSet.add(e.to.path)
            }

            // Depth via BFS from roots.
            val depth = HashMap<String, Int>()
            val queue = ArrayDeque<String>()
            nodeSet.sorted().forEach { n ->
                if (parents[n].isNullOrEmpty()) {
                    depth[n] = 0
                    queue.add(n)
                }
            }
            while (queue.isNotEmpty()) {
                val n = queue.removeFirst()
                children[n]?.forEach { c ->
                    if (!depth.containsKey(c)) {
                        depth[c] = depth.getValue(n) + 1
                        queue.add(c)
                    }
                }
            }

            val order = depth.entries.groupBy({ it.value }, { it.key })
                .mapValues { (_, list) -> list.sorted().toMutableList() }

            fun avgParentPos(n: String): Double {
                val ps = parents[n].orEmpty()
                if (ps.isEmpty()) return 0.0
                var sum = 0.0
                var count = 0
                ps.forEach { p ->
                    val idx = order[depth[p]]?.indexOf(p) ?: -1
                    if (idx >= 0) {
                        sum += idx
                        count++
                    }
                }
                return if (count > 0) sum / count else 0.0
            }

            fun avgChildPos(n: String): Double {
                val cs = children[n].orEmpty()
                if (cs.isEmpty()) return 0.0
                var sum = 0.0
                var count = 0
                cs.forEach { c ->
                    val idx = order[depth[c]]?.indexOf(c) ?: -1
                    if (idx >= 0) {
                        sum += idx
                        count++
                    }
                }
                return if (count > 0) sum / count else 0.0
            }

            val layerCount = order.size
            for (i in 1 until layerCount) order[i]?.sortBy { avgParentPos(it) }
            for (i in layerCount - 2 downTo 0) order[i]?.sortBy { avgChildPos(it) }

            val layers = (0 until layerCount).mapNotNull { order[it] }
            val edgeLines = edges.map { e ->
                val key = e.to.path + ":" + (e.to.line ?: "")
                EdgeLine(
                    from = e.from.path,
                    to = e.to.path,
                    via = e.viaCommand,
                    line = e.from.line,
                    isError = key in suspLines
                )
            }
            return GraphLayout(
                nodes = nodeSet.toList(),
                roots = nodeSet.filter { parents[it].isNullOrEmpty() }.toSet(),
                layers = layers,
                edgeList = edgeLines
            )
        }
    }
}

/* ── Shared bits ────────────────────────────────────────────────────────────── */

/** Primary indigo accent (matches the tab bar). */
@Composable
private fun graphAccent(): Color =
    if (JewelTheme.isDark) Color(0xFFC3C0FF) else Color(0xFF4F46E5)

/** Synthetic finding used to open a file that has no findings. */
private fun syntheticFinding(path: String): Finding = Finding(
    fingerprint = "node-$path",
    ruleId = "WG-NODE",
    title = path,
    message = path,
    severity = Severity.INFO,
    confidence = Confidence.LOW,
    category = FindingCategory.AUDIT,
    kind = FindingKind.AUDIT_EVIDENCE,
    location = SourceLocation(path),
    policyAction = PolicyAction.ALLOW,
    status = FindingStatus.ACTIVE
)

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
