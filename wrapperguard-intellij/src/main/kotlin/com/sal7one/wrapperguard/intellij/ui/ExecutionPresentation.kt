package com.sal7one.wrapperguard.intellij.ui

import com.sal7one.wrapperguard.core.model.Finding
import com.sal7one.wrapperguard.core.model.FindingStatus
import com.sal7one.wrapperguard.core.model.PolicyAction
import com.sal7one.wrapperguard.core.model.ScriptEdge

internal data class ExecutionLayout(val layers: List<List<String>>, val roots: Set<String>) {
    companion object {
        fun build(edges: List<ScriptEdge>): ExecutionLayout {
            val nodes = edges.flatMap { listOf(it.from.path, it.to.path) }.distinct().sorted()
            val children = edges.groupBy { it.from.path }.mapValues { (_, links) -> links.map { it.to.path }.distinct().sorted() }
            val targets = edges.map { it.to.path }.toSet()
            val roots = nodes.filter { it !in targets }.toSet()
            val depth = mutableMapOf<String, Int>()
            fun visit(seeds: Collection<String>) {
                val queue = ArrayDeque<String>()
                seeds.forEach { if (it !in depth) { depth[it] = 0; queue.add(it) } }
                while (queue.isNotEmpty()) {
                    val node = queue.removeFirst()
                    children[node].orEmpty().forEach { child ->
                        if (child !in depth) { depth[child] = depth.getValue(node) + 1; queue.add(child) }
                    }
                }
            }
            visit(roots)
            // A cycle can have no root. Keep every disconnected component visible.
            nodes.forEach { if (it !in depth) visit(listOf(it)) }
            return ExecutionLayout(depth.entries.groupBy({ it.value }, { it.key }).toSortedMap()
                .values.map { it.sorted() }, roots)
        }
    }
}

internal data class ExecutionRiskPath(
    val target: String,
    val files: List<String>,
    val hasEntry: Boolean,
    val inGraph: Boolean
)

/** One shortest discovered entry-to-blocker path per file; approval history cannot create a risky path. */
internal fun executionRiskPaths(edges: List<ScriptEdge>, findings: List<Finding>): List<ExecutionRiskPath> {
    val graphFiles = edges.flatMap { listOf(it.from.path, it.to.path) }.toSet()
    val targets = edges.map { it.to.path }.toSet()
    val roots = graphFiles - targets
    val parents = edges.groupBy { it.to.path }.mapValues { (_, links) -> links.map { it.from.path }.distinct().sorted() }
    return findings.asSequence()
        .filter { it.status == FindingStatus.ACTIVE && it.policyAction == PolicyAction.BLOCK }
        .map { it.location.path }.distinct().sorted().map { target ->
            val queue = ArrayDeque<String>()
            val visited = mutableSetOf(target)
            val towardTarget = mutableMapOf<String, String>()
            queue.add(target)
            var entry: String? = null
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                if (current in roots) { entry = current; break }
                parents[current].orEmpty().forEach { parent ->
                    if (visited.add(parent)) { towardTarget[parent] = current; queue.add(parent) }
                }
            }
            val path = mutableListOf<String>()
            var current = entry ?: target
            path.add(current)
            while (current != target) {
                current = towardTarget[current] ?: break
                path.add(current)
            }
            ExecutionRiskPath(target, path, entry != null, target in graphFiles)
        }.toList()
}
