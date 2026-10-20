package com.sal7one.wrapperguard.core.history

import com.sal7one.wrapperguard.core.model.ScanReport
import com.sal7one.wrapperguard.core.vetting.VettingOperation
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

@Serializable data class HistoryProvider(val id: String, val version: String, val status: String)
@Serializable data class HistoryFinding(
    val fingerprint: String, val providerId: String, val ruleId: String, val title: String,
    val severity: String, val status: String, val path: String
)
@Serializable data class HistoryEntry(
    val schemaVersion: Int = 1,
    val id: String,
    val projectId: String,
    val createdAt: Long,
    val operation: String,
    val scopeId: String,
    val scopeKnown: Boolean,
    val complete: Boolean,
    val stale: Boolean,
    val truncated: Boolean,
    val verdict: String,
    val providers: List<HistoryProvider>,
    val findings: List<HistoryFinding>
)
data class HistoryScope(val scopeId: String, val known: Boolean) {
    init { require(scopeId.matches(Regex("[a-f0-9]{64}"))) }
}
data class HistoryDelta(
    val available: Boolean, val reason: String? = null,
    val newlyReported: List<HistoryFinding> = emptyList(),
    val noLongerReported: List<HistoryFinding> = emptyList(), val unchanged: Int = 0
) {
    companion object {
        fun compare(previous: HistoryEntry, current: HistoryEntry): HistoryDelta {
            if (previous.projectId != current.projectId || previous.operation != current.operation ||
                previous.scopeId != current.scopeId || !previous.scopeKnown || !current.scopeKnown) {
                return HistoryDelta(false, "Project, operation or verified analysis scope differs.")
            }
            if (!previous.complete || !current.complete || previous.stale || current.stale || previous.truncated || current.truncated) {
                return HistoryDelta(false, "Both reports must be complete, current and untruncated for comparison.")
            }
            fun key(finding: HistoryFinding) = finding.providerId + "\u0000" + finding.fingerprint
            val old = previous.findings.associateBy(::key)
            val now = current.findings.associateBy(::key)
            return HistoryDelta(true,
                newlyReported = now.filterKeys { it !in old }.values.toList(),
                noLongerReported = old.filterKeys { it !in now }.values.toList(),
                unchanged = now.keys.count { it in old })
        }
    }
}

/** Opt-in local summaries. History never modifies findings, approvals or enforcement. */
class HistoryStore(private val baseDirectory: Path) {
    init { require(baseDirectory.isAbsolute) { "History storage must use an absolute user path." } }

    @Synchronized
    fun record(projectRoot: Path, report: ScanReport, operation: VettingOperation, scope: HistoryScope, retention: Int = 20): HistoryEntry {
        require(retention in 1..100)
        val root = projectRoot.toRealPath()
        require(Path.of(report.projectPath).toRealPath() == root) { "History report belongs to another project." }
        val directory = directory(root, true)!!
        val previous = list(root)
        val records = report.findings.map { finding ->
            HistoryFinding(finding.fingerprint, finding.providerId ?: "wrapperguard", finding.ruleId, finding.title.take(512),
                finding.severity.name, finding.status.name, finding.location.path.replace('\\', '/'))
        }.distinctBy { it.providerId + "\u0000" + it.fingerprint }
        var entry = HistoryEntry(id = UUID.randomUUID().toString(), projectId = projectId(root),
            createdAt = maxOf(System.currentTimeMillis(), (previous.firstOrNull()?.createdAt ?: 0L) + 1L), operation = operation.name, scopeId = scope.scopeId, scopeKnown = scope.known,
            complete = report.complete, stale = report.stale, truncated = records.size > MAX_FINDINGS || report.providerRuns.size > 33,
            verdict = report.verdict.name,
            providers = report.providerRuns.take(33).map { HistoryProvider(it.descriptor.id, it.descriptor.version, it.status.name) },
            findings = records.take(MAX_FINDINGS))
        var bytes = JSON.encodeToString(entry).toByteArray(Charsets.UTF_8)
        while (bytes.size > MAX_BYTES && entry.findings.isNotEmpty()) {
            entry = entry.copy(truncated = true, findings = entry.findings.take((entry.findings.size * 0.8).toInt()))
            bytes = JSON.encodeToString(entry).toByteArray(Charsets.UTF_8)
        }
        require(bytes.size <= MAX_BYTES && valid(entry, entry.id, entry.projectId)) { "Invalid bounded history summary." }
        val temporary = directory.resolve(".${entry.id}.tmp")
        try {
            Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { it.write(bytes) }
            privatePermissions(temporary, false)
            try { Files.move(temporary, directory.resolve("${entry.id}.json"), StandardCopyOption.ATOMIC_MOVE) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, directory.resolve("${entry.id}.json")) }
        } finally { Files.deleteIfExists(temporary) }
        list(root).drop(retention).forEach { deleteEntry(directory, it) }
        return entry
    }

    @Synchronized
    fun list(projectRoot: Path): List<HistoryEntry> {
        val project = projectId(projectRoot)
        val directory = directory(projectRoot, false) ?: return emptyList()
        val records = mutableListOf<HistoryEntry>()
        var inspected = 0
        val deadline = System.nanoTime() + 5_000_000_000L
        Files.newDirectoryStream(directory).use { stream ->
            for (file in stream) {
                if (++inspected > 1000 || System.nanoTime() > deadline) throw IOException("History inspection limit reached.")
                val id = file.fileName.toString().removeSuffix(".json")
                if (file.fileName.toString() != "$id.json" || !uuid(id)) continue
                read(file, id, project)?.let { record ->
                    records.add(record)
                    if (records.size > 101) records.removeAt(records.indices.minBy { records[it].createdAt })
                }
            }
        }
        return records.sortedWith(compareByDescending<HistoryEntry> { it.createdAt }.thenByDescending { it.id })
    }

    @Synchronized
    fun clear(projectRoot: Path): Int {
        val directory = directory(projectRoot, false) ?: return 0
        return list(projectRoot).count { deleteEntry(directory, it) }
    }

    private fun deleteEntry(directory: Path, entry: HistoryEntry): Boolean {
        val file = directory.resolve("${entry.id}.json")
        if (read(file, entry.id, entry.projectId) == null) return false
        return Files.deleteIfExists(file)
    }

    private fun directory(projectRoot: Path, create: Boolean): Path? {
        val root = projectRoot.toRealPath()
        val lexical = baseDirectory.toAbsolutePath().normalize()
        var ancestor = lexical
        val missing = ArrayDeque<String>()
        while (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
            missing.addFirst(ancestor.fileName.toString())
            ancestor = ancestor.parent ?: throw IOException("Invalid history storage.")
        }
        var base = ancestor.toRealPath()
        missing.forEach { base = base.resolve(it) }
        require(!base.startsWith(root)) { "History storage must be outside the project." }
        if (!create && !Files.exists(base)) return null
        if (create) { Files.createDirectories(base); privatePermissions(base, true) }
        val marker = base.resolve(".wrapperguard-history")
        if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            if (!create) return null
            Files.writeString(marker, OWNER, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            privatePermissions(marker, false)
        }
        require(!Files.isSymbolicLink(marker) && Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) &&
            Files.size(marker) == OWNER.toByteArray().size.toLong() && Files.readString(marker) == OWNER) { "History ownership marker is invalid." }
        val directory = base.resolve(projectId(root))
        if (!create && !Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return null
        if (create && !Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(directory)
        require(!Files.isSymbolicLink(directory) && Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) { "History project directory is invalid." }
        if (create) privatePermissions(directory, true)
        return directory
    }

    private fun read(file: Path, id: String, project: String): HistoryEntry? = try {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_BYTES) null
        else {
            val bytes = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(MAX_BYTES + 1) }
            if (bytes.size > MAX_BYTES) null else JSON.decodeFromString<HistoryEntry>(bytes.toString(Charsets.UTF_8))
                .takeIf { valid(it, id, project) }
        }
    } catch (_: Exception) { null }

    companion object {
        private const val MAX_BYTES = 2 * 1024 * 1024
        private const val MAX_FINDINGS = 5000
        private const val OWNER = "WrapperGuard history schema 1\n"
        private val JSON = Json { encodeDefaults = true; ignoreUnknownKeys = false }
        fun projectId(projectRoot: Path): String {
            var path = projectRoot.toRealPath().toString().replace('\\', '/')
            if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) path = path.lowercase(Locale.ROOT)
            return digest(path)
        }
        fun scopeId(value: String): String = digest(value)
        private fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        private fun uuid(id: String): Boolean = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
        private fun text(value: String, limit: Int): Boolean = value.isNotEmpty() && value.length <= limit && value.none { it.code < 32 || it.code == 127 }
        private fun path(value: String): Boolean = value == "." || text(value, 4096) &&
            !value.startsWith('/') && '\\' !in value && !Regex("^[A-Za-z]:").containsMatchIn(value) &&
            value.split('/').none { it.isEmpty() || it == "." || it == ".." }
        private fun valid(entry: HistoryEntry, id: String, project: String): Boolean =
            entry.schemaVersion == 1 && entry.id == id && uuid(id) && entry.projectId == project && project.matches(Regex("[a-f0-9]{64}")) &&
            entry.scopeId.matches(Regex("[a-f0-9]{64}")) && entry.createdAt in 0..9_007_199_254_740_991 &&
            entry.operation in setOf("SCAN", "BUILD", "SYNC") && entry.verdict in setOf("NOT_SCANNED", "CLEAN", "REVIEW_REQUIRED", "EXECUTION_BLOCKED", "INCOMPLETE", "STALE", "FAILED") &&
            entry.providers.size <= 33 && entry.providers.all { text(it.id, 80) && text(it.version, 80) && it.status in setOf("COMPLETED", "FAILED", "TIMED_OUT", "DISABLED") } &&
            entry.findings.size <= MAX_FINDINGS && entry.findings.all { text(it.fingerprint, 128) && text(it.providerId, 80) && text(it.ruleId, 200) && text(it.title, 512) &&
                it.severity in setOf("INFO", "WARN", "ERROR") && text(it.status, 64) && path(it.path) }
        private fun privatePermissions(path: Path, directory: Boolean) {
            try { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(if (directory) "rwx------" else "rw-------")) }
            catch (_: UnsupportedOperationException) { /* User-owned host storage ACLs apply. */ }
        }
    }
}
