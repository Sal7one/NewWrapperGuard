package com.sal7one.wrapperguard.intellij

import com.intellij.openapi.components.Service
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import com.sal7one.wrapperguard.core.model.ScanReport
import com.sal7one.wrapperguard.core.policy.EnforcementMode
import com.sal7one.wrapperguard.core.policy.ScanPolicy
import com.sal7one.wrapperguard.intellij.settings.buildScanSettings
import com.sal7one.wrapperguard.intellij.settings.WrapperGuardSettings
import com.sal7one.wrapperguard.intellij.vetting.IntellijVettingProviders
import com.sal7one.wrapperguard.core.vetting.VettingOperation
import com.sal7one.wrapperguard.intellij.execution.watchedFileHashes
import com.sal7one.wrapperguard.intellij.demo.WrapperGuardDemo
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CancellationException

@Service(Service.Level.PROJECT)
class WrapperGuardProjectService(private val project: Project) {

    private val demoAtCreation = WrapperGuardDemo.enabled
    val isDemo: Boolean get() = demoAtCreation || WrapperGuardDemo.enabled

    private val listeners = CopyOnWriteArrayList<(ScanReport?) -> Unit>()

    /** Single-flight guard: only one authoritative scan generation may publish a report. */
    private val scanInFlight = AtomicBoolean(false)

    val isScanning: Boolean get() = scanInFlight.get()

    @Volatile
    var scanError: String? = null
        private set

    /** A relevant file changed while a scan was running; another pass is needed. */
    private val rescanRequested = AtomicBoolean(false)
    private val inputGeneration = AtomicLong()
    private val publicationLock = Any()

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
        if (project.isDisposed) return null
        if (isDemo) return reloadDemoReport()
        val root = project.basePath?.let(Path::of) ?: return null
        val generation = inputGeneration.get()
        val trusted = TrustedProjects.isProjectTrusted(project)
        val result = IntellijVettingProviders.scan(project, root, ScanPolicy(mode = EnforcementMode.WARN), VettingOperation.SCAN)
        if (project.isDisposed) return null
        ProgressManager.checkCanceled()
        refreshHashes(root)
        ProgressManager.checkCanceled()
        val published = synchronized(publicationLock) {
            val changed = generation != inputGeneration.get() || trusted != TrustedProjects.isProjectTrusted(project)
            stale = changed
            if (changed) rescanRequested.set(true)
            scanError = null
            (if (changed) result.copy(stale = true) else result).also { report = it }
        }
        notifyListeners(published)
        return published
    }

    /**
     * Runs a scan in the background (off the EDT). When it finishes, the latest
     * [report] is published to all listeners — including the Compose UI, which
     * observes it and re-renders. Single-flight: if a scan is already running,
     * a follow-up pass is requested instead of dropping the change.
     */
    fun scanAsync() {
        if (project.isDisposed) return
        if (isDemo) {
            reloadDemoReport()
            return
        }
        if (!scanInFlight.compareAndSet(false, true)) {
            rescanRequested.set(true)
            return
        }
        scanError = null
        notifyListeners(report)
        object : Task.Backgroundable(project, "WrapperGuard: Scanning project", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.checkCanceled()
                if (scan() == null) scanError = "This project has no local directory to scan."
            }

            override fun onThrowable(error: Throwable) {
                // Do not put arbitrary exception text (which may include source data) into the UI.
                scanError = "The scan could not finish. Review the provider configuration in WrapperGuard settings and retry."
            }

            override fun onCancel() {
                scanError = "Scan cancelled. Run a new scan to refresh the results."
            }

            override fun onFinished() {
                scanInFlight.set(false)
                notifyListeners(report)
                if (rescanRequested.getAndSet(false) && !project.isDisposed) scanAsync()
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
        if (isDemo) return
        val current = synchronized(publicationLock) {
            inputGeneration.incrementAndGet()
            stale = true
            report
        }
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
        if (isDemo) return
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
        return sealOrNull { computeWatchedHashes(root) } == lastHashes
    }

    /**
     * Execution seal: content hashes of every watched file at this instant. The run guard
     * compares two such seals (before/after its scan) so a file changing mid-scan cannot
     * slip through — see WrapperGuardBeforeRunTaskProvider.
     */
    fun computeSeal(root: Path? = project.basePath?.let(Path::of)): Map<String, String>? {
        if (isDemo) return null
        root ?: return null
        return sealOrNull {
            computeWatchedHashes(root) + ("<user-policy>" to policySeal())
        }
    }

    /** Include user policy, provider grants and command config in the launch seal. */
    private fun policySeal(): String {
        val settings = WrapperGuardSettings.getInstance()
        val state = settings.state
        val text = buildString {
            append(buildScanSettings().toString())
            append(state.providers.toString())
            append(TrustedProjects.isProjectTrusted(project))
            if (TrustedProjects.isProjectTrusted(project)) {
                append(IntellijVettingProviders.installedProviders().map { it.descriptor }.toString())
            }
            append(state.providerConfigurationPath)
            if (state.providerConfigurationPath.isNotBlank()) {
                val path = Path.of(state.providerConfigurationPath)
                require(Files.size(path) <= 65_536) { "Provider configuration exceeds the limit" }
                val bytes = Files.newInputStream(path).use { it.readNBytes(65_537) }
                require(bytes.size <= 65_536) { "Provider configuration exceeds the limit" }
                append(bytes.toString(Charsets.UTF_8))
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun settingsChanged() {
        synchronized(publicationLock) {
            inputGeneration.incrementAndGet()
            stale = true
        }
        scanAsync()
    }

    /**
     * Publishes a report produced outside the normal scan flow (e.g. the pre-run guard's
     * ENFORCE-mode scan) so the tool window reflects what actually gated the run. The
     * hash baseline and stale state are refreshed to match that scan.
     */
    fun publishExternalReport(newReport: ScanReport) {
        if (project.isDisposed) return
        if (isDemo) return
        project.basePath?.let(Path::of)?.let { refreshHashes(it) }
        stale = newReport.stale
        scanError = null
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

    private fun reloadDemoReport(): ScanReport? {
        scheduledRescan?.cancel(false)
        stale = false
        report = try {
            WrapperGuardDemo.loadReport().also { scanError = null }
        } catch (_: Exception) {
            scanError = "Demo report could not be loaded. Set an absolute wrapperguard.demoReport JSON path."
            null
        }
        notifyListeners(report)
        return report
    }

    private fun refreshHashes(root: Path) {
        lastHashes.clear()
        sealOrNull { computeWatchedHashes(root) }.orEmpty()
            .forEach { (k, v) -> lastHashes[k] = v }
    }

    private inline fun <T> sealOrNull(action: () -> T): T? = try {
        action()
    } catch (error: ProcessCanceledException) {
        throw error
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private fun computeWatchedHashes(root: Path): Map<String, String> =
        watchedFileHashes(root, buildScanSettings()) { ProgressManager.checkCanceled() }

    companion object {
        /** Debounce window for rescans after VFS change bursts (Gradle sync writes etc.). */
        const val RESCAN_DEBOUNCE_MS: Long = 3_000

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
