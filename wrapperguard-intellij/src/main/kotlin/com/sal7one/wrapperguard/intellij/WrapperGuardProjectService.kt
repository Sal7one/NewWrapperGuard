package com.sal7one.wrapperguard.intellij

import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import com.sal7one.wrapperguard.core.model.ScanReport
import com.sal7one.wrapperguard.core.policy.EnforcementMode
import com.sal7one.wrapperguard.core.policy.ScanPolicy
import com.sal7one.wrapperguard.intellij.settings.buildScanSettings
import com.sal7one.wrapperguard.rules.RepositoryScanner
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class WrapperGuardProjectService(private val project: Project) {

    private val listeners = CopyOnWriteArrayList<(ScanReport?) -> Unit>()

    /** Single-flight guard: only one authoritative scan generation may publish a report. */
    private val scanInFlight = AtomicBoolean(false)

    /** A relevant file changed while a scan was running; another pass is needed. */
    private val rescanRequested = AtomicBoolean(false)

    private val rescanScheduler = AppExecutorUtil.getAppScheduledExecutorService()
    private var scheduledRescan: ScheduledFuture<*>? = null

    /** Content hashes of the watched files at the time of the last completed scan. */
    private val lastHashes = ConcurrentHashMap<String, String>()

    @Volatile
    var report: ScanReport? = null
        private set

    /** True when a watched file changed after the last completed scan — the seal is stale. */
    @Volatile
    var stale: Boolean = false
        private set

    /**
     * Runs a scan synchronously on the calling thread. Intended for background tasks.
     * UI callers should prefer [scanAsync].
     */
    fun scan(): ScanReport? {
        val root = project.basePath?.let(Path::of) ?: return null
        val result = RepositoryScanner().scan(root, ScanPolicy(mode = EnforcementMode.WARN), buildScanSettings())
        refreshHashes(root)
        stale = false
        report = result
        notifyListeners(result)
        return result
    }

    /**
     * Runs a scan in the background (off the EDT). When it finishes, the latest
     * [report] is published to all listeners — including the Compose UI, which
     * observes it and re-renders. Single-flight: if a scan is already running,
     * a follow-up pass is requested instead of dropping the change.
     */
    fun scanAsync() {
        if (!scanInFlight.compareAndSet(false, true)) {
            rescanRequested.set(true)
            return
        }
        object : Task.Backgroundable(project, "WrapperGuard: Scanning project", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    scan()
                } finally {
                    scanInFlight.set(false)
                    if (rescanRequested.getAndSet(false)) scanAsync()
                }
            }
        }.queue()
    }

    /**
     * Scans only if there is no report yet — used when the tool window opens so the UI
     * populates immediately on first open without re-scanning on every window toggle.
     */
    fun scanIfNoReport() {
        if (report == null) scanAsync()
    }

    /**
     * A watched file changed: mark the current report STALE immediately (the execution seal
     * no longer matches what is on disk), then debounce the *rescan* by
     * [RESCAN_DEBOUNCE_MS] to absorb write bursts. The content-hash check in
     * [runDebouncedRescan] drops VFS-only noise (timestamp refreshes, reverts).
     */
    fun markStaleAndScheduleRescan() {
        val current = report
        stale = true
        if (current != null) notifyListeners(current.copy(stale = true))
        scheduledRescan?.cancel(false)
        scheduledRescan = rescanScheduler.schedule(
            { runDebouncedRescan() },
            RESCAN_DEBOUNCE_MS,
            TimeUnit.MILLISECONDS
        )
    }

    private fun runDebouncedRescan() {
        if (project.isDisposed) return
        if (scanInFlight.get()) {
            rescanRequested.set(true)
            return
        }
        val root = project.basePath?.let(Path::of) ?: return
        val current = report
        if (current != null && hashesUnchanged(root)) {
            // Nothing on disk actually changed: restore freshness without a new scan.
            stale = false
            notifyListeners(current.copy(stale = false))
        } else {
            scanAsync()
        }
    }

    private fun hashesUnchanged(root: Path): Boolean {
        if (lastHashes.isEmpty()) return false
        return runCatching { computeWatchedHashes(root) }.getOrNull() == lastHashes
    }

    /**
     * Execution seal: content hashes of every watched file at this instant. The run guard
     * compares two such seals (before/after its scan) so a file changing mid-scan cannot
     * slip through — see WrapperGuardBeforeRunTaskProvider.
     */
    fun computeSeal(): Map<String, String>? {
        val root = project.basePath?.let(Path::of) ?: return null
        return runCatching { computeWatchedHashes(root) }.getOrNull()
    }

    /**
     * Publishes a report produced outside the normal scan flow (e.g. the pre-run guard's
     * ENFORCE-mode scan) so the tool window reflects what actually gated the run. The
     * hash baseline and stale state are refreshed to match that scan.
     */
    fun publishExternalReport(newReport: ScanReport) {
        project.basePath?.let(Path::of)?.let { refreshHashes(it) }
        stale = false
        report = newReport
        notifyListeners(newReport)
    }

    fun addListener(listener: (ScanReport?) -> Unit) {
        listeners += listener
        listener(report)
    }

    fun removeListener(listener: (ScanReport?) -> Unit) {
        listeners -= listener
    }

    private fun notifyListeners(r: ScanReport?) {
        listeners.forEach { it(r) }
    }

    private fun refreshHashes(root: Path) {
        lastHashes.clear()
        runCatching { computeWatchedHashes(root) }
            .getOrDefault(emptyMap())
            .forEach { (k, v) -> lastHashes[k] = v }
    }

    private fun computeWatchedHashes(root: Path): Map<String, String> {
        val hashes = HashMap<String, String>()
        if (!Files.isDirectory(root)) return hashes
        Files.walk(root).use { stream ->
            stream
                .filter { path ->
                    Files.isRegularFile(path) &&
                        !isExcluded(root, path) &&
                        isWatchedFileName(path.fileName.toString())
                }
                .forEach { path ->
                    val content = runCatching { Files.readAllBytes(path) }.getOrNull() ?: return@forEach
                    val sha = MessageDigest.getInstance("SHA-256")
                        .digest(content)
                        .joinToString("") { "%02x".format(it) }
                    hashes[root.relativize(path).toString().replace('\\', '/')] = sha
                }
        }
        return hashes
    }

    private fun isExcluded(root: Path, path: Path): Boolean {
        val parts = root.relativize(path).map { it.toString() }
        return parts.any { it in EXCLUDED_DIRS }
    }

    companion object {
        /** Debounce window for rescans after VFS change bursts (Gradle sync writes etc.). */
        const val RESCAN_DEBOUNCE_MS: Long = 3_000

        private val EXCLUDED_DIRS = setOf(
            ".git", ".gradle", ".idea", "build", "out", ".intellijPlatform", ".kotlin", "node_modules"
        )

        /** The file names whose content changes invalidate a scan. */
        fun isWatchedFileName(name: String): Boolean =
            name == "gradlew" ||
                name == "gradlew.bat" ||
                name == "gradle-wrapper.properties" ||
                name == "gradle-wrapper.jar" ||
                name == "libs.versions.toml" ||
                name.endsWith(".gradle") ||
                name.endsWith(".gradle.kts") ||
                name.endsWith(".sh") ||
                name.endsWith(".bat") ||
                name.endsWith(".cmd") ||
                name.endsWith(".ps1")
    }
}
