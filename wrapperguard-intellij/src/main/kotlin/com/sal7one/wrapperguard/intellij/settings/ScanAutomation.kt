package com.sal7one.wrapperguard.intellij.settings

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import com.sal7one.wrapperguard.core.methods.SecurityMethodCatalog
import com.sal7one.wrapperguard.core.policy.ScanSettings
import com.sal7one.wrapperguard.core.vetting.*
import com.sal7one.wrapperguard.intellij.WrapperGuardProjectService
import com.sal7one.wrapperguard.intellij.execution.NativeProviderInputs
import com.sal7one.wrapperguard.intellij.vetting.IntellijVettingProviders
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Persisted preferences never arm a session. Network/cost are declarations, not a sandbox. */
data class AutomationProviderState(var network: String = "UNKNOWN", var cost: String = "UNKNOWN", var acknowledged: Boolean = false)
data class ScanAutomationState(var schemaVersion: Int = 1, var enabled: Boolean = false, var onChanges: Boolean = true,
    var debounceMs: Int = 2000, var intervalMinutes: Int = 0,
    var providers: MutableMap<String, AutomationProviderState> = mutableMapOf()) {
    fun snapshot(): ScanAutomationState = copy(providers = providers.toSortedMap().mapValues { it.value.copy() }.toMutableMap())
    fun validate(): ScanAutomationState {
        require(schemaVersion == 1 && debounceMs in 500..60000 && (intervalMinutes == 0 || intervalMinutes in 5..1440) && (onChanges || intervalMinutes > 0)) { "Choose a 500–60000 ms debounce, a 5–1440 minute interval (or zero), and at least one trigger." }
        require(providers.size <= 33 && providers.all { (id, value) -> id.matches(Regex("[a-z][a-z0-9._-]{0,79}")) && value.network in setOf("NONE", "LOCAL", "REMOTE", "UNKNOWN") && value.cost in setOf("LOW", "METERED", "UNKNOWN") }) { "Review each provider's network and cost declaration." }
        return snapshot()
    }
}

internal interface AutomationClock { fun schedule(delayMs: Long, task: () -> Unit): () -> Unit }
internal data class AutomationView(val running: Boolean = false, val status: String = "Paused · Start explicitly for this project", val root: String? = null, val providerIds: Set<String> = emptySet())

/** Small testable lifecycle: no launch on save/start, no overlapping/replayed triggers, pause cancels. */
internal class ScanAutomationController(private val clock: AutomationClock,
    private val verify: () -> Boolean,
    private val run: (Set<String>, (Boolean) -> Unit) -> (() -> Unit)) {
    private var configuration = ScanAutomationState()
    private var debounce: (() -> Unit)? = null
    private var interval: (() -> Unit)? = null
    private var cancelRun: (() -> Unit)? = null
    private var busy = false
    private var generation = 0L
    @Volatile var view = AutomationView(); private set
    @Synchronized fun configure(value: ScanAutomationState) { pause("Paused · saved settings require Start"); configuration = value.validate() }
    @Synchronized fun start(root: String, readyIds: Set<String>) {
        val ids = configuration.providers.keys.toSet()
        require(configuration.enabled && ids.isNotEmpty() && ids.all { configuration.providers.getValue(it).acknowledged && it in readyIds }) { "Select, enable and acknowledge every provider before starting automation." }
        pause("Starting")
        view = AutomationView(true, "Watching · no scan until the next trigger", root, ids)
        scheduleInterval()
    }
    @Synchronized fun inputChanged(root: String) {
        if (!view.running || view.root != root || !configuration.onChanges) return
        debounce?.invoke()
        debounce = clock.schedule(configuration.debounceMs.toLong()) { synchronized(this) { debounce = null; tick() } }
    }
    @Synchronized fun pause(reason: String = "Paused by user") {
        generation++; debounce?.invoke(); interval?.invoke(); debounce = null; interval = null
        cancelRun?.invoke(); cancelRun = null; busy = false
        view = view.copy(running = false, status = reason)
    }
    private fun scheduleInterval() {
        if (view.running && configuration.intervalMinutes > 0) interval = clock.schedule(configuration.intervalMinutes * 60000L) {
            synchronized(this) { interval = null; tick(); scheduleInterval() }
        }
    }
    private fun tick() {
        if (!view.running || busy) return
        if (!runCatching(verify).getOrDefault(false)) { pause("Paused · settings, trust or provider readiness changed"); return }
        val token = generation; busy = true; view = view.copy(status = "Scanning reviewed providers")
        val cancellation = try { run(view.providerIds) { success -> synchronized(this) {
            if (generation != token) return@synchronized
            busy = false; cancelRun = null
            if (success) view = view.copy(status = "Watching · last automated scan completed") else pause("Paused · scan failed; review and Start again")
        } } } catch (_: Throwable) { pause("Paused · scan could not start; review readiness"); return }
        if (busy && token == generation) cancelRun = cancellation
    }
}

/** Omitted providers are disabled for this SCAN and cannot appear as requested missing methods. */
internal fun automationSettings(settings: ScanSettings, selectedIds: Set<String>): ScanSettings = settings.copy(
    methodFlags = settings.methodFlags + SecurityMethodCatalog.methods.filter { it.providerId !in selectedIds }.associate { it.id to false })

@Service(Service.Level.PROJECT)
internal class NativeScanAutomation(private val project: Project) : Disposable {
    private var baseline = ""
    private var sessionRoot = ""
    private val controller: ScanAutomationController = ScanAutomationController(object : AutomationClock {
        override fun schedule(delayMs: Long, task: () -> Unit): () -> Unit {
            val future = AppExecutorUtil.getAppScheduledExecutorService().schedule({ task() }, delayMs, TimeUnit.MILLISECONDS)
            return { future.cancel(false); Unit }
        }
    }, verify = { !project.isDisposed && TrustedProjects.isProjectTrusted(project) && digest() == baseline && readyIds().containsAll(controllerIds()) },
        run = { ids, finished -> project.getService(WrapperGuardProjectService::class.java).scanAutomaticAsync(ids, finished) })
    fun view(): AutomationView = controller.view
    private fun controllerIds(): Set<String> = controller.view.providerIds
    private fun root(): Path = Path.of(requireNotNull(project.basePath)).toRealPath()
    fun readyIds(): Set<String> {
        val settings = WrapperGuardSettings.getInstance().state
        val root = root()
        val configured = settings.providerConfigurationPath.takeIf(String::isNotBlank)?.let { ProviderConfiguration.load(Path.of(it), root, OptionalMethodConnections.roots(project)) }
        return IntellijVettingProviders.registrations(project, root).filter { registration ->
            val id = registration.provider.descriptor.id
            registration.permissions.enabled && (id == "wrapperguard" || SecurityMethodCatalog.methods.none { it.providerId == id && settings.methodFlags[it.id] == false }) && runCatching {
                val custom = settings.customProviders.firstOrNull { it.id == id }
                if (custom != null) {
                    CustomProviders.validate(custom, OptionalMethodConnections.roots(project))
                    require(custom.kind != "sdk" || IntellijVettingProviders.installedProviders().any { it.descriptor.id == id })
                }
                settings.methodConnections.firstOrNull { SecurityMethodCatalog.find(it.methodId)?.providerId == id && connectionIsSelected(registration.provider.descriptor, it) }?.let { connection ->
                    OptionalMethodProfiles.validate(requireNotNull(SecurityMethodCatalog.find(connection.methodId)), connection, OptionalMethodConnections.roots(project))
                    require(Files.isRegularFile(Path.of(connection.profilePath)))
                }
                configured?.providers?.firstOrNull { it.id == id }?.validate()
                true
            }.getOrDefault(false)
        }.map { it.provider.descriptor.id }.toSet()
    }
    fun start() {
        require(TrustedProjects.isProjectTrusted(project)) { "Trust the project through the IDE before starting automation." }
        val value = WrapperGuardSettings.getInstance().state.automation.validate()
        controller.configure(value)
        sessionRoot = root().toString(); baseline = digest()
        controller.start(sessionRoot, readyIds())
    }
    fun inputChanged(): Boolean {
        if (!controller.view.running) return false
        val current = runCatching { root().toString() }.getOrDefault("")
        if (current != sessionRoot) pause("Paused · project root changed") else controller.inputChanged(current)
        return true
    }
    fun pause(reason: String = "Paused by user") = controller.pause(reason)
    override fun dispose() = pause("Stopped · project closed")

    /** Seal reviewed configuration and trusted tool identity; source/report payload changes remain triggers. */
    private fun digest(): String {
        val root = root(); val state = WrapperGuardSettings.getInstance().state
        val selected = state.automation.providers.keys
        val registrations = IntellijVettingProviders.registrations(project, root).filter { it.provider.descriptor.id in selected }
        val inputs = NativeProviderInputs.capture(root, NativeProviderInputs.snapshot(registrations, state, root), OptionalMethodConnections.roots(project))
        val payloads = state.methodConnections.flatMap { listOf(it.reportPath, it.provenanceManifestPath) }.filter(String::isNotBlank).map { runCatching { Path.of(it).toRealPath().toString() }.getOrDefault(Path.of(it).toAbsolutePath().normalize().toString()) }.toSet()
        val identity = buildString {
            append(root).append(TrustedProjects.isProjectTrusted(project)).append(buildScanSettings()).append(state.automation.snapshot())
            append(state.providers.sortedBy { it.id }).append(state.customProviders.sortedBy { it.id }).append(state.methodConnections.sortedBy { it.methodId })
            registrations.sortedBy { it.provider.descriptor.id }.forEach { append(it.provider.descriptor).append(it.provider.descriptor.category).append(it.provider.descriptor.summary).append(it.provider.descriptor.tags).append(it.provider.descriptor.inputScope).append(it.permissions) }
            inputs.toSortedMap().filter { (key, _) -> key.startsWith("tool:") || key.startsWith("external:") && key.removePrefix("external:") !in payloads }.forEach { (key, hash) -> append(key).append(hash) }
            if (state.providerConfigurationPath.isNotBlank()) {
                val path = Path.of(state.providerConfigurationPath); require(Files.size(path) <= 65_536)
                val bytes = Files.newInputStream(path).use { it.readNBytes(65_537) }; require(bytes.size <= 65_536); append(bytes.toString(Charsets.UTF_8))
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
