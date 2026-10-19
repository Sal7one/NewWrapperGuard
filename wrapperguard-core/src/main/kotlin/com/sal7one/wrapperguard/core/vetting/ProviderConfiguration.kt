package com.sal7one.wrapperguard.core.vetting

import com.sal7one.wrapperguard.core.spi.ScannerEngine
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
    fun registrations(engine: ScannerEngine): List<ProviderRegistration> {
        validate()
        return listOf(ProviderRegistration(BuiltInVettingProvider(engine), builtIn)) +
            providers.map { ProviderRegistration(CommandVettingProvider(it), it.permissions) }
    }

    fun validate() {
        require(apiVersion == 1) { "Unsupported provider configuration API version." }
        require(providers.size <= 32) { "At most 32 command providers may be configured." }
        validatePermissions(builtIn)
        val ids = mutableSetOf("wrapperguard")
        providers.forEach {
            it.validate()
            require(ids.add(it.id)) { "Provider IDs must be unique and cannot use the built-in ID." }
        }
    }

    companion object {
        private const val MAX_CONFIGURATION_BYTES = 65_536L
        private val JSON = Json { ignoreUnknownKeys = false }

        /** Resolve symlinks before checking the trust boundary, not just the lexical path. */
        fun load(path: Path, projectRoot: Path): ProviderConfiguration {
            require(path.isAbsolute) { "Provider configuration must use an absolute path." }
            val root = projectRoot.toRealPath()
            val file = path.toRealPath()
            require(!file.startsWith(root)) { "Provider configuration must be outside the project." }
            require(Files.isRegularFile(file)) { "Provider configuration must be a regular file." }
            require(Files.size(file) <= MAX_CONFIGURATION_BYTES) { "Provider configuration is too large." }
            val bytes = Files.newInputStream(file).use { it.readNBytes((MAX_CONFIGURATION_BYTES + 1).toInt()) }
            require(bytes.size <= MAX_CONFIGURATION_BYTES) { "Provider configuration is too large." }
            return try {
                val content = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
                JSON.decodeFromString<ProviderConfiguration>(content).also { it.validate() }
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
    val permissions: ProviderPermissions = ProviderPermissions(enabled = false)
) {
    fun validate() {
        require(id.matches(Regex("[a-z][a-z0-9._-]{0,79}")) && id != "wrapperguard") { "Invalid command provider ID." }
        require(displayName.isNotBlank() && displayName.length <= 160 && displayName.none { it.isISOControl() }) { "Invalid provider display name." }
        require(version.isNotBlank() && version.length <= 80 && version.none { it.isISOControl() }) { "Invalid provider version." }
        require(command.isNotEmpty() && command.size <= 128 && command.all { it.length <= 32_768 && '\u0000' !in it }) { "Invalid provider command arguments." }
        val executable = Path.of(command.first())
        require(executable.isAbsolute && Files.isRegularFile(executable) && Files.isExecutable(executable)) {
            "Provider executable must be an absolute path to an executable file."
        }
        ProviderConfiguration.validatePermissions(permissions)
    }
}
