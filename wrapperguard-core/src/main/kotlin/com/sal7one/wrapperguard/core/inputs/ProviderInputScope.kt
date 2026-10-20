package com.sal7one.wrapperguard.core.inputs

import kotlinx.serialization.Serializable
import java.nio.file.Path

/** An explicit user declaration, not a filesystem or execution permission. Null means unknown. */
@Serializable
data class ProviderInputScope(
    val includes: List<String> = emptyList(),
    val excludes: List<String> = emptyList(),
    val externalFiles: List<String> = emptyList()
) {
    fun validate() {
        require(includes.size <= 128 && excludes.size <= 128 && externalFiles.size <= 128) { "Too many declared provider inputs." }
        require(includes.distinct().size == includes.size && excludes.distinct().size == excludes.size &&
            externalFiles.distinct().size == externalFiles.size) { "Declared input lists must be unique." }
        (includes + excludes).forEach { PortableGlob.validate(it) }
        externalFiles.forEach {
            require(it.isNotBlank() && it.length <= 4096 && it.none(Char::isISOControl) && Path.of(it).isAbsolute) {
                "External provider inputs must use absolute file paths."
            }
        }
    }

    fun snapshot(): ProviderInputScope = copy(includes = includes.toList(), excludes = excludes.toList(), externalFiles = externalFiles.toList())
}

/** Portable segment globs: *, ?, and whole-segment **; no platform-specific glob extensions. */
internal class PortableGlob(pattern: String) {
    private val parts = pattern.split('/').also { validate(pattern) }

    fun matches(path: String): Boolean = consume(path.split('/').filter(String::isNotEmpty), prefix = false)
    fun canMatchBelow(directory: String): Boolean = consume(directory.split('/').filter(String::isNotEmpty), prefix = true)
    fun excludes(path: String): Boolean = path.split('/').indices.any { index -> matches(path.split('/').take(index + 1).joinToString("/")) }

    private fun consume(path: List<String>, prefix: Boolean): Boolean {
        val memo = mutableMapOf<Pair<Int, Int>, Boolean>()
        fun visit(patternIndex: Int, pathIndex: Int): Boolean = memo.getOrPut(patternIndex to pathIndex) {
            when {
                pathIndex == path.size && prefix -> patternIndex < parts.size
                patternIndex == parts.size -> pathIndex == path.size
                parts[patternIndex] == "**" -> visit(patternIndex + 1, pathIndex) ||
                    (pathIndex < path.size && visit(patternIndex, pathIndex + 1))
                pathIndex == path.size -> false
                segmentMatches(parts[patternIndex], path[pathIndex]) -> visit(patternIndex + 1, pathIndex + 1)
                else -> false
            }
        }
        return visit(0, 0)
    }

    // Dynamic programming keeps repeated stars bounded instead of regex backtracking.
    private fun segmentMatches(pattern: String, value: String): Boolean {
        var state = BooleanArray(value.length + 1).also { it[0] = true }
        for (character in pattern) {
            val next = BooleanArray(value.length + 1)
            if (character == '*') next[0] = state[0]
            for (index in value.indices) next[index + 1] = when (character) {
                '*' -> state[index + 1] || next[index]
                '?' -> state[index]
                else -> state[index] && value[index] == character
            }
            state = next
        }
        return state[value.length]
    }

    companion object {
        fun validate(pattern: String) {
            require(pattern.isNotBlank() && pattern.length <= 512 && !pattern.startsWith('/') &&
                pattern.none { it.isISOControl() || it in "\\[]{}!:" }) { "Invalid portable input glob." }
            require(pattern.split('/').all { it.isNotEmpty() && it != "." && it != ".." && ("**" !in it || it == "**") }) {
                "Input globs must be relative paths with whole-segment double stars."
            }
        }
    }
}
