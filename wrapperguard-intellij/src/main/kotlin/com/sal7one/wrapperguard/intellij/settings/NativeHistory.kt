package com.sal7one.wrapperguard.intellij.settings

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import com.sal7one.wrapperguard.core.history.*
import com.sal7one.wrapperguard.core.model.ScanReport
import com.sal7one.wrapperguard.core.vetting.*
import com.sal7one.wrapperguard.intellij.execution.NativeProviderInputs
import com.sal7one.wrapperguard.intellij.vetting.IntellijVettingProviders
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Path

/** Local opt-in summaries, written only after the host has established its freshness result. */
internal object NativeHistory {
    private val stores = java.util.concurrent.ConcurrentHashMap<Path, HistoryStore>()
    private fun store(project: Project): HistoryStore {
        val path = Path.of(PathManager.getConfigPath(), "wrapperguard", "history").toAbsolutePath().normalize()
        require(OptionalMethodConnections.roots(project).none { path.startsWith(it.toAbsolutePath().normalize()) || path.startsWith(it.toRealPath()) })
        return stores.computeIfAbsent(path, ::HistoryStore)
    }
    fun list(project: Project) = store(project).list(Path.of(requireNotNull(project.basePath)))
    fun clear(project: Project) = store(project).clear(Path.of(requireNotNull(project.basePath)))
    fun record(project: Project, report: ScanReport, operation: VettingOperation, sealedInputs: Map<String, String>): HistoryEntry? {
        val state = WrapperGuardSettings.getInstance().state
        if (!state.historyEnabled) return null
        val root = Path.of(requireNotNull(project.basePath))
        val registrations = runCatching { IntellijVettingProviders.registrations(project, root) }
        val activeIds = report.providerRuns.filter { it.status != ProviderRunStatus.DISABLED }.map { it.descriptor.id }.toSet()
        val enabled = registrations.getOrDefault(emptyList()).filter { it.permissions.enabled && it.provider.descriptor.id in activeIds }
        val configuration = if (state.providerConfigurationPath.isBlank()) null else ProviderConfiguration.load(Path.of(state.providerConfigurationPath), root, OptionalMethodConnections.roots(project))
        val providerScopes = enabled.map { registration ->
            val descriptor = registration.provider.descriptor
            val connection = state.methodConnections.firstOrNull { com.sal7one.wrapperguard.core.methods.SecurityMethodCatalog.find(it.methodId)?.providerId == descriptor.id }
            val custom = state.customProviders.firstOrNull { it.id == descriptor.id }
            val command = when {
                connection != null -> OptionalMethodProfiles.command(requireNotNull(com.sal7one.wrapperguard.core.methods.SecurityMethodCatalog.find(connection.methodId)), connection, registration.permissions).command
                custom?.kind == "command" -> listOf(custom.executable) + custom.arguments
                else -> configuration?.providers?.firstOrNull { it.id == descriptor.id }?.command.orEmpty()
            }
            fun sealed(path: String): Boolean = runCatching { "tool:external:${Path.of(path).toRealPath()}" in sealedInputs }.getOrDefault(false)
            NativeHistoryProviderScope(builtIn = registration.provider is BuiltInVettingProvider,
                inProcessSdk = registration.provider !is BuiltInVettingProvider && registration.provider !is CommandVettingProvider,
                declared = descriptor.inputScope != null, externalFileCount = descriptor.inputScope?.externalFiles?.size ?: 0,
                command = command, managedMethod = connection?.methodId, reportImport = connection?.mode == "report", modulePath = connection?.modulePath.orEmpty(),
                binarySealed = command.firstOrNull()?.let(::sealed) == true,
                runnerSealed = connection?.runnerExecutable?.takeIf(String::isNotBlank)?.let(::sealed) == true)
        }
        val imported = state.methodConnections.map { it.reportPath }
            .filter(String::isNotBlank).map { runCatching { Path.of(it).toRealPath().toString() }.getOrDefault(Path.of(it).toAbsolutePath().normalize().toString()) }.toSet()
        val scopeText = buildString {
            append(buildScanSettings())
            report.notices.filter { it.code == "WG-AUTOMATED-SCAN" }.forEach { append(it.message) }
            enabled.sortedBy { it.provider.descriptor.id }.forEach { registration ->
                val descriptor = registration.provider.descriptor
                append(descriptor).append(descriptor.category).append(descriptor.summary).append(descriptor.tags).append(descriptor.inputScope)
                append(registration.permissions)
            }
            state.methodConnections.sortedBy { it.methodId }.forEach { append(it.copy(profilePath = "", configurationPath = "")) }
            state.customProviders.sortedBy { it.id }.forEach(::append)
            if (state.providerConfigurationPath.isNotBlank()) {
                append(Json.encodeToString(configuration))
            }

        }
        return store(project).record(root, report, operation, nativeHistoryScope(scopeText, providerScopes, sealedInputs, imported, registrations.isSuccess), state.historyLimit.coerceIn(1, 100))
    }
}

/** Conservative comparability: declarations alone cannot describe private plugin/package/model settings. */
internal data class NativeHistoryProviderScope(
    val builtIn: Boolean = false, val inProcessSdk: Boolean = false, val declared: Boolean = false,
    val externalFileCount: Int = 0, val command: List<String> = emptyList(), val managedMethod: String? = null,
    val reportImport: Boolean = false, val modulePath: String = "", val binarySealed: Boolean = false, val runnerSealed: Boolean = false
) {
    fun known(): Boolean {
        if (builtIn) return true
        if (inProcessSdk || !declared || !binarySealed || reportImport || modulePath.isNotBlank()) return false
        // Imported producer/rule identities and mutable Python/model services are not proven by a provider version.
        if (managedMethod != null) return managedMethod in setOf("sca.trivy", "sca.osv") && runnerSealed
        if (externalFileCount > 0) return false // Custom external payload/config roles are not distinguishable.
        val executable = command.firstOrNull()?.let { Path.of(it).fileName.toString().lowercase().removeSuffix(".exe") }.orEmpty()
        return !(executable.matches(Regex("(?:python|pypy)[0-9.]*")) && "-m" in command.drop(1))
    }
}

internal fun nativeHistoryScope(policyState: String, providers: List<NativeHistoryProviderScope>,
    sealedInputs: Map<String, String>, reportPaths: Set<String>, registrationKnown: Boolean = true): HistoryScope {
    val identity = buildString {
        append(policyState)
        // Include profile/configuration, tool and rules bytes. Only source and known report payload bytes are omitted.
        sealedInputs.toSortedMap().filter { (key, _) -> key.startsWith("tool:") ||
            key.startsWith("external:") && key.removePrefix("external:") !in reportPaths }
            .forEach { (key, value) -> append('\u0000').append(key).append('\u0000').append(value) }
    }
    return HistoryScope(HistoryStore.scopeId(identity), registrationKnown && sealedInputs.isNotEmpty() && providers.all { it.known() })
}
