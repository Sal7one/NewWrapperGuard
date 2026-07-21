package com.sal7one.wrapperguard.rules

import com.sal7one.wrapperguard.core.spi.TextSource

/**
 * A reference from one script to another, plus the raw line and line number that produced it.
 */
data class CalledScript(val rawLine: String, val path: String, val lineNumber: Int)

/**
 * Discovers script-to-script references (edges) in shell/batch/PowerShell files.
 * Ported from old_project's CalledScriptsResolver, but operates on [TextSource].
 *
 * The scanner uses this to drive recursive analysis. It only produces candidate edges —
 * [SafePathResolver] decides whether each is safe to follow.
 */
object CalledScriptsResolver {

    private val UNIX_PATTERNS = listOf(
        Regex("""^\s*(source|\.)\s+"(?<path>[^"]+\.sh)""""),
        Regex("""^\s*(source|\.)\s+'(?<path>[^']+\.sh)'"""),
        Regex("""^\s*(source|\.)\s+(["']?)(?<path>[^"'\s;|&]+)\2"""),
        Regex("""^\s*(bash|sh|env\s+(?:-S\s+)?bash|env\s+sh)\s+"(?<path>[^"]+\.sh)""""),
        Regex("""^\s*(bash|sh|env\s+(?:-S\s+)?bash|env\s+sh)\s+'(?<path>[^']+\.sh)'"""),
        Regex("""^\s*(bash|sh|env\s+(?:-S\s+)?bash|env\s+sh)\s+(["']?)(?<path>[^"'\s;|&]+)\2"""),
        Regex("""^\s*(?<path>\./[^\s;|&]+\.sh|[^\s;|&/]+\.sh)\s*(?:[;&|]|$)""")
    )

    private val WINDOWS_PATTERNS = listOf(
        Regex("""^\s*CALL\s+"(?<path>[^"]+\.(?:bat|cmd))"""", RegexOption.IGNORE_CASE),
        Regex("""^\s*CALL\s+(?<path>[^"'\s]+\.(?:bat|cmd))""", RegexOption.IGNORE_CASE),
        Regex("""^\s*"(?<path>[^"]+\.(?:bat|cmd))"""", RegexOption.IGNORE_CASE),
        Regex("""^\s*(?<path>[^"'\s]+\.(?:bat|cmd))(?:\s|$)""", RegexOption.IGNORE_CASE),
        Regex("""powershell(?:\.exe)?\s+.*-File\s+"(?<path>[^"]+\.ps1)"""", RegexOption.IGNORE_CASE),
        Regex("""powershell(?:\.exe)?\s+.*-File\s+(?<path>[^"'\s]+\.ps1)""", RegexOption.IGNORE_CASE)
    )

    private val PS1_PATTERNS = listOf(
        Regex("""^\s*\.\s+(["']?)(?<path>[^"'\s]+\.ps1)\1""", RegexOption.IGNORE_CASE)
    )

    fun findCalledScripts(source: TextSource): List<CalledScript> {
        val fileName = source.path.fileName.toString()
        return when {
            fileName == "gradlew" || fileName.endsWith(".sh") -> parse(source.content, UNIX_PATTERNS)
            fileName == "gradlew.bat" || fileName.endsWith(".bat") || fileName.endsWith(".cmd") ->
                parse(source.content, WINDOWS_PATTERNS)
            fileName.endsWith(".ps1") -> parse(source.content, PS1_PATTERNS)
            else -> emptyList()
        }
    }

    private fun parse(content: String, patterns: List<Regex>): List<CalledScript> {
        val out = mutableListOf<CalledScript>()
        val seen = mutableSetOf<String>()
        content.lines().forEachIndexed { idx, line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("REM", ignoreCase = true) || trimmed.startsWith("::")) return@forEachIndexed
            patterns.forEach { pattern ->
                pattern.find(line)?.groups?.get("path")?.value?.let { path ->
                    if (seen.add(path)) out += CalledScript(trimmed, path, idx + 1)
                }
            }
        }
        return out
    }
}
