package com.sal7one.wrapperguard.rules

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*

/**
 * Resolves script references safely, ported from old_project's SafePathResolver but on pure
 * [java.nio.file.Path] (no IntelliJ VFS). Pipeline:
 *  1. Expand shell variables ($0, ${BASH_SOURCE}, $WRAPPER_DIR, %~dp0, %CD%, $(dirname $0)).
 *  2. Normalize, rejecting anything that escapes the project root (path-traversal guard).
 *  3. Require the file to exist, be under the project root, be non-oversize, and be text.
 *
 * Returns the resolved absolute [Path], or null if the reference is invalid/unsafe.
 */
object SafePathResolver {

    fun resolve(
        projectRoot: Path,
        referrer: Path,
        rawPath: String,
        maxFileBytes: Long
    ): Path? = runCatching {
        val expanded = expandVariables(rawPath, referrer)
        val resolved = normalizePath(projectRoot, expanded, referrer) ?: return@runCatching null
        if (!isUnderProjectRoot(projectRoot, resolved)) return@runCatching null
        if (ProjectFileAccess.status(projectRoot, resolved) != ProjectFileAccess.Status.REGULAR) return@runCatching null
        if (ProjectFileAccess.read(projectRoot, resolved, maxFileBytes).any { it == 0.toByte() }) return@runCatching null
        resolved
    }.getOrNull()

    private fun expandVariables(raw: String, referrer: Path): String {
        var expanded = raw.trim().trim('"', '\'', '`')
        val refDir = referrer.parent ?: Path.of("")

        val unix = mapOf(
            "\$0" to referrer.toString(),
            "\${BASH_SOURCE[0]}" to referrer.toString(),
            "\${BASH_SOURCE}" to referrer.toString(),
            "\$WRAPPER_DIR" to refDir.toString(),
            "\$BASEDIR" to refDir.toString()
        )
        unix.forEach { (k, v) -> expanded = expanded.replace(k, v) }

        val win = mapOf("%~dp0" to (refDir.toString() + "\\"), "%CD%" to refDir.toString())
        win.forEach { (k, v) -> expanded = expanded.replace(k, v, ignoreCase = true) }

        expanded = Regex("""\$\((?:cd\s+)?["']?dirname["']?\s+["']?\$0["']?\)""").replace(expanded, refDir.toString())
        return expanded.trim().trim('"', '\'')
    }

    private fun normalizePath(projectRoot: Path, expanded: String, referrer: Path): Path? = runCatching {
        val cleaned = expanded.replace("\\", "/")
        val path = Path.of(cleaned)
        val resolved = if (path.isAbsolute) path.normalize() else referrer.parent?.resolve(cleaned)?.normalize() ?: return null
        if (!resolved.startsWith(projectRoot.normalize())) return null
        resolved
    }.getOrNull()

    private fun isUnderProjectRoot(projectRoot: Path, file: Path): Boolean =
        file.normalize().startsWith(projectRoot.normalize())

}
