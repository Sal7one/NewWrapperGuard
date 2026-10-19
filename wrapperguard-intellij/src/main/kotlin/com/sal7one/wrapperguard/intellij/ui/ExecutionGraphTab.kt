package com.sal7one.wrapperguard.intellij.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.wrapperguard.core.model.*
import com.sal7one.wrapperguard.intellij.ui.components.*
import org.jetbrains.jewel.ui.component.Text

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExecutionGraphTab(
    edges: List<ScriptEdge>, findings: List<Finding>,
    onOpenFinding: (Finding) -> Unit, onOpenFile: (Finding) -> Unit,
    modifier: Modifier = Modifier
) {
    val byFile = remember(findings) { findings.groupBy { it.location.path } }
    val layout = remember(edges) { ExecutionLayout.build(edges) }
    val graphFiles = remember(edges) { edges.flatMap { listOf(it.from.path, it.to.path) }.toSet() }
    val paths = remember(edges, findings) { (graphFiles + byFile.keys).sorted() }
    val riskyPaths = remember(edges, findings) { executionRiskPaths(edges, findings) }
    val graphAvailable = graphFiles.size <= 200
    var graph by remember { mutableStateOf(true) }
    var selectedPath by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(paths, riskyPaths) {
        if (selectedPath !in paths) selectedPath = riskyPaths.firstOrNull { it.inGraph }?.target
            ?: layout.roots.firstOrNull() ?: paths.firstOrNull()
    }

    Column(modifier.fillMaxSize().background(workspaceBackground()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Execution graph", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("Discovered calls and the findings along them", color = mutedTextColor(), fontSize = 12.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ChoiceChip("Graph", graph) { graph = true }
                ChoiceChip("Files · ${paths.size}", !graph) { graph = false }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val canvas: @Composable (Modifier) -> Unit = { pane ->
                if (graph && graphAvailable && edges.isNotEmpty()) {
                    ExecutionCanvas(layout, edges, byFile, selectedPath, { selectedPath = it }, pane)
                } else {
                    ExecutionFiles(paths, byFile, selectedPath, { selectedPath = it }, pane,
                        if (graph && !graphAvailable) "More than 200 graph files. All files remain available here." else null)
                }
            }
            val inspector: @Composable (Modifier) -> Unit = { pane ->
                ExecutionInspector(selectedPath, edges, byFile, riskyPaths, paths.filter { it !in graphFiles },
                    { selectedPath = it }, onOpenFinding, onOpenFile, pane)
            }
            if (maxWidth >= 820.dp) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    canvas(Modifier.weight(1f).fillMaxHeight())
                    inspector(Modifier.width(292.dp).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    canvas(Modifier.fillMaxWidth().weight(0.58f))
                    inspector(Modifier.fillMaxWidth().weight(0.42f))
                }
            }
        }
        Text("Static call discovery · arrows are references in source, not a recording of executed code.",
            fontSize = 10.sp, color = mutedTextColor())
    }
}

@Composable
private fun ExecutionFiles(paths: List<String>, byFile: Map<String, List<Finding>>, selected: String?,
    onSelect: (String) -> Unit, modifier: Modifier, note: String?) {
    val scroll = rememberLazyListState()
    Column(modifier.clip(RoundedCornerShape(6.dp)).background(cardSurfaceColor())
        .border(1.dp, outlineColor(), RoundedCornerShape(6.dp))) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("PROJECT FILES", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = mutedTextColor())
            Text("${paths.size} files", fontSize = 11.sp, color = mutedTextColor())
        }
        WorkspaceDivider()
        if (note != null) Text(note, Modifier.padding(12.dp), fontSize = 11.sp, color = warningColor())
        if (paths.isEmpty()) EmptyHint("No discovered calls or finding locations yet.")
        Box(Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().padding(end = 10.dp)) {
                items(paths, key = { it }) { path ->
                    FileEntry(path, byFile[path].orEmpty(), path == selected) { onSelect(path) }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

@Composable
private fun FileEntry(path: String, findings: List<Finding>, selected: Boolean, onClick: () -> Unit) {
    val active = findings.count { it.status == FindingStatus.ACTIVE }
    val blocked = findings.any { it.status == FindingStatus.ACTIVE && it.policyAction == PolicyAction.BLOCK }
    Row(Modifier.fillMaxWidth().background(if (selected) accentColor().copy(alpha = 0.10f) else Color.Transparent)
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(5.dp).background(if (blocked) dangerColor() else outlineColor(), RoundedCornerShape(50)))
        Text(path, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(active.toString(), color = if (blocked) dangerColor() else mutedTextColor(), fontSize = 11.sp)
    }
    WorkspaceDivider()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExecutionCanvas(layout: ExecutionLayout, edges: List<ScriptEdge>, byFile: Map<String, List<Finding>>,
    selected: String?, onSelect: (String) -> Unit, modifier: Modifier) {
    val horizontal = rememberScrollState()
    val vertical = rememberScrollState()
    var manualZoom by remember { mutableStateOf<Float?>(null) }
    var fittedZoom by remember { mutableStateOf(1f) }
    val border = outlineColor()
    val muted = mutedTextColor()
    val connection = muted.copy(alpha = 0.75f)
    val error = dangerColor()
    val accent = accentColor()
    val surface = cardSurfaceColor()
    val canvasSurface = insetSurfaceColor()
    val points = remember(layout) {
        val columns = layout.layers.maxOfOrNull { it.size } ?: 1
        buildMap {
            layout.layers.forEachIndexed { depth, layer ->
                layer.forEachIndexed { column, path -> put(path, (column + (columns - layer.size) / 2f) to depth) }
            }
        }
    }
    val blockers = remember(byFile) { byFile.filterValues { list ->
        list.any { it.status == FindingStatus.ACTIVE && it.policyAction == PolicyAction.BLOCK }
    }.keys }
    Column(modifier.clip(RoundedCornerShape(6.dp)).background(canvasSurface)
        .border(1.dp, border, RoundedCornerShape(6.dp))) {
        Row(Modifier.fillMaxWidth().background(surface).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            GraphAction("−", "Zoom out") { manualZoom = ((manualZoom ?: fittedZoom) - 0.15f).coerceAtLeast(0.50f) }
            Text(if (manualZoom == null) "AUTO" else "${(manualZoom!! * 100).toInt()}%", fontSize = 10.sp,
                fontFamily = FontFamily.Monospace, color = muted, modifier = Modifier.width(36.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            GraphAction("+", "Zoom in") { manualZoom = ((manualZoom ?: fittedZoom) + 0.15f).coerceAtMost(1.65f) }
            GraphAction("Fit", "Fit graph to view") { manualZoom = null }
            Spacer(Modifier.weight(1f))
            Text("${points.size} files  /  ${edges.size} calls", color = muted, fontSize = 10.sp,
                fontFamily = FontFamily.Monospace)
        }
        WorkspaceDivider()
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val columns = layout.layers.maxOfOrNull { it.size } ?: 1
            val naturalWidth = (columns - 1) * 238f + 200f + 72f
            val naturalHeight = (layout.layers.size - 1) * 138f + 86f + 72f
            val fit = minOf((maxWidth.value - 18f) / naturalWidth, (maxHeight.value - 18f) / naturalHeight)
                .coerceIn(0.50f, 1f)
            LaunchedEffect(fit) { fittedZoom = fit }
            val scale = manualZoom ?: fit
            val nodeWidth = (200f * scale).dp
            val nodeHeight = (86f * scale).dp
            val columnStep = (238f * scale).dp
            val rowStep = (138f * scale).dp
            val padding = (36f * scale).dp
            val width = (naturalWidth * scale).dp
            val height = (naturalHeight * scale).dp
            val viewportWidth = maxWidth
            val viewportHeight = maxHeight
            Box(Modifier.fillMaxSize().padding(end = 10.dp, bottom = 10.dp)
                .horizontalScroll(horizontal).verticalScroll(vertical)) {
                Box(Modifier.width(maxOf(width, viewportWidth - 10.dp)).height(maxOf(height, viewportHeight - 10.dp)),
                    contentAlignment = Alignment.Center) {
                    Box(Modifier.size(width, height)) {
                        Canvas(Modifier.matchParentSize()) {
                            edges.forEachIndexed { index, edge ->
                                val from = points[edge.from.path] ?: return@forEachIndexed
                                val to = points[edge.to.path] ?: return@forEachIndexed
                                val a = Offset(padding.toPx() + from.first * columnStep.toPx() + nodeWidth.toPx() / 2,
                                    padding.toPx() + from.second * rowStep.toPx() + nodeHeight.toPx())
                                val b = Offset(padding.toPx() + to.first * columnStep.toPx() + nodeWidth.toPx() / 2,
                                    padding.toPx() + to.second * rowStep.toPx())
                                val color = when {
                                    edge.from.path in blockers || edge.to.path in blockers -> error.copy(alpha = 0.80f)
                                    edge.from.path == selected || edge.to.path == selected -> accent.copy(alpha = 0.90f)
                                    else -> connection
                                }
                                val curve = Path().apply {
                                    moveTo(a.x, a.y)
                                    if (to.second > from.second) {
                                        val middle = (a.y + b.y) / 2
                                        cubicTo(a.x, middle, b.x, middle, b.x, b.y)
                                    } else {
                                        // Return calls and self calls take a side lane instead of crossing a node.
                                        val side = maxOf(a.x, b.x) + nodeWidth.toPx() / 2 + (18 + index % 3 * 6).dp.toPx() * scale
                                        cubicTo(side, a.y + 20.dp.toPx() * scale, side, b.y - 32.dp.toPx() * scale,
                                            b.x + 14.dp.toPx() * scale, b.y - 24.dp.toPx() * scale)
                                        quadraticBezierTo(b.x, b.y - 24.dp.toPx() * scale, b.x, b.y)
                                    }
                                }
                                drawPath(curve, color, style = Stroke(1.4.dp.toPx()))
                                val arrow = Path().apply {
                                    moveTo(b.x, b.y)
                                    lineTo(b.x - 3.5.dp.toPx(), b.y - 6.dp.toPx())
                                    lineTo(b.x + 3.5.dp.toPx(), b.y - 6.dp.toPx())
                                    close()
                                }
                                drawPath(arrow, color)
                            }
                        }
                        points.forEach { (path, position) ->
                            val fileFindings = byFile[path].orEmpty()
                            val active = fileFindings.count { it.status == FindingStatus.ACTIVE }
                            val blocked = path in blockers
                            val tone = if (blocked) error else if (path == selected) accent else muted
                            Column(Modifier.offset(padding + columnStep * position.first, padding + rowStep * position.second)
                                .size(nodeWidth, nodeHeight).clip(RoundedCornerShape(4.dp))
                                .background(if (blocked) error.copy(alpha = 0.08f) else surface)
                                .border(if (path == selected) 2.dp else 1.dp,
                                    if (blocked || path == selected) tone else border, RoundedCornerShape(4.dp))
                                .clickable(role = Role.Button) { onSelect(path) }.padding((10f * scale).dp),
                                verticalArrangement = Arrangement.spacedBy((5f * scale).dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (path in layout.roots) "ENTRY POINT" else "SCRIPT", fontSize = (9f * scale).sp,
                                        fontWeight = FontWeight.SemiBold, color = if (path in layout.roots) accent else muted)
                                    Spacer(Modifier.weight(1f))
                                    if (blocked) Text("BLOCKED", fontSize = (8f * scale).sp, color = error, fontWeight = FontWeight.Bold)
                                }
                                Text(path.substringAfterLast('/'), fontFamily = FontFamily.Monospace,
                                    fontSize = (12f * scale).sp, fontWeight = FontWeight.Medium,
                                    color = if (blocked) error else Color.Unspecified, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(path.substringBeforeLast('/', "project root"), fontFamily = FontFamily.Monospace,
                                        fontSize = (9f * scale).sp, color = muted, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    Text("$active active", fontSize = (9f * scale).sp, color = tone)
                                }
                            }
                        }
                    }
                }
            }
            HorizontalScrollbar(rememberScrollbarAdapter(horizontal), Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(end = 10.dp))
            VerticalScrollbar(rememberScrollbarAdapter(vertical), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(bottom = 10.dp))
        }
        WorkspaceDivider()
        FlowRow(Modifier.fillMaxWidth().background(surface).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            GraphLegend("Entry point", accent)
            GraphLegend("Active blocker", error)
            GraphLegend("Discovered call", muted)
        }
    }
}

@Composable
private fun GraphLegend(label: String, tone: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(5.dp).background(tone, RoundedCornerShape(50)))
        Text(label, color = mutedTextColor(), fontSize = 10.sp)
    }
}

@Composable
private fun GraphAction(label: String, description: String = label, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(4.dp)).background(raisedSurfaceColor())
        .border(1.dp, outlineColor(), RoundedCornerShape(4.dp))
        .semantics { contentDescription = description }
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 9.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center) {
        Text(label, fontSize = 11.sp, color = mutedTextColor())
    }
}

@Composable
private fun ExecutionInspector(path: String?, edges: List<ScriptEdge>, byFile: Map<String, List<Finding>>,
    riskyPaths: List<ExecutionRiskPath>, detached: List<String>, onSelect: (String) -> Unit,
    onOpenFinding: (Finding) -> Unit, onOpenFile: (Finding) -> Unit, modifier: Modifier) {
    val scroll = rememberScrollState()
    Column(modifier.clip(RoundedCornerShape(6.dp)).background(cardSurfaceColor())
        .border(1.dp, outlineColor(), RoundedCornerShape(6.dp))) {
        Row(Modifier.fillMaxWidth().background(raisedSurfaceColor()).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Path inspector", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("${riskyPaths.size} blocked files", color = if (riskyPaths.isEmpty()) mutedTextColor() else dangerColor(), fontSize = 10.sp)
        }
        WorkspaceDivider()
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(12.dp).padding(end = 6.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                InspectorLabel("PATHS TO ACTIVE BLOCKERS")
                if (riskyPaths.isEmpty()) Text("No active blocking findings.", fontSize = 11.sp, color = mutedTextColor())
                riskyPaths.forEach { risk ->
                    val selected = risk.target == path
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                        .background(if (selected) dangerColor().copy(alpha = 0.07f) else raisedSurfaceColor())
                        .border(1.dp, if (selected) dangerColor().copy(alpha = 0.55f) else outlineColor(), RoundedCornerShape(4.dp))
                        .clickable(role = Role.Button) { onSelect(risk.target) }.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(risk.target.substringAfterLast('/'), fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium, color = dangerColor(), fontSize = 11.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(when {
                            !risk.inGraph -> "Outside discovered call graph"
                            !risk.hasEntry -> "No discovered entry path"
                            risk.files.size == 1 -> "Entry file has an active blocker"
                            else -> risk.files.joinToString(" → ") { it.substringAfterLast('/') }
                        }, fontSize = 10.sp, color = mutedTextColor(), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
                WorkspaceDivider()
                InspectorLabel("SELECTED FILE")
                if (path == null) Text("Select a node to inspect its calls and findings.", fontSize = 11.sp, color = mutedTextColor())
                else {
                    Text(path, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    val incoming = edges.filter { it.to.path == path }
                    val outgoing = edges.filter { it.from.path == path }
                    Text("${incoming.size} incoming  ·  ${outgoing.size} outgoing", fontSize = 10.sp, color = mutedTextColor())
                    Row { GraphAction("Open in editor") { onOpenFile(fileLocation(path)) } }
                    val fileFindings = byFile[path].orEmpty()
                    if (fileFindings.isEmpty()) Text("No findings in this file. External calls may need separate review.",
                        fontSize = 11.sp, color = mutedTextColor())
                    fileFindings.forEach { finding ->
                        val active = finding.status == FindingStatus.ACTIVE
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(insetSurfaceColor())
                            .border(1.dp, outlineColor(), RoundedCornerShape(4.dp))
                            .clickable(role = Role.Button) { onOpenFinding(finding) }.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(finding.title, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            Text("${finding.ruleId} · ${finding.location.line?.let { "line $it" } ?: "file"}",
                                color = mutedTextColor(), fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                            Text(if (active) "${finding.policyAction.name} · Inspect finding →" else "${finding.status.name} · Inspect finding →",
                                fontSize = 10.sp, color = if (active) policyActionColor(finding.policyAction) else mutedTextColor())
                        }
                    }
                    if (incoming.isNotEmpty()) {
                        InspectorLabel("INCOMING CALLS")
                        incoming.forEach { edge -> CallEntry(edge.from.display, edge.viaCommand) { onSelect(edge.from.path) } }
                    }
                    if (outgoing.isNotEmpty()) {
                        InspectorLabel("OUTGOING CALLS")
                        outgoing.forEach { edge -> CallEntry(edge.to.display, "${edge.from.line?.let { "line $it · " } ?: ""}${edge.viaCommand}") { onSelect(edge.to.path) } }
                    }
                }
                if (detached.isNotEmpty()) {
                    WorkspaceDivider()
                    InspectorLabel("OUTSIDE DISCOVERED CALL GRAPH")
                    detached.forEach { file ->
                        Text(file, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                            color = if (file == path) accentColor() else mutedTextColor(),
                            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { onSelect(file) }.padding(vertical = 3.dp))
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

@Composable
private fun InspectorLabel(text: String) {
    Text(text, color = mutedTextColor(), fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun CallEntry(path: String, command: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("↳ $path", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = accentColor())
        Text(command, fontSize = 10.sp, color = mutedTextColor(), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private fun fileLocation(path: String) = Finding("node-$path", "WG-NODE", path, "", Severity.INFO,
    Confidence.LOW, FindingCategory.AUDIT, FindingKind.AUDIT_EVIDENCE, SourceLocation(path), policyAction = PolicyAction.ALLOW)
