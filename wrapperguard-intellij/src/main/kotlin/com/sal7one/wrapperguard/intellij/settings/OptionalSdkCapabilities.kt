package com.sal7one.wrapperguard.intellij.settings

import com.intellij.openapi.progress.ProgressManager
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Passive SDK metadata only: never reads a project/profile or starts an analyzer. */
internal data class OptionalSdkCapability(val version: String, val runners: Set<String>, val sandboxTools: Set<String> = emptySet()) {
    fun requireCurrent() {
        val release = version.substringBefore('-').split('.').map { it.toIntOrNull() ?: -1 }
        require(version.length <= 80 && version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?")) && release.size == 3 && release.all { it >= 0 } &&
            (release[0] > 0 || release[1] >= 4)) { "New guided connections require WrapperGuard SDK 0.4.0 or newer. Use Install / repair SDK; existing connections retain their previous runtime." }
    }
}

internal object OptionalSdkCapabilities {
    fun decode(text: String): OptionalSdkCapability {
        require(text.toByteArray().size <= 65_536) { "SDK capability output exceeds its limit." }
        val value = Json.parseToJsonElement(text).jsonObject
        require(value["providerApiVersion"]?.jsonPrimitive?.intOrNull == 1) { "The selected SDK does not support provider API 1." }
        val runners = value.getValue("runners").jsonArray
        require(runners.size <= 32 && runners.all { it.jsonObject["tool"]?.jsonPrimitive?.content?.matches(Regex("[a-z][a-z0-9._-]{0,63}")) == true }) { "SDK runner list exceeds its limit." }
        return OptionalSdkCapability(value.getValue("sdkVersion").jsonPrimitive.content,
            runners.filter { it.jsonObject["supported"]?.jsonPrimitive?.booleanOrNull == true }
                .map { it.jsonObject.getValue("tool").jsonPrimitive.content }.toSet(),
            value["sandboxBackends"]?.jsonArray.orEmpty().filter { it.jsonObject["engine"]?.jsonPrimitive?.content == "docker" && it.jsonObject["supported"]?.jsonPrimitive?.booleanOrNull == true }
                .flatMap { it.jsonObject["tools"]?.jsonArray.orEmpty() }.map { it.jsonPrimitive.content }.toSet().also { require(it.size <= 32) })
    }

    fun probe(python: String): OptionalSdkCapability {
        val directory = OptionalMethodConnections.directory(); Files.createDirectories(directory)
        val process = ProcessBuilder(python, "-I", "-m", "wrapperguard_sdk", "--capabilities")
            .directory(directory.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val output = AtomicReference<ByteArray>()
        val failure = AtomicReference<Throwable>()
        val reader = Thread { try { output.set(process.inputStream.use { it.readNBytes(65_537) }) } catch (e: Throwable) { failure.set(e) } }
            .apply { isDaemon = true; start() }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                ProgressManager.checkCanceled()
                require(System.nanoTime() < deadline) { "SDK capability discovery timed out. Choose an installed trusted Python environment." }
            }
            reader.join(1000)
            require(process.exitValue() == 0 && failure.get() == null && !reader.isAlive) { "SDK discovery failed. Install / repair the SDK in the selected trusted environment." }
            val bytes = requireNotNull(output.get())
            require(bytes.size <= 65_536) { "SDK capability output exceeds its limit." }
            return decode(bytes.toString(Charsets.UTF_8))
        } finally {
            process.descendants().forEach { it.destroyForcibly() }; process.destroyForcibly(); reader.join(1000)
        }
    }
}
