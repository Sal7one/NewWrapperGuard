package com.sal7one.wrapperguard.intellij.execution

import com.sal7one.wrapperguard.core.policy.ScanSettings
import com.sal7one.wrapperguard.intellij.WrapperGuardProjectService
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/**
 * Bounded streaming hashes for the same build, source and dependency predicate used
 * by VFS staleness tracking. Existing directory/depth/file/byte limits apply to the
 * expanded inputs; exceeding them fails the seal instead of returning partial coverage.
 * This does not seal arbitrary inputs of a third-party SDK or excluded directory trees.
 */
internal fun watchedFileHashes(root: Path, settings: ScanSettings, checkCancelled: () -> Unit = {}): Map<String, String> {
    val canonicalRoot = root.toRealPath()
    if (!Files.isDirectory(canonicalRoot)) throw IOException("Execution seal requires a project directory.")
    val hashes = linkedMapOf<String, String>()
    var visitedEntries = 0L
    // Also bound trees containing only irrelevant files/directories; never silently truncate a seal.
    val maximumEntries = settings.maxFilesPerScan.toLong().coerceAtLeast(1) * 10
    fun visit(path: Path) {
        checkCancelled()
        visitedEntries++
        val depth = if (path == canonicalRoot) 0 else canonicalRoot.relativize(path).nameCount
        if (visitedEntries > maximumEntries || depth > settings.maxDirectoryDepth) {
            throw IOException("Execution seal traversal limit reached.")
        }
    }
    Files.walkFileTree(canonicalRoot, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
            checkCancelled()
            if (directory != canonicalRoot && directory.fileName.toString() in SEAL_EXCLUDED_DIRECTORIES) {
                return FileVisitResult.SKIP_SUBTREE
            }
            visit(directory)
            return FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
            visit(file)
            if (!WrapperGuardProjectService.isWatchedFileName(file.fileName.toString())) return FileVisitResult.CONTINUE
            if (hashes.size >= settings.maxFilesPerScan) throw IOException("Execution seal file limit reached.")
            val target = file.toRealPath()
            if (!target.startsWith(canonicalRoot)) throw IOException("Execution seal input leaves the project.")
            val targetAttributes = Files.readAttributes(target, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (!targetAttributes.isRegularFile || targetAttributes.size() > settings.maxFileBytes) {
                throw IOException("Execution seal input is not a regular file within the size limit.")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            // Include a contained symlink's destination, as well as its bytes, in the seal.
            digest.update(canonicalRoot.relativize(target).toString().replace('\\', '/').toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS).use { input ->
                val buffer = ByteArray(8_192)
                var totalBytes = 0L
                while (true) {
                    checkCancelled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    totalBytes += count
                    if (totalBytes > settings.maxFileBytes) throw IOException("Execution seal input exceeds the size limit.")
                    digest.update(buffer, 0, count)
                }
            }
            hashes[canonicalRoot.relativize(file).toString().replace('\\', '/')] =
                digest.digest().joinToString("") { "%02x".format(it) }
            return FileVisitResult.CONTINUE
        }

        override fun visitFileFailed(file: Path, error: IOException): FileVisitResult = throw error

        override fun postVisitDirectory(directory: Path, error: IOException?): FileVisitResult {
            if (error != null) throw error
            return FileVisitResult.CONTINUE
        }
    })
    return hashes
}

private val SEAL_EXCLUDED_DIRECTORIES = setOf(
    ".git", ".gradle", ".idea", "build", "out", ".intellijPlatform", ".kotlin", "node_modules"
)
