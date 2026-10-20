package com.sal7one.wrapperguard.intellij.settings

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.sal7one.wrapperguard.core.methods.SecurityMethod
import com.sal7one.wrapperguard.core.methods.SecurityMethodCatalog
import com.sal7one.wrapperguard.core.model.ScanReport
import com.sal7one.wrapperguard.core.util.CredentialRedaction
import com.sal7one.wrapperguard.core.vetting.*
import com.sal7one.wrapperguard.intellij.WrapperGuardProjectService
import com.sal7one.wrapperguard.intellij.execution.ManagedMethodInputScopes
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Application-owned connection metadata. Project files never populate this state. */
data class MethodConnectionState(
    var methodId: String = "",
    var pythonExecutable: String = "",
    var mode: String = "report",
    var reportPath: String = "",
    var runnerExecutable: String = "",
    var modulePath: String = "",
    var rulesPath: String = "",
    var rulePacks: MutableList<String> = mutableListOf("legacy-android-ktor"),
    var responseFormat: String = "json_schema",
    var targetOrigin: String = "",
    var targetPath: String = "/",
    var endpoint: String = "http://127.0.0.1:1234/v1",
    var model: String = "",
    var cachePath: String = "",
    var profilePath: String = "",
    var configurationPath: String = "",
    var projectRoot: String = "",
    var verified: Boolean = false,
    var preferManaged: Boolean = false,
    var allowedOperations: MutableList<String> = mutableListOf("SCAN"),
    var requestedActions: MutableList<String> = mutableListOf("WARNING")
)

internal object OptionalMethodProfiles {
    val formats = mapOf("sast.bandit" to "bandit", "sast.powershell" to "psscriptanalyzer", "sast.sarif" to "sarif", "sast.semgrep" to "sarif", "sast.sonar" to "sonar", "sca.cyclonedx" to "cyclonedx",
        "sca.trivy" to "trivy", "sca.osv" to "osv", "dast.zap" to "zap", "research.script-semantics" to "script-semantics", "ai.external-model" to "ai-review")
    val semgrepPacks = linkedMapOf("legacy-android-ktor" to "Core Android / Ktor", "android-manifest" to "Android manifest", "android-webview" to "Android WebView", "jvm-crypto" to "JVM cryptography", "ktor-runtime" to "Ktor runtime")
    val runnerMethods = setOf("sast.semgrep", "sast.bandit", "sast.powershell", "sca.trivy", "sca.osv", "research.script-semantics", "ai.external-model")

    fun checkedPath(value: String, roots: List<Path>, executable: Boolean = false, directoryAllowed: Boolean = false): Path {
        val supplied = Path.of(value.trim())
        require(supplied.isAbsolute) { "Choose an absolute installed path." }
        val path = supplied.toAbsolutePath().normalize()
        val boundaries = roots.flatMap { listOf(it.toAbsolutePath().normalize(), it.toRealPath()) }.distinct()
        val resolvedParent = (path.parent ?: path).toRealPath().resolve(path.fileName ?: Path.of(""))
        val target = path.toRealPath()
        require(boundaries.none { path.startsWith(it) || resolvedParent.startsWith(it) || target.startsWith(it) }) {
            "Choose a user-owned path outside all open projects, including its selected location and symlink target."
        }
        require(Files.isRegularFile(path) || directoryAllowed && Files.isDirectory(path)) { "Choose an existing file." }
        require(!executable || Files.isExecutable(path)) { "Choose an executable file." }
        return path
    }

    fun validate(method: SecurityMethod, connection: MethodConnectionState, roots: List<Path>) {
        require(method.id == connection.methodId && method.providerId != "wrapperguard") { "This method has no external connection." }
        if (method.id == "ai.external-model") {
            val endpoint = java.net.URI(connection.endpoint)
            require(endpoint.scheme in setOf("http", "https") && endpoint.host in setOf("127.0.0.1", "::1", "[::1]") &&
                endpoint.rawUserInfo == null && endpoint.rawQuery == null && endpoint.rawFragment == null) {
                "Use a numeric loopback endpoint such as http://127.0.0.1:1234/v1. Remote model endpoints are not allowed."
            }
            require(connection.responseFormat in setOf("json_schema", "json_object")) { "Choose a supported model response format." }
            require(connection.model.isNotBlank() && connection.model.length <= 160 && connection.model.none { it.isISOControl() }) { "Choose the exact model name loaded by your local model server." }
            val report = checkedPath(connection.reportPath, roots)
            require(Files.size(report) <= 16L * 1024 * 1024) { "Choose a report smaller than 16 MiB." }
        }
        checkedPath(connection.pythonExecutable, roots, executable = true)
        require(connection.mode == "report" || connection.mode == "runner") { "Choose a connection type." }
        if (connection.mode == "runner") {
            require(!System.getProperty("os.name").startsWith("Windows") || method.id == "ai.external-model") { "Direct source analyzers require a POSIX host. Import an existing report on Windows." }
            require(method.id in runnerMethods) { "This method imports a report and has no direct runner." }
            if (method.id !in setOf("research.script-semantics", "ai.external-model")) checkedPath(connection.runnerExecutable, roots, executable = true)
            if (method.id == "sast.semgrep") {
                if (connection.rulesPath.isNotBlank()) checkedPath(connection.rulesPath, roots)
                else require(connection.rulePacks.isNotEmpty() && connection.rulePacks.distinct().size == connection.rulePacks.size && connection.rulePacks.all { it in semgrepPacks }) { "Choose at least one bundled rule pack or a trusted custom rules file." }
            }
            if (method.id == "sast.powershell") checkedPath(connection.modulePath, roots, directoryAllowed = true)
        } else {
            require(method.id !in setOf("research.script-semantics", "ai.external-model")) { "Script behavior signals require the installed SDK analyzer." }
            val report = checkedPath(connection.reportPath, roots)
            require(Files.size(report) <= 16L * 1024 * 1024) { "Choose a report smaller than 16 MiB." }
        }
        if (method.id == "dast.zap") {
            val uri = java.net.URI(connection.targetOrigin)
            require(uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
                uri.rawQuery == null && uri.rawFragment == null && uri.path.orEmpty() in setOf("", "/") && uri.port in -1..65535 && uri.port != 0) {
                "Use an exact http(s) origin, for example https://staging.example.com:8443, without a path or query."
            }
            val path = connection.targetPath
            require(path.startsWith('/') && !path.contains("..") && !path.contains('%') && !path.contains('\\') &&
                !path.contains('?') && !path.contains('#') && !path.contains("//")) { "Use a path scope such as / or /payments." }
        }
    }

    fun profile(method: SecurityMethod, connection: MethodConnectionState): JsonObject = buildJsonObject {
        if (connection.projectRoot.isNotBlank()) put("projectRoot", connection.projectRoot)
        put("id", requireNotNull(method.providerId)); put("displayName", method.title); put("version", "0.3.0")
        put("analysisKind", when (method.id) { "sca.trivy", "sca.osv", "sca.cyclonedx" -> "SCA"; "dast.zap" -> "DAST"; "ai.external-model" -> "AI"; else -> "SAST" })
        put("format", requireNotNull(formats[method.id]))
        if (connection.mode == "runner") putJsonObject("runner") {
            put("tool", when (method.id) { "ai.external-model" -> "local-ai"; "sast.semgrep" -> "semgrep"; else -> requireNotNull(formats[method.id]) })
            if (method.id !in setOf("research.script-semantics", "ai.external-model")) put("executable", connection.runnerExecutable)
            if (method.id == "ai.external-model") { put("endpoint", connection.endpoint); put("model", connection.model); put("inputReportPath", connection.reportPath); put("responseFormat", connection.responseFormat) }
            if (method.id == "sast.powershell") put("modulePath", connection.modulePath)
            if (method.id == "sast.semgrep") {
                if (connection.rulesPath.isNotBlank()) put("rulesPath", connection.rulesPath)
                else putJsonArray("rulePacks") { connection.rulePacks.forEach { add(it) } }
            }
            if (method.id == "sca.trivy" && connection.cachePath.isNotBlank()) put("cachePath", connection.cachePath)
            put("timeoutMs", if (method.id in setOf("sca.trivy", "sca.osv")) 120_000 else 30_000); put("maxOutputBytes", 16 * 1024 * 1024)
        } else put("reportPath", connection.reportPath)
        putJsonArray("allowedOperations") { connection.allowedOperations.forEach { add(it) } }
        putJsonArray("requestedActions") { connection.requestedActions.forEach { add(it) } }
        if (method.id == "dast.zap") putJsonObject("targetScope") {
            putJsonArray("origins") { add(connection.targetOrigin.trimEnd('/')) }
            putJsonArray("pathPrefixes") { add(connection.targetPath.ifBlank { "/" }) }
        }
    }

    fun aiInput(report: ScanReport): JsonObject = buildJsonObject {
        put("schemaVersion", 1)
        putJsonArray("findings") { report.findings.forEach { finding -> add(buildJsonObject {
            put("ruleId", finding.ruleId); put("title", CredentialRedaction.mask(finding.title).first)
            put("message", CredentialRedaction.mask(finding.message).first)
            put("severity", finding.severity.name); put("category", finding.category.name)
            putJsonObject("location") { put("path", finding.location.path); finding.location.line?.let { put("line", it) } }
            put("policyAction", finding.policyAction.name); put("status", finding.status.name)
            finding.providerId?.let { put("providerId", it) }; putJsonArray("actions") { }
        }) } }
        putJsonArray("notices") { }
    }

    fun command(method: SecurityMethod, connection: MethodConnectionState, permissions: ProviderPermissions): CommandProviderConfiguration =
        CommandProviderConfiguration(requireNotNull(method.providerId), method.title, "0.3.0",
            listOf(connection.pythonExecutable, "-I", "-m", "wrapperguard_sdk", "--profile", connection.profilePath), permissions, category = method.category.name, summary = method.summary,
            inputScope = ManagedMethodInputScopes.scope(method, connection),
            projectRoots = connection.projectRoot.takeIf(String::isNotBlank)?.let(::listOf).orEmpty())
}

/** Generates profiles in IDE config, without rewriting the user's existing provider file. */
internal object OptionalMethodConnections {
    private val json = Json { prettyPrint = true; encodeDefaults = true }
    fun roots(project: Project): List<Path> = (ProjectManager.getInstance().openProjects.toList() + project)
        .mapNotNull { it.basePath?.let(Path::of) }.distinct()
    fun directory(): Path = Path.of(PathManager.getConfigPath(), "wrapperguard", "integrations")
    fun changed() = ProjectManager.getInstance().openProjects.forEach {
        it.getService(WrapperGuardProjectService::class.java)?.settingsChanged()
    }

    fun setEnabled(method: SecurityMethod, enabled: Boolean) {
        val state = WrapperGuardSettings.getInstance().state
        state.methodFlags[method.id] = enabled
        if (method.providerId != "wrapperguard") {
            val provider = state.providers.firstOrNull { it.id == method.providerId } ?: WrapperGuardSettings.ProviderState(
                id = requireNotNull(method.providerId), actions = mutableListOf(VettingAction.WARNING.name), timeoutMs = hostTimeout(method.id)).also { state.providers.add(it) }
            provider.enabled = enabled
        }
        try { refreshGeneratedConfiguration() } finally { changed() }
    }

    internal fun forgetConnection(state: WrapperGuardSettings.State, method: SecurityMethod) {
        require(method.providerId != "wrapperguard") { "Built-in checks are controlled by their switches." }
        state.methodFlags[method.id] = false
        state.methodConnections.removeAll { it.methodId == method.id }
        state.providers.firstOrNull { it.id == method.providerId }?.enabled = false
    }

    fun disconnect(method: SecurityMethod) {
        forgetConnection(WrapperGuardSettings.getInstance().state, method)
        try { refreshGeneratedConfiguration() } finally { changed() }
    }

    fun save(project: Project, method: SecurityMethod, input: MethodConnectionState) = ManagedStorage.withUse {
        saveConnection(project, method, input)
    }

    private fun saveConnection(project: Project, method: SecurityMethod, input: MethodConnectionState) {
        val roots = roots(project)
        OptionalMethodProfiles.validate(method, input, roots)
        val state = WrapperGuardSettings.getInstance().state
        val connection = input.copy()
        val dir = directory()
        Files.createDirectories(dir)
        require(roots.none { dir.toRealPath().startsWith(it.toRealPath()) }) { "IDE configuration must be outside the inspected project." }
        if (method.id == "sca.trivy" && connection.mode == "runner") {
            val cache = dir.resolve("cache/trivy"); Files.createDirectories(cache)
            require(roots.none { cache.toRealPath().startsWith(it.toRealPath()) })
            connection.cachePath = cache.toRealPath().toString()
        }
        run {
            val temporary = Files.createTempFile(dir, "setup-", ".profile.json")
            try {
                Files.writeString(temporary, json.encodeToString(OptionalMethodProfiles.profile(method, connection)))
                verifySdk(connection.pythonExecutable, temporary, Path.of(requireNotNull(project.basePath)))
                val destination = dir.resolve("${method.id}-${java.util.UUID.randomUUID()}.profile.json")
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
                ManagedStorage.registerCreated(destination, "Method profile")
                connection.profilePath = destination.toString()
                connection.verified = true
            } finally { Files.deleteIfExists(temporary) }
        }
        // Setup retains an existing reviewed grant; a new connection starts disabled and warning-only.
        if (state.providers.none { it.id == method.providerId }) state.providers.add(WrapperGuardSettings.ProviderState(
            id = requireNotNull(method.providerId), enabled = false, actions = mutableListOf(VettingAction.WARNING.name), timeoutMs = hostTimeout(method.id)))
        val updated = state.methodConnections.filterNot { it.methodId == method.id } + connection
        state.methodConnections = updated.toMutableList()
        try { refreshGeneratedConfiguration() } finally { changed() }
    }

    private fun hostTimeout(id: String): Long = if (id in setOf("sca.trivy", "sca.osv")) 150_000 else 45_000

    fun refreshGeneratedConfiguration() {
        val settings = WrapperGuardSettings.getInstance()
        val state = settings.state
        if (state.methodConnections.isEmpty() && state.customProviders.isEmpty() && state.managedProviderConfigurationPath.isBlank()) return
        val dir = directory(); Files.createDirectories(dir)
        val commands = state.methodConnections.mapNotNull { item -> SecurityMethodCatalog.find(item.methodId)?.let { method ->
            val permissions = settings.permissions(requireNotNull(method.providerId))
            OptionalMethodProfiles.command(method, item, if (state.methodFlags[method.id] == false) permissions.copy(enabled = false) else permissions)
        } }
        val customCommands = state.customProviders.filter { it.kind == "command" }.map { it.command(settings.permissions(it.id)) }
        val temporary = Files.createTempFile(dir, "providers-", ".json")
        try {
            Files.writeString(temporary, json.encodeToString(ProviderConfiguration(providers = commands + customCommands)))
            val destination = dir.resolve("optional-providers.json")
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
            state.managedProviderConfigurationPath = destination.toString()
        } finally { Files.deleteIfExists(temporary) }
    }

    internal fun verifySdk(python: String, profile: Path, root: Path) {
        val process = ProcessBuilder(python, "-I", "-m", "wrapperguard_sdk", "--profile", profile.toString(),
            "--plan", "--project", root.toString()).directory(directory().toFile()).redirectErrorStream(true).start()
        val reader = Thread { runCatching { process.inputStream.use { it.readNBytes(65_537) } } }.apply { isDaemon = true; start() }
        try {
            require(process.waitFor(15, TimeUnit.SECONDS)) { "SDK verification timed out. Choose a trusted Python environment with wrapperguard-sdk installed." }
            require(process.exitValue() == 0) { "SDK verification failed. Install the current wrapperguard-sdk into the selected Python environment, then retry. No scanner was started." }
        } finally {
            process.descendants().forEach { it.destroyForcibly() }; process.destroyForcibly(); reader.join(1000)
        }
    }

    fun registrations(root: Path, state: WrapperGuardSettings.State, existingIds: Set<String> = emptySet()): List<ProviderRegistration> = state.methodConnections.mapNotNull { connection ->
        val method = SecurityMethodCatalog.find(connection.methodId) ?: return@mapNotNull null
        val id = method.providerId ?: return@mapNotNull null
        if (id in existingIds) return@mapNotNull null
        if (connection.projectRoot.isNotBlank() && !runCatching { Path.of(connection.projectRoot).toRealPath() == root.toRealPath() }.getOrDefault(false)) {
            val permissions = WrapperGuardSettings.getInstance().permissions(id).copy(enabled = false)
            return@mapNotNull ProviderRegistration(CommandVettingProvider(OptionalMethodProfiles.command(method, connection, permissions)), permissions)
        }
        if (state.methodFlags[method.id] == false || state.providers.firstOrNull { it.id == id }?.enabled != true) return@mapNotNull null
        OptionalMethodProfiles.validate(method, connection, listOf(root))
        val permissions = WrapperGuardSettings.getInstance().permissions(id)
        val command = OptionalMethodProfiles.command(method, connection, permissions)
        command.validate()
        ProviderRegistration(CommandVettingProvider(command), permissions)
    }

    fun updateProfilePermissions(method: SecurityMethod, operations: List<String>, actions: List<String>) = ManagedStorage.withUse {
        writeProfilePermissions(method, operations, actions)
    }

    private fun writeProfilePermissions(method: SecurityMethod, operations: List<String>, actions: List<String>) {
        val state = WrapperGuardSettings.getInstance().state
        val existing = state.methodConnections.firstOrNull { it.methodId == method.id } ?: return
        val advisory = method.id in setOf("research.script-semantics", "ai.external-model")
        val updated = existing.copy(allowedOperations = (if (advisory) listOf("SCAN") else operations).toMutableList(),
            requestedActions = (if (advisory) listOf("WARNING") else actions).toMutableList())
        val path = directory().resolve("${method.id}-${java.util.UUID.randomUUID()}.profile.json")
        Files.writeString(path, json.encodeToString(OptionalMethodProfiles.profile(method, updated)))
        ManagedStorage.registerCreated(path, "Method profile")
        updated.profilePath = path.toString()
        state.methodConnections = (state.methodConnections.filterNot { it.methodId == method.id } + updated).toMutableList()
    }

    /** Hash settings plus bounded profile/report/config bytes for the before/after execution seal. */
    fun inputSeal(state: WrapperGuardSettings.State): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(value: String) { digest.update(value.toByteArray(Charsets.UTF_8)); digest.update(0.toByte()) }
        add(state.methodFlags.toSortedMap().toString())
        state.methodConnections.sortedBy { it.methodId }.forEach { connection ->
            add(connection.toString())
            listOf(connection.profilePath to 65_536L, connection.configurationPath to 65_536L,
                connection.reportPath to 16L * 1024 * 1024).filter { it.first.isNotBlank() }.forEach { (value, limit) ->
                add(value)
                val path = Path.of(value)
                if (Files.isRegularFile(path) && Files.size(path) <= limit) Files.newInputStream(path).use { input ->
                    val buffer = ByteArray(8192); var total = 0L
                    while (true) { val read = input.read(buffer); if (read < 0) break; total += read
                        require(total <= limit) { "Connection input grew beyond its limit" }; digest.update(buffer, 0, read) }
                } else add("missing-or-over-limit")
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
