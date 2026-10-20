package com.sal7one.wrapperguard.core.methods

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Data-only method selections: a preset never installs tools or grants provider actions. */
@Serializable
data class SecurityPreset(
    val id: String,
    val title: String,
    val summary: String,
    val methodFlags: Map<String, Boolean>,
    val notice: String
) {
    fun applyTo(current: Map<String, Boolean>): Map<String, Boolean> = current + methodFlags
}

object SecurityPresets {
    @Serializable private data class Document(val schemaVersion: Int, val presets: List<SecurityPreset>)
    val presets: List<SecurityPreset> by lazy {
        val source = checkNotNull(javaClass.getResourceAsStream("/security-presets.json")) {
            "Security method presets are missing."
        }.use { it.readBytes().toString(Charsets.UTF_8) }
        val document = Json.decodeFromString<Document>(source)
        require(document.schemaVersion == 1 && document.presets.size <= 32)
        require(document.presets.map { it.id }.distinct().size == document.presets.size)
        document.presets.forEach { preset ->
            require(preset.id.matches(Regex("[a-z][a-z0-9-]{0,63}")))
            require(preset.title.isNotBlank() && preset.summary.isNotBlank())
            require(preset.methodFlags.keys.all { SecurityMethodCatalog.find(it) != null })
        }
        document.presets
    }
}
