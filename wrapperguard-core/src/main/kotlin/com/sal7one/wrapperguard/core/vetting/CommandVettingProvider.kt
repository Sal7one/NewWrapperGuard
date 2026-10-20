package com.sal7one.wrapperguard.core.vetting

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Bridge for an explicitly configured local SDK adapter or script. The executable is
 * trusted software with the user's privileges; this bridge does not sandbox its code.
 * An argv array is passed directly to ProcessBuilder, with no implicit shell expansion.
 * System launchers isolate a process group on supported POSIX hosts so children remain
 * terminable after their parent exits. Other hosts use observed descendants as a best-effort
 * fallback. Providers must not detach descendants into independent sessions/process groups.
 */
class CommandVettingProvider(configuration: CommandProviderConfiguration) : VettingProvider {
    private val configuration = configuration.copy(command = configuration.command.toList(),
        permissions = configuration.permissions.copy(actions = configuration.permissions.actions.toSet()),
        tags = configuration.tags.toList(), inputScope = configuration.inputScope?.snapshot(), projectRoots = configuration.projectRoots.toList())
    init { configuration.validate(requireExecutable = false) }

    override val descriptor = this.configuration.descriptor()
    fun appliesToProject(projectRoot: Path): Boolean = configuration.appliesToProject(projectRoot)
    internal fun validateReadiness() = configuration.validate()

    override fun scan(request: VettingRequest): ProviderResult {
        val root = request.projectRoot.toRealPath()
        val input = JSON.encodeToString(buildJsonObject {
            put("apiVersion", 1)
            put("projectPath", root.toString())
            put("operation", request.operation.name)
        }) + "\n"
        return runAdapter(root, input)
    }

    internal fun runAdapter(root: Path, input: String): ProviderResult {
        require(appliesToProject(root)) { "Provider is not enabled for the selected project." }
        validateReadiness()
        val launch = launchCommand(configuration.command)
        val process = ProcessBuilder(launch.arguments)
            .directory(root.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(configuration.permissions.timeoutMs)
        val descendants = mutableSetOf<ProcessHandle>()
        val io = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "wrapperguard-provider-io").apply { isDaemon = true }
        }
        val output = io.submit<ByteArray> {
            process.inputStream.use { stream ->
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    require(bytes.size() + count <= MAX_OUTPUT_BYTES) { "Provider output exceeds the allowed size." }
                    bytes.write(buffer, 0, count)
                }
                bytes.toByteArray()
            }
        }
        val delivery = io.submit {
            try {
                process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(input) }
            } catch (_: IOException) {
                // A fast adapter may close stdin without consuming the request. A broken
                // request pipe alone does not invalidate its answer: still require a timely
                // zero exit and bounded, strictly decoded result below before accepting it.
            }
        }
        try {
            while (true) {
                process.descendants().use { handles -> handles.forEach { descendants.add(it) } }
                if (output.isDone) output.get() // propagate output limits immediately
                if (delivery.isDone) delivery.get()
                if (process.waitFor(25, TimeUnit.MILLISECONDS)) break
                if (System.nanoTime() >= deadline) throw TimeoutException("Provider process timed out.")
            }
            require(process.exitValue() == 0) { "Provider process exited unsuccessfully." }
            var remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw TimeoutException("Provider process timed out.")
            delivery.get(remaining, TimeUnit.NANOSECONDS)
            remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw TimeoutException("Provider process timed out.")
            val bytes = output.get(remaining, TimeUnit.NANOSECONDS)
            val text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            return try {
                JSON.decodeFromString<ProviderResult>(text)
            } catch (_: kotlinx.serialization.SerializationException) {
                throw IllegalArgumentException("Provider returned invalid result JSON.")
            }
        } finally {
            // A process group survives its leader, covering children orphaned before the
            // first ProcessHandle descendant poll. This is lifecycle cleanup, not isolation
            // of filesystem, environment, network or other provider privileges.
            launch.groupKiller?.let { killGroup(it, process.pid()) }
            // Keep handles captured before the parent exits so its children can be stopped too.
            process.descendants().use { handles -> handles.forEach { descendants.add(it) } }
            descendants.filter { it.isAlive }.forEach { it.destroy() }
            if (process.isAlive) process.destroy()
            descendants.filter { it.isAlive }.forEach { it.destroyForcibly() }
            if (process.isAlive) process.destroyForcibly()
            output.cancel(true)
            delivery.cancel(true)
            runCatching { process.inputStream.close() }
            runCatching { process.outputStream.close() }
            io.shutdownNow()
        }
    }

    companion object {
        const val MAX_OUTPUT_BYTES: Int = 4 * 1024 * 1024
        private val JSON = Json { ignoreUnknownKeys = false }

        private data class Launch(val arguments: List<String>, val groupKiller: Path? = null)

        private fun executable(vararg paths: String): Path? = paths.asSequence().map { Path.of(it) }
            .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }

        private fun launchCommand(command: List<String>): Launch {
            val killer = executable("/bin/kill", "/usr/bin/kill") ?: return Launch(command)
            val os = System.getProperty("os.name").lowercase(java.util.Locale.ROOT)
            if (os.contains("linux")) {
                val setsid = executable("/usr/bin/setsid", "/bin/setsid")
                if (setsid != null) return Launch(listOf(setsid.toString()) + command, killer)
            }
            if (os.contains("mac") || os.contains("darwin")) {
                val perl = executable("/usr/bin/perl")
                if (perl != null) return Launch(listOf(
                    perl.toString(), "-MPOSIX", "-e",
                    "my \$sid = POSIX::setsid(); defined(\$sid) && \$sid >= 0 or die 'Could not create provider process group'; " +
                        "exec { \$ARGV[0] } @ARGV; exit 127;",
                    "--"
                ) + command, killer)
            }
            return Launch(command)
        }

        private fun killGroup(executable: Path, pid: Long) {
            val killer = runCatching {
                ProcessBuilder(executable.toString(), "-KILL", "--", "-$pid")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            }.getOrNull() ?: return
            var interrupted = false
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500)
            try {
                while (killer.isAlive) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) break
                    try { killer.waitFor(remaining, TimeUnit.NANOSECONDS) }
                    catch (_: InterruptedException) { interrupted = true }
                }
            } finally {
                if (killer.isAlive) killer.destroyForcibly()
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }
}
