package com.sal7one.wrapperguard.intellij.ui

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
