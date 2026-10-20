package com.sal7one.wrapperguard.core.methods

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.security.MessageDigest

/** Portable selections only. Integrity is a digest, not publisher authentication. */
@Serializable
data class OrganizationPreset(
    val schemaVersion: Int = 1,
    val id: String,
    val title: String,
    val version: String,
    val issuer: String,
    val createdAt: Long,
    val methodFlags: Map<String, Boolean>,
    val digest: String
)

object OrganizationPresets {
    private val json = Json { encodeDefaults = true }
    private val fields = setOf("schemaVersion", "id", "title", "version", "issuer", "createdAt", "methodFlags", "digest")

    /** Same encoding as the VS Code host; no locale, floating point or key-order dependence. */
    fun canonical(value: JsonElement): String = when (value) {
        is JsonObject -> value.keys.sorted().joinToString(",", "{", "}") { json.encodeToString(it) + ":" + canonical(value.getValue(it)) }
        is JsonArray -> value.joinToString(",", "[", "]") { canonical(it) }
        else -> value.toString()
    }

    fun digest(preset: OrganizationPreset): String {
        val payload = JsonObject(json.encodeToJsonElement(preset).jsonObject.filterKeys { it != "digest" })
        return MessageDigest.getInstance("SHA-256").digest(canonical(payload).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun create(id: String, title: String, version: String, issuer: String,
               methodFlags: Map<String, Boolean>, createdAt: Long = System.currentTimeMillis()): OrganizationPreset {
        val candidate = OrganizationPreset(id = id, title = title, version = version, issuer = issuer,
            createdAt = createdAt, methodFlags = methodFlags.toMap(), digest = "")
        return validate(candidate.copy(digest = digest(candidate)))
    }

    fun validate(preset: OrganizationPreset): OrganizationPreset {
        require(preset.schemaVersion == 1 && preset.id.matches(Regex("[a-z][a-z0-9._-]{0,79}"))) { "Preset identity is invalid." }
        require(listOf(preset.title, preset.issuer).all { it.isNotBlank() && it.length <= 160 && it.none { ch -> ch.code < 32 || ch.code == 127 } }) { "Preset title or issuer is invalid." }
        require(preset.version.length <= 80 && preset.version.matches(Regex("\\d+\\.\\d+\\.\\d+(?:-[A-Za-z0-9.-]+)?"))) { "Choose a semantic preset version." }
        require(preset.createdAt in 0..9_007_199_254_740_991L) { "Preset creation time is invalid." }
        require(preset.methodFlags.size in 1..128 && preset.methodFlags.keys.all { SecurityMethodCatalog.find(it) != null }) { "Preset methods must exist in this installation." }
        require(preset.digest.matches(Regex("[0-9a-f]{64}")) && digest(preset) == preset.digest) { "Preset digest does not match its contents." }
        return preset.copy(methodFlags = preset.methodFlags.toMap())
    }

    fun decode(source: String): OrganizationPreset {
        require(source.toByteArray(Charsets.UTF_8).size <= 65536) { "Preset exceeds 64 KiB." }
        val document = json.parseToJsonElement(source).jsonObject
        require(document.keys == fields) { "Commands, permissions and unknown preset fields are not accepted." }
        return validate(json.decodeFromJsonElement<OrganizationPreset>(document))
    }

    fun encode(preset: OrganizationPreset): String = json.encodeToString(validate(preset)) + "\n"

    fun read(file: Path): OrganizationPreset {
        require(file.isAbsolute) { "Choose an absolute preset file." }
        val anchored = file.parent.toRealPath().resolve(file.fileName)
        require(Files.isRegularFile(anchored, NOFOLLOW_LINKS)) { "Choose a regular preset file." }
        val source = Files.newByteChannel(anchored, setOf(READ, NOFOLLOW_LINKS)).use { channel ->
            require(channel.size() <= 65536) { "Preset exceeds 64 KiB." }
            val buffer = ByteBuffer.allocate(65537)
            while (buffer.hasRemaining() && channel.read(buffer) != -1) { }
            require(buffer.position() <= 65536) { "Preset exceeds 64 KiB." }
            buffer.flip()
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(buffer).toString()
        }
        return decode(source)
    }
}
