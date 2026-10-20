package com.sal7one.wrapperguard.core.vetting

import com.sal7one.wrapperguard.core.spi.ScannerEngine
import com.sal7one.wrapperguard.core.inputs.ProviderInputScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Explicit, user-owned configuration. Never discover this file in a scanned repository. */
@Serializable
data class ProviderConfiguration(
    val apiVersion: Int = 1,
    val builtIn: ProviderPermissions = BuiltInVettingProvider.DEFAULT_PERMISSIONS,
    val providers: List<CommandProviderConfiguration> = emptyList()
) {
    @JvmOverloads fun registrations(engine: ScannerEngine, projectRoot: Path? = null): List<ProviderRegistration> {
        validateMetadata()
        return listOf(ProviderRegistration(BuiltInVettingProvider(engine), builtIn)) +
            providers.map { ProviderRegistration(CommandVettingProvider(it),
                if (projectRoot != null && !it.appliesToProject(projectRoot)) it.permissions.copy(enabled = false) else it.permissions) }
    }

    @JvmOverloads fun validate(projectRoot: Path? = null) {
        validateConfiguration(projectRoot, requireExecutables = true)
    }

    /** Repair/loading validates trust and shape without requiring installed tools to exist. */
    fun validateMetadata() {
        validateConfiguration(null, requireExecutables = false)
    }

    private fun validateConfiguration(projectRoot: Path?, requireExecutables: Boolean) {
        require(apiVersion == 1) { "Unsupported provider configuration API version." }
        require(providers.size <= 32) { "At most 32 command providers may be configured." }
        validatePermissions(builtIn)
        val ids = mutableSetOf("wrapperguard")
        providers.forEach {
            it.validate(requireExecutable = requireExecutables && (projectRoot == null || it.appliesToProject(projectRoot)))
            require(ids.add(it.id)) { "Provider IDs must be unique and cannot use the built-in ID." }
        }
    }

    companion object {
        private const val MAX_CONFIGURATION_BYTES = 65_536L
        private val JSON = Json { ignoreUnknownKeys = false }

        /** Resolve symlinks before checking the trust boundary, not just the lexical path. */
        @JvmOverloads fun load(path: Path, projectRoot: Path, projectRoots: List<Path> = listOf(projectRoot)): ProviderConfiguration {
            require(path.isAbsolute) { "Provider configuration must use an absolute path." }
            val roots = (projectRoots + listOf(projectRoot)).map { it.toRealPath() }.distinct()
            val file = path.toRealPath()
            require(roots.none { file.startsWith(it) }) { "Provider configuration must be outside every project." }
            require(Files.isRegularFile(file)) { "Provider configuration must be a regular file." }
            require(Files.size(file) <= MAX_CONFIGURATION_BYTES) { "Provider configuration is too large." }
            val bytes = Files.newInputStream(file).use { it.readNBytes((MAX_CONFIGURATION_BYTES + 1).toInt()) }
            require(bytes.size <= MAX_CONFIGURATION_BYTES) { "Provider configuration is too large." }
            return try {
                val content = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
                JSON.decodeFromString<ProviderConfiguration>(content).also { it.validateMetadata() }
            } catch (_: kotlinx.serialization.SerializationException) {
                // Parser exceptions can contain the user's command arguments. Keep them private.
                throw IllegalArgumentException("Invalid provider configuration JSON.")
            }
        }

        internal fun validatePermissions(permissions: ProviderPermissions) {
            require(permissions.timeoutMs in 100..300_000) { "Provider timeout must be between 100 and 300000 milliseconds." }
        }
    }
}

@Serializable
data class CommandProviderConfiguration(
    val id: String,
    val displayName: String,
    val version: String,
    val command: List<String>,
    val permissions: ProviderPermissions = ProviderPermissions(enabled = false),
    val category: String? = null,
    val summary: String? = null,
    val tags: List<String> = emptyList(),
    val inputScope: ProviderInputScope? = null,
    val projectRoots: List<String> = emptyList()
) {
    /** Preserve the pre-metadata Kotlin constructor/default-argument JVM entry points. */
    constructor(id: String, displayName: String, version: String, command: List<String>,
        permissions: ProviderPermissions = ProviderPermissions(enabled = false)
    ) : this(id, displayName, version, command, permissions, null, null, emptyList(), null, emptyList())

    /** Legacy copies retain the additive metadata and project binding. */
    fun copy(id: String = this.id, displayName: String = this.displayName, version: String = this.version,
        command: List<String> = this.command, permissions: ProviderPermissions = this.permissions
    ) = CommandProviderConfiguration(id, displayName, version, command, permissions, category, summary, tags, inputScope, projectRoots)

    fun descriptor() = ProviderDescriptor(id, displayName, version, category = category, summary = summary,
        tags = tags.toList(), inputScope = inputScope?.snapshot())

    /** Empty retains the API 1 all-project behavior. Missing bound roots never match. */
    fun appliesToProject(projectRoot: Path): Boolean {
        if (projectRoots.isEmpty()) return true
        val selected = runCatching { projectRoot.toRealPath() }.getOrNull() ?: return false
        return projectRoots.any { value -> runCatching {
            val bound = Path.of(value).toRealPath()
            Files.isDirectory(bound) && bound == selected
        }.getOrDefault(false) }
    }

    @JvmOverloads fun validate(requireExecutable: Boolean = true) {
        require(id.matches(Regex("[a-z][a-z0-9._-]{0,79}")) && id != "wrapperguard") { "Invalid command provider ID." }
        require(displayName.isNotBlank() && displayName.length <= 160 && displayName.none { it.isISOControl() }) { "Invalid provider display name." }
        require(version.isNotBlank() && version.length <= 80 && version.none { it.isISOControl() }) { "Invalid provider version." }
        require(command.isNotEmpty() && command.size <= 128 && command.all { it.length <= 32_768 && '\u0000' !in it }) { "Invalid provider command arguments." }
        val executable = Path.of(command.first())
        require(executable.isAbsolute && (!requireExecutable || Files.isRegularFile(executable) && Files.isExecutable(executable))) {
            "Provider executable must be an absolute path to an executable file."
        }
        ProviderConfiguration.validatePermissions(permissions)
        descriptor().validateMetadata()
        require(projectRoots.size <= 32 && projectRoots.distinct().size == projectRoots.size && projectRoots.all {
            it.isNotBlank() && it.length <= 4096 && it.none(Char::isISOControl) && Path.of(it).isAbsolute
        }) { "Provider project bindings must be unique absolute project directories." }
    }
}
