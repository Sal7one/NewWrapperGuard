package com.sal7one.wrapperguard.core.inputs

import java.nio.channels.Channels
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.TimeUnit

data class InputSealLimits(
    val maxFiles: Int = 5_000,
    val maxFileBytes: Long = 1_048_576,
    val maxExternalFileBytes: Long = 16_777_216,
    val maxTotalBytes: Long = 67_108_864,
    val maxDirectoryDepth: Int = 64,
    val maxVisitedEntries: Int = 50_000,
    val timeoutMs: Long = 30_000
) {
    init {
        require(maxFiles in 1..100_000 && maxFileBytes in 1..1_073_741_824 && maxExternalFileBytes in 1..1_073_741_824)
        require(maxTotalBytes in 1..4_294_967_296 && maxDirectoryDepth in 1..256 && maxVisitedEntries in 1..1_000_000 && timeoutMs in 1..300_000)
    }
}

data class InputSeal(val hashes: Map<String, String>, val totalBytes: Long) {
    val fileCount: Int get() = hashes.size
}

/** Generic errors deliberately omit paths, source, command arguments and provider output. */
class InputSealException : IllegalStateException("Declared provider inputs could not be sealed within the configured trust and resource limits.")

/** Content snapshots establish freshness only for the declared files; this is not a process sandbox. */
object ProviderInputSealer {
    fun matchesProjectPath(relativePath: String, scope: ProviderInputScope): Boolean = projectPathMatcher(scope)(relativePath)

    /** Compile once for filesystem event batches; scope collections are copied at construction. */
    fun projectPathMatcher(scope: ProviderInputScope): (String) -> Boolean {
        scope.validate()
        val includes = scope.includes.map(::PortableGlob)
        val excludes = scope.excludes.map(::PortableGlob)
        return { relativePath ->
            relativePath.isNotEmpty() && !relativePath.startsWith('/') && '\\' !in relativePath &&
                relativePath.split('/').none { it.isEmpty() || it == "." || it == ".." } &&
                includes.any { it.matches(relativePath) } && excludes.none { it.excludes(relativePath) }
        }
    }

    fun capture(
        projectRoot: Path,
        scopes: List<ProviderInputScope>,
        externalFiles: List<Path> = emptyList(),
        projectRoots: List<Path> = listOf(projectRoot),
        limits: InputSealLimits = InputSealLimits(),
        checkCancelled: () -> Unit = {}
    ): InputSeal = captureInternal(projectRoot, scopes, externalFiles, projectRoots, limits, checkCancelled, emptyList())

    /** Exact observed paths, including names containing glob metacharacters, use the same boundaries. */
    fun captureProjectFiles(
        projectRoot: Path,
        files: List<Path>,
        limits: InputSealLimits = InputSealLimits(),
        checkCancelled: () -> Unit = {}
    ): InputSeal = captureInternal(projectRoot, emptyList(), emptyList(), listOf(projectRoot), limits, checkCancelled, files)

    private fun captureInternal(
        projectRoot: Path, scopes: List<ProviderInputScope>, externalFiles: List<Path>, projectRoots: List<Path>,
        limits: InputSealLimits, checkCancelled: () -> Unit, projectFiles: List<Path>
    ): InputSeal {
        val started = System.nanoTime()
        var cancellation: Exception? = null
        fun budget() {
            try { checkCancelled() } catch (error: Exception) { cancellation = error; throw error }
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            require(System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(limits.timeoutMs))
        }
        try {
            require(scopes.size <= 33 && externalFiles.size <= 256 && projectRoots.size <= 128)
            scopes.forEach(ProviderInputScope::validate)
            val root = projectRoot.toRealPath()
            require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
            val roots = (projectRoots + listOf(root)).map { it.toRealPath() }.distinct()
            val compiled = scopes.map { scope -> scope.includes.map(::PortableGlob) to scope.excludes.map(::PortableGlob) }
            fun included(relative: String) = compiled.any { (includes, excludes) ->
                includes.any { it.matches(relative) } && excludes.none { it.excludes(relative) }
            }
            fun relevantDirectory(relative: String) = compiled.any { (includes, excludes) ->
                includes.any { it.canMatchBelow(relative) } && excludes.none { it.excludes(relative) }
            }
            val hashes = sortedMapOf<String, String>()
            var totalBytes = 0L
            var visited = 0
            fun seal(anchor: Path, file: Path, key: String, maximum: Long) {
                budget()
                require(hashes.size < limits.maxFiles)
                var count = 0L
                val digest = MessageDigest.getInstance("SHA-256")
                checkedRead(anchor, file) { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        budget()
                        val read = input.read(buffer)
                        if (read < 0) break
                        count += read
                        require(count <= maximum && totalBytes + count <= limits.maxTotalBytes)
                        digest.update(buffer, 0, read)
                    }
                }
                totalBytes += count
                hashes[key] = digest.digest().joinToString("") { "%02x".format(it) }
            }
            if (compiled.any { it.first.isNotEmpty() }) {
                Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(directory: Path, attrs: BasicFileAttributes): FileVisitResult {
                        budget()
                        require(++visited <= limits.maxVisitedEntries)
                        if (directory == root) return FileVisitResult.CONTINUE
                        val relative = root.relativize(directory).toString().replace('\\', '/')
                        if (!relevantDirectory(relative)) return FileVisitResult.SKIP_SUBTREE
                        require(root.relativize(directory).nameCount <= limits.maxDirectoryDepth && !attrs.isSymbolicLink)
                        return FileVisitResult.CONTINUE
                    }
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        budget()
                        require(++visited <= limits.maxVisitedEntries)
                        val relative = root.relativize(file).toString().replace('\\', '/')
                        // A linked directory can hide included descendants. Never follow it.
                        if (attrs.isSymbolicLink && relevantDirectory(relative)) throw InputSealException()
                        if (included(relative)) {
                            require(attrs.isRegularFile && !attrs.isSymbolicLink && attrs.size() <= limits.maxFileBytes)
                            seal(root, file, "project:$relative", limits.maxFileBytes)
                        }
                        return FileVisitResult.CONTINUE
                    }
                    override fun visitFileFailed(file: Path, error: java.io.IOException): FileVisitResult {
                        val relative = root.relativize(file).toString().replace('\\', '/')
                        if (file == root || included(relative) || relevantDirectory(relative)) throw InputSealException()
                        return FileVisitResult.CONTINUE
                    }
                    override fun postVisitDirectory(directory: Path, error: java.io.IOException?): FileVisitResult {
                        if (error != null) throw InputSealException()
                        return FileVisitResult.CONTINUE
                    }
                })
            }
            val externals = (externalFiles + scopes.flatMap { it.externalFiles }.map(Path::of)).map {
                require(it.isAbsolute)
                it.toAbsolutePath().normalize()
            }.distinct().sortedBy(Path::toString)
            require(projectFiles.size <= limits.maxFiles)
            projectFiles.map { it.toAbsolutePath().normalize() }.distinct().sortedBy(Path::toString).forEach { file ->
                require(file.startsWith(root) && file != root)
                require(root.relativize(file).nameCount - 1 <= limits.maxDirectoryDepth)
                val relative = root.relativize(file).toString().replace('\\', '/')
                if ("project:$relative" !in hashes) seal(root, file, "project:$relative", limits.maxFileBytes)
            }
            externals.forEach { file ->
                budget()
                require(roots.none { file.startsWith(it) })
                // Validate all components before resolving: resolving first would hide a link.
                val anchor = file.root
                validatePath(anchor, file)
                val real = file.toRealPath()
                require(roots.none { real.startsWith(it) } && real == file)
                require(Files.size(file) <= limits.maxExternalFileBytes)
                seal(anchor, file, "external:$file", limits.maxExternalFileBytes)
            }
            budget()
            return InputSeal(Collections.unmodifiableMap(hashes.toMap()), totalBytes)
        } catch (error: InterruptedException) { throw error }
          catch (error: InputSealException) { throw error }
          catch (error: Exception) { if (error === cancellation) throw error; throw InputSealException() }
    }

    private fun validatePath(anchor: Path, file: Path): BasicFileAttributes {
        require(file.startsWith(anchor) && file != anchor)
        require(Files.isDirectory(anchor, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(anchor))
        var current = anchor
        val parts = anchor.relativize(file).toList()
        var leaf: BasicFileAttributes? = null
        parts.forEachIndexed { index, part ->
            current = current.resolve(part)
            val attrs = Files.readAttributes(current, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            require(!attrs.isSymbolicLink && if (index == parts.lastIndex) attrs.isRegularFile && Files.isReadable(current) else attrs.isDirectory)
            leaf = attrs
        }
        return requireNotNull(leaf)
    }

    private fun checkedRead(anchor: Path, file: Path, consume: (java.io.InputStream) -> Unit) {
        val before = validatePath(anchor, file)
        Files.newDirectoryStream(anchor).use { directory ->
            if (directory is SecureDirectoryStream<Path>) {
                val opened = mutableListOf<SecureDirectoryStream<Path>>()
                var parent: SecureDirectoryStream<Path> = directory
                try {
                    val parts = anchor.relativize(file).toList()
                    parts.dropLast(1).forEach { part ->
                        parent = parent.newDirectoryStream(part, LinkOption.NOFOLLOW_LINKS)
                        opened.add(parent)
                    }
                    parent.newByteChannel(parts.last(), setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use {
                        consume(Channels.newInputStream(it))
                    }
                } finally { opened.asReversed().forEach { it.close() } }
            } else {
                validatePath(anchor, file)
                Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use(consume)
            }
        }
        val after = validatePath(anchor, file)
        require(before.fileKey() == after.fileKey() && before.size() == after.size() && before.lastModifiedTime() == after.lastModifiedTime())
    }
}
