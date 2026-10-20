package com.sal7one.wrapperguard.core.inputs

import java.nio.file.Path
import java.security.MessageDigest

/**
 * First-party readers record hashes of bytes actually inspected on the current provider thread.
 * Source bytes are never retained or serialized. Third-party IO still needs declared scopes;
 * this observer does not intercept arbitrary JVM or subprocess filesystem access.
 */
object ProviderReadTracker {
    private class Reads(val root: Path, val limits: InputSealLimits) {
        val hashes = linkedMapOf<Path, String>()
        var bytes = 0L
        var failed = false
    }
    private val current = ThreadLocal<Reads>()

    fun record(file: Path, bytes: ByteArray) {
        val reads = current.get() ?: return
        try {
            val normalized = file.toAbsolutePath().normalize()
            require(normalized.startsWith(reads.root) && normalized != reads.root && bytes.size <= reads.limits.maxFileBytes)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val previous = reads.hashes[normalized]
            require(previous == null || previous == hash)
            if (previous == null) {
                require(reads.hashes.size < reads.limits.maxFiles && reads.bytes + bytes.size <= reads.limits.maxTotalBytes)
                reads.hashes[normalized] = hash
                reads.bytes += bytes.size
            }
        } catch (_: Exception) { reads.failed = true; throw InputSealException() }
    }

    fun <T> verifyReads(projectRoot: Path, limits: InputSealLimits = InputSealLimits(), onCaptured: (InputSeal) -> Unit = {}, block: () -> T): T {
        check(current.get() == null) { "A provider read observation is already active on this thread." }
        val reads = Reads(runCatching { projectRoot.toRealPath() }.getOrElse { projectRoot.toAbsolutePath().normalize() }, limits)
        current.set(reads)
        val result = try { block() } finally { current.remove() }
        if (reads.failed) throw InputSealException()
        if (reads.hashes.isEmpty()) return result
        val after = ProviderInputSealer.captureProjectFiles(reads.root, reads.hashes.keys.toList(), limits)
        val expected = reads.hashes.mapKeys { "project:${reads.root.relativize(it.key).toString().replace('\\', '/')}" }
        if (expected != after.hashes) throw InputSealException()
        onCaptured(after)
        return result
    }
}
