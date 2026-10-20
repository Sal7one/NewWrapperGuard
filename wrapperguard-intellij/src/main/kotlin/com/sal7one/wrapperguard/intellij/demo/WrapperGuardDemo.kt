package com.sal7one.wrapperguard.intellij.demo

import com.sal7one.wrapperguard.core.model.ScanNotice
import com.sal7one.wrapperguard.core.model.ScanReport
import com.sal7one.wrapperguard.core.policy.ScanSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.nio.file.Files
import java.nio.file.Path

/** Explicit development-only presentation mode. Simulated reports never authorize execution. */
internal object WrapperGuardDemo {
    const val ENABLED_PROPERTY = "wrapperguard.demo"
    const val REPORT_PROPERTY = "wrapperguard.demoReport"
    const val TAB_PROPERTY = "wrapperguard.demoTab"
    const val CONTROL_PROPERTY = "wrapperguard.demoControl"
    const val WINDOW_PROPERTY = "wrapperguard.demoWindow"
    private const val MAX_REPORT_BYTES = 4 * 1_024 * 1_024
    private val json = Json { ignoreUnknownKeys = true }

    val enabled: Boolean get() = System.getProperty(ENABLED_PROPERTY) == "true"
    val projectName: String get() = "Atlas Payments"

    fun loadReport(): ScanReport {
        check(enabled) { "Demo mode is not enabled." }
        val path = Path.of(requireNotNull(System.getProperty(REPORT_PROPERTY)))
        require(path.isAbsolute && Files.isRegularFile(path)) { "Demo report must be an absolute regular-file path." }
        val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_REPORT_BYTES + 1) }
        require(bytes.size <= MAX_REPORT_BYTES) { "Demo report exceeds the size limit." }
        val report = json.decodeFromString<ScanReport>(bytes.toString(Charsets.UTF_8))
        return report.copy(stale = false, notices = report.notices.filterNot { it.code == "WG-DEMO" } +
            ScanNotice("WG-DEMO", "DEMO • simulated data. This report is for UI demonstration; execution is disabled."))
    }

    /** Display-only fixture; never persisted or used to authorize scanning or execution. */
    fun loadDisplaySettings(): ScanSettings? {
        if (!enabled) return null
        return runCatching {
            val reportPath = Path.of(System.getProperty(REPORT_PROPERTY) ?: return null)
            if (!reportPath.isAbsolute) return null
            val settingsPath = reportPath.resolveSibling("settings.json")
            if (!Files.isRegularFile(settingsPath)) return null
            val bytes = Files.newInputStream(settingsPath).use { it.readNBytes(65_537) }
            if (bytes.size > 65_536) return null
            val fields = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject ?: return null
            val defaults = ScanSettings()
            fun strings(key: String, fallback: List<String>): List<String> {
                val values = fields[key] as? JsonArray ?: return fallback
                return values.map { value ->
                    val primitive = value as? JsonPrimitive ?: error("Expected a string in $key")
                    require(primitive.isString) { "Expected a string in $key" }
                    primitive.content
                }
            }
            fun primitive(key: String): JsonPrimitive? = fields[key] as? JsonPrimitive
            val checksums = (fields["trustedWrapperChecksums"] as? JsonObject)?.mapValues { (_, value) ->
                val primitive = value as? JsonPrimitive ?: error("Expected a checksum string")
                require(primitive.isString) { "Expected a checksum string" }
                primitive.content
            } ?: defaults.trustedWrapperChecksums
            defaults.copy(
                allowedHosts = strings("allowedHosts", defaults.allowedHosts.toList()).toSet(),
                allowedUrlPatterns = strings("allowedUrlPatterns", defaults.allowedUrlPatterns),
                blockedCommands = strings("blockedCommands", defaults.blockedCommands.toList()).toSet(),
                maxScriptDepth = primitive("maxScriptDepth")?.intOrNull ?: defaults.maxScriptDepth,
                maxFileBytes = primitive("maxFileBytes")?.longOrNull ?: defaults.maxFileBytes,
                requireDistributionSha256 = primitive("requireDistributionSha256")?.booleanOrNull ?: defaults.requireDistributionSha256,
                enableRecursiveScanning = primitive("enableRecursiveScanning")?.booleanOrNull ?: defaults.enableRecursiveScanning,
                knownSafeRepositoryHosts = strings("knownSafeRepositoryHosts", defaults.knownSafeRepositoryHosts.toList()).toSet(),
                trustedWrapperChecksums = checksums,
                maxLineLength = primitive("maxLineLength")?.intOrNull ?: defaults.maxLineLength,
                maxFilesPerScan = primitive("maxFilesPerScan")?.intOrNull ?: defaults.maxFilesPerScan,
                maxDirectoryDepth = primitive("maxDirectoryDepth")?.intOrNull ?: defaults.maxDirectoryDepth,
            )
        }.getOrNull()
    }

    /** A tiny opt-in presentation control file lets screenshot tooling switch the real tabs. */
    fun requestedTab(): String? {
        if (!enabled) return null
        val text = System.getProperty(CONTROL_PROPERTY)?.let { rawPath ->
            try {
                val path = Path.of(rawPath)
                if (!path.isAbsolute || !Files.isRegularFile(path)) return null
                val bytes = Files.newInputStream(path).use { it.readNBytes(65) }
                if (bytes.size > 64) return null
                bytes.toString(Charsets.UTF_8)
            } catch (_: Exception) { return null }
        } ?: return null
        return text.trim().lowercase().takeIf { it in TABS }
    }

    fun initialTab(): String? = if (enabled) System.getProperty(TAB_PROPERTY)?.trim()?.lowercase()?.takeIf { it in TABS } else null

    fun writePid() {
        if (!enabled) return
        val control = System.getProperty(CONTROL_PROPERTY) ?: return
        try {
            val path = Path.of("$control.pid")
            if (path.isAbsolute) Files.writeString(path, ProcessHandle.current().pid().toString())
        } catch (_: Exception) { /* Optional screenshot metadata; no execution authority. */ }
    }

    private val TABS = setOf("summary", "overview", "findings", "execution", "graph", "policy", "tools", "research")
}
