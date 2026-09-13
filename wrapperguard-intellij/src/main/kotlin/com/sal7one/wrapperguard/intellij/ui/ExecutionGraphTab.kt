package com.sal7one.wrapperguard.intellij.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.wrapperguard.core.model.*
import com.sal7one.wrapperguard.intellij.ui.components.*
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun ExecutionGraphTab(
    edges: List<ScriptEdge>, findings: List<Finding>,
    onOpenFinding: (Finding) -> Unit, onOpenFile: (Finding) -> Unit,
    modifier: Modifier = Modifier
) {
    val byFile = remember(findings) { findings.groupBy { it.location.path } }
    val layout = remember(edges) { ExecutionLayout.build(edges) }
    val graphAvailable = layout.layers.sumOf { it.size } <= 200
    val inspector = remember { BringIntoViewRequester() }
    var graph by remember { mutableStateOf(true) }
    var selectedPath by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(selectedPath) { if (selectedPath != null) inspector.bringIntoView() }
    val paths = remember(edges, findings) {
        (edges.flatMap { listOf(it.from.path, it.to.path) } + findings.map { it.location.path }).distinct().sorted()
    }
    WorkspacePage(modifier) {
        WorkspaceSection("Execution paths") {
            Text("${paths.size} files · ${edges.size} discovered calls", color = mutedTextColor(), fontSize = 12.sp)
            Text("Select a file to inspect its calls and findings. Arrows show statically discovered calls, not a recording of executed code.", fontSize = 12.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ChoiceChip("Graph", graph) { graph = true }
                ChoiceChip("File list", !graph) { graph = false }
            }
        }
        if (paths.isEmpty()) Text("No execution paths or finding locations were discovered.", color = mutedTextColor())
        if (graph && graphAvailable && edges.isNotEmpty()) {
            ExecutionCanvas(layout, edges, byFile, selectedPath, { selectedPath = it })
            Text("Scroll inside the graph to explore. Files with active blockers have a red border.", fontSize = 11.sp, color = mutedTextColor())
        }
        if (graph && !graphAvailable) Text("This graph has more than 200 files. Use the file list to inspect all paths without rendering a crowded canvas.", fontSize = 12.sp)
        if (!graph || !graphAvailable || edges.isEmpty()) WorkspaceSection("Files") {
            ExecutionFileList(paths, byFile, selectedPath) { selectedPath = it }
        } else {
            val linked = edges.flatMap { listOf(it.from.path, it.to.path) }.toSet()
            val detached = paths.filter { it !in linked }
            if (detached.isNotEmpty()) WorkspaceSection("Files outside discovered call chains") {
                ExecutionFileList(detached, byFile, selectedPath) { selectedPath = it }
            }
        }
        selectedPath?.takeIf { it in paths }?.let { path ->
            WorkspaceDivider()
            Box(Modifier.fillMaxWidth().bringIntoViewRequester(inspector)) {
            WorkspaceSection(path) {
                OutlinedButton(onClick = { onOpenFile(fileLocation(path)) }) { Text("Open file in editor") }
                val calls = edges.filter { it.from.path == path || it.to.path == path }
                calls.forEach { edge ->
                    Text("${edge.from.display} → ${edge.to.display}", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Text(edge.viaCommand, fontSize = 12.sp, color = mutedTextColor())
                }
                val fileFindings = byFile[path].orEmpty()
                if (fileFindings.isEmpty()) Text("No findings in this file. This does not establish that a called external file is safe.", fontSize = 12.sp)
                fileFindings.forEach { finding ->
                    Column(Modifier.fillMaxWidth().clickable { onOpenFinding(finding) }.padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(finding.title, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                        Text("${finding.ruleId} · ${finding.location.display} · ${finding.policyAction.name.lowercase()}",
                            color = severityColor(finding.severity), fontSize = 11.sp)
                        Text("Inspect finding →", fontSize = 11.sp, color = mutedTextColor())
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun ExecutionFileList(paths: List<String>, byFile: Map<String, List<Finding>>, selected: String?, onSelect: (String) -> Unit) {
    val scroll = rememberLazyListState()
    Box(Modifier.fillMaxWidth().heightIn(max = 300.dp).height((paths.size.coerceAtMost(6) * 52).dp)) {
        LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().padding(end = 12.dp)) {
            items(paths, key = { it }) { path -> FileEntry(path, byFile[path].orEmpty().size, path == selected) { onSelect(path) } }
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
private fun FileEntry(path: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(cardSurfaceColor()).clickable(onClick = onClick).padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (selected) "›" else "·")
        Text(path, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(count.toString(), color = mutedTextColor(), fontSize = 12.sp)
    }
}

@Composable
private fun ExecutionCanvas(layout: ExecutionLayout, edges: List<ScriptEdge>, byFile: Map<String, List<Finding>>,
    selected: String?, onSelect: (String) -> Unit) {
    val horizontal = rememberScrollState()
    val vertical = rememberScrollState()
    val border = outlineColor()
    val connection = mutedTextColor().copy(alpha = 0.60f)
    val error = severityColor(Severity.ERROR)
    val surface = cardSurfaceColor()
    val nodeWidth = 208.dp
    val nodeHeight = 92.dp
    val columnStep = 272.dp
    val rowStep = 124.dp
    val points = remember(layout) { buildMap { layout.layers.forEachIndexed { x, layer -> layer.forEachIndexed { y, path -> put(path, x to y) } } } }
    val blockers = remember(byFile) { byFile.filterValues { list -> list.any { it.status == FindingStatus.ACTIVE && it.policyAction == PolicyAction.BLOCK } }.keys }
    Box(Modifier.fillMaxWidth().height(350.dp).border(1.dp, border).background(surface)) {
        Box(Modifier.fillMaxSize().padding(end = 12.dp, bottom = 12.dp).horizontalScroll(horizontal).verticalScroll(vertical)) {
            Box(Modifier.size(columnStep * layout.layers.size + 32.dp,
                rowStep * (layout.layers.maxOfOrNull { it.size } ?: 1) + 32.dp)) {
                Canvas(Modifier.matchParentSize()) {
                    edges.forEach { edge ->
                        val from = points[edge.from.path] ?: return@forEach
                        val to = points[edge.to.path] ?: return@forEach
                        val a = Offset(16.dp.toPx() + from.first * columnStep.toPx() + nodeWidth.toPx(),
                            16.dp.toPx() + from.second * rowStep.toPx() + nodeHeight.toPx() / 2)
                        val b = Offset(16.dp.toPx() + to.first * columnStep.toPx(),
                            16.dp.toPx() + to.second * rowStep.toPx() + nodeHeight.toPx() / 2)
                        val color = if (edge.to.path in blockers) error else connection
                        val curve = Path().apply {
                            moveTo(a.x, a.y)
                            if (to.first > from.first) cubicTo(a.x + 32.dp.toPx(), a.y, b.x - 32.dp.toPx(), b.y, b.x, b.y)
                            else {
                                val above = minOf(a.y, b.y) - nodeHeight.toPx() / 2 - 10.dp.toPx()
                                cubicTo(a.x + 20.dp.toPx(), above, b.x - 20.dp.toPx(), above, b.x, b.y)
                            }
                        }
                        drawPath(curve, color, style = Stroke(1.5.dp.toPx()))
                        drawLine(color, b - Offset(6.dp.toPx(), 4.dp.toPx()), b, 1.5.dp.toPx())
                        drawLine(color, b - Offset(6.dp.toPx(), -4.dp.toPx()), b, 1.5.dp.toPx())
                    }
                }
                points.forEach { (path, position) ->
                    Column(Modifier.offset(16.dp + columnStep * position.first, 16.dp + rowStep * position.second)
                        .size(nodeWidth, nodeHeight).background(surface, RoundedCornerShape(4.dp))
                        .border(if (path == selected) 2.dp else 1.dp, if (path in blockers) error else if (path == selected) connection else border, RoundedCornerShape(4.dp))
                        .clickable { onSelect(path) }.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (path in layout.roots) "ENTRY" else "FILE", fontSize = 9.sp, color = mutedTextColor())
                        Text(path, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${byFile[path].orEmpty().size} findings", fontSize = 10.sp, color = if (path in blockers) error else mutedTextColor())
                    }
                }
            }
        }
        HorizontalScrollbar(rememberScrollbarAdapter(horizontal), Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(end = 12.dp))
        VerticalScrollbar(rememberScrollbarAdapter(vertical), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(bottom = 12.dp))
    }
}

private fun fileLocation(path: String) = Finding("node-$path", "WG-NODE", path, "", Severity.INFO,
    Confidence.LOW, FindingCategory.AUDIT, FindingKind.AUDIT_EVIDENCE, SourceLocation(path), policyAction = PolicyAction.ALLOW)
