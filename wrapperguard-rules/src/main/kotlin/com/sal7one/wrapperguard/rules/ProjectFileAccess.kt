package com.sal7one.wrapperguard.rules

import java.nio.channels.Channels
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

/** Project source is data: reject links at every component before inspecting its bytes. */
internal object ProjectFileAccess {
    enum class Status { MISSING, REJECTED, REGULAR }

    fun status(root: Path, file: Path): Status = try {
        val base = root.toAbsolutePath().normalize()
        val input = file.toAbsolutePath().normalize()
        if (!input.startsWith(base) || input == base) Status.REJECTED else {
            var current = base
            val parts = base.relativize(input).toList()
            var result = Status.REGULAR
            for ((index, part) in parts.withIndex()) {
                current = current.resolve(part)
                val attrs = Files.readAttributes(current, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                if (attrs.isSymbolicLink || (index < parts.lastIndex && !attrs.isDirectory) ||
                    (index == parts.lastIndex && (!attrs.isRegularFile || !Files.isReadable(current)))) {
                    result = Status.REJECTED
                    break
                }
            }
            result
        }
    } catch (_: NoSuchFileException) { Status.MISSING }
      catch (_: Exception) { Status.REJECTED }

    fun read(root: Path, file: Path, maximumBytes: Long): ByteArray {
        require(maximumBytes in 0 until Int.MAX_VALUE.toLong() && status(root, file) == Status.REGULAR)
        val base = root.toAbsolutePath().normalize()
        val input = file.toAbsolutePath().normalize()
        val parts = base.relativize(input).toList()
        // Secure directory streams anchor parent lookups where the platform supports them.
        // On other hosts every component is checked again and the leaf is opened no-follow.
        Files.newDirectoryStream(base).use { directory ->
            if (directory is SecureDirectoryStream<Path>) {
                val opened = mutableListOf<SecureDirectoryStream<Path>>()
                var parent: SecureDirectoryStream<Path> = directory
                try {
                    for (part in parts.dropLast(1)) {
                        parent = parent.newDirectoryStream(part, LinkOption.NOFOLLOW_LINKS)
                        opened += parent
                    }
                    parent.newByteChannel(parts.last(), setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
                        return bytes(Channels.newInputStream(channel), maximumBytes)
                    }
                } finally { opened.asReversed().forEach { it.close() } }
            }
            require(status(base, input) == Status.REGULAR)
            Files.newInputStream(input, LinkOption.NOFOLLOW_LINKS).use { return bytes(it, maximumBytes) }
        }
    }

    private fun bytes(input: java.io.InputStream, maximum: Long): ByteArray {
        val data = input.readNBytes((maximum + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        require(data.size.toLong() <= maximum) { "Project input exceeds the configured limit." }
        return data
    }
}
