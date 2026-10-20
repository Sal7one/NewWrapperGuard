package com.sal7one.wrapperguard.core.methods

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** Explicit user preferences. Never auto-discover repository-shipped disabling policy. */
@Serializable
data class MethodConfiguration(val methodFlags: Map<String, Boolean> = emptyMap()) {
    fun validate() {
        require(methodFlags.size <= 256 && methodFlags.keys.all { SecurityMethodCatalog.find(it) != null }) {
            "Method configuration contains an unknown method."
        }
    }

    companion object {
        private const val MAX_BYTES = 65_536
        fun load(path: Path, projectRoot: Path): MethodConfiguration {
            require(path.isAbsolute) { "Method configuration must use an absolute path." }
            val file = path.toRealPath()
            require(!file.startsWith(projectRoot.toRealPath())) { "Method configuration must be outside the project." }
            require(Files.isRegularFile(file) && Files.size(file) <= MAX_BYTES)
            val bytes = Files.newInputStream(file).use { it.readNBytes(MAX_BYTES + 1) }
            require(bytes.size <= MAX_BYTES)
            val text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            return try {
                val document = Json.parseToJsonElement(text).jsonObject
                document["methodFlags"]?.jsonObject?.values?.forEach { value ->
                    require(value is JsonPrimitive && !value.isString && value.booleanOrNull != null) { "Method flags must be JSON booleans." }
                }
                Json.decodeFromString<MethodConfiguration>(text).also { it.validate() }
            } catch (_: kotlinx.serialization.SerializationException) {
                throw IllegalArgumentException("Invalid method configuration JSON.")
            }
        }
    }
}
