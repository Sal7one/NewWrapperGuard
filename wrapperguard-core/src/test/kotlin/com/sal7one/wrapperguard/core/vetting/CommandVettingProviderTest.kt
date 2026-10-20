package com.sal7one.wrapperguard.core.vetting

import com.sal7one.wrapperguard.core.policy.ScanPolicy
import com.sal7one.wrapperguard.core.policy.ScanSettings
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeoutException
import kotlin.test.*

class CommandVettingProviderTest {
    private val root = Files.createTempDirectory("wg-command-project-")
    private val shell = Path.of("/bin/sh")

    @AfterTest fun cleanup() { root.toFile().deleteRecursively() }

    private fun provider(script: String, timeout: Long = 5_000): CommandVettingProvider = CommandVettingProvider(
        CommandProviderConfiguration("example.sdk", "Example SDK", "1", listOf(shell.toString(), "-c", script),
            ProviderPermissions(enabled = true, timeoutMs = timeout))
    )

    @Test fun `command receives one JSON request without shell expanding project paths`() {
        val result = provider("""
            IFS= read -r request
            case "${'$'}request" in *'"operation":"SYNC"'*) ;; *) exit 5;; esac
            printf '%s' '{"findings":[],"scannedFiles":3,"notices":[{"code":"SDK-OK","message":"Adapter finished"}]}'
        """.trimIndent()).scan(VettingRequest(root, ScanPolicy(), ScanSettings(), operation = VettingOperation.SYNC))
        assertEquals(3, result.scannedFiles)
        assertEquals("SDK-OK", result.notices.single().code)
    }

    @Test fun `nonzero exit and malformed stdout cannot become successful empty results`() {
        assertFails { provider("printf '%s' '{\"findings\":[]}'; exit 2").scan(VettingRequest(root, ScanPolicy(), ScanSettings())) }
        val error = assertFails { provider("printf '%s' 'token=do-not-leak invalid-json'").scan(VettingRequest(root, ScanPolicy(), ScanSettings())) }
        assertFalse(error.message.orEmpty().contains("do-not-leak"))
    }

    @Test fun `SDK adapters need no host owned fingerprint or approval fields`() {
        val payload = """{"findings":[{"ruleId":"SDK-001","title":"Vendor check","message":"Review the vendor result","severity":"WARN","confidence":"HIGH","category":"CONFIGURATION","location":{"path":"."}}]}"""
        val result = provider("printf '%s' '$payload'").scan(VettingRequest(root, ScanPolicy(), ScanSettings()))
        assertEquals("", result.findings.single().fingerprint)
        assertEquals(com.sal7one.wrapperguard.core.model.FindingStatus.ACTIVE, result.findings.single().status)
    }

    @Test fun `adapter may close stdin early but still must return valid successful bounded output`() {
        // Repeat the short-lived adapter so both sides of the request write/exit race run.
        repeat(25) {
            val result = provider("exec 0<&-; printf '%s' '{\"findings\":[],\"scannedFiles\":2}'")
                .scan(VettingRequest(root, ScanPolicy(), ScanSettings()))
            assertEquals(2, result.scannedFiles)
        }
        // Larger than an OS pipe, so early close necessarily interrupts request delivery.
        val earlyExit = provider("exec 0<&-; printf '%s' '{\"findings\":[],\"scannedFiles\":2}'")
            .runAdapter(root, "{\"projectPath\":\"" + "x".repeat(1024 * 1024) + "\"}\n")
        assertEquals(2, earlyExit.scannedFiles)
        val nonzero = assertFailsWith<IllegalArgumentException> {
            provider("exec 0<&-; printf '%s' '{\"findings\":[]}'; exit 2")
                .scan(VettingRequest(root, ScanPolicy(), ScanSettings()))
        }
        assertEquals("Provider process exited unsuccessfully.", nonzero.message)
        val malformed = assertFailsWith<IllegalArgumentException> {
            provider("exec 0<&-; printf '%s' 'token=do-not-leak invalid-json'")
                .scan(VettingRequest(root, ScanPolicy(), ScanSettings()))
        }
        assertEquals("Provider returned invalid result JSON.", malformed.message)
        assertFails {
            provider("exec 0<&-; head -c 5000000 /dev/zero")
                .scan(VettingRequest(root, ScanPolicy(), ScanSettings()))
        }
        assertFailsWith<TimeoutException> {
            provider("exec 0<&-; exec sleep 10", 150)
                .scan(VettingRequest(root, ScanPolicy(), ScanSettings()))
        }
    }

    @Test fun `oversized output is bounded`() {
        assertFails {
            provider("head -c 5000000 /dev/zero").scan(VettingRequest(root, ScanPolicy(), ScanSettings()))
        }
    }

    @Test fun `timed out process is terminated`() {
        val pidFile = root.resolve("adapter.pid")
        assertFailsWith<TimeoutException> {
            provider("echo ${'$'}${'$'} > adapter.pid\nexec sleep 10", 150)
                .scan(VettingRequest(root, ScanPolicy(), ScanSettings()))
        }
        val pid = Files.readString(pidFile).trim().toLong()
        val process = ProcessHandle.of(pid)
        if (process.isPresent) process.get().onExit().get(2, java.util.concurrent.TimeUnit.SECONDS)
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
    }

    private fun assertProviderIoStopped() {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
        fun workers() = Thread.getAllStackTraces().keys.filter { it.isAlive && it.name == "wrapperguard-provider-io" }
        while (workers().isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(workers().isEmpty(), "Provider pipe workers must stop after process cancellation.")
    }

    @Test fun `blocked request pipe is covered by adapter deadline and leaves no writer alive`() {
        val started = System.nanoTime()
        assertFailsWith<TimeoutException> {
            // The execution boundary receives an intentionally large request so it exceeds
            // native pipe capacity even on hosts whose ordinary project paths are short.
            provider("echo ${'$'}${'$'} > adapter.pid; exec sleep 10", 250)
                .runAdapter(root, "{\"projectPath\":\"" + "x".repeat(1024 * 1024) + "\"}\n")
        }
        val pid = Files.readString(root.resolve("adapter.pid")).trim().toLong()
        ProcessHandle.of(pid).ifPresent { it.onExit().get(2, java.util.concurrent.TimeUnit.SECONDS) }
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        assertProviderIoStopped()
        assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 3_000)
    }

    @Test fun `interrupting adapter while request pipe is blocked stops process and both pipe workers`() {
        val caller = java.util.concurrent.Executors.newSingleThreadExecutor()
        val pidFile = root.resolve("adapter.pid")
        try {
            val running = caller.submit {
                provider("echo ${'$'}${'$'} > adapter.pid; exec sleep 10")
                    .runAdapter(root, "{\"projectPath\":\"" + "x".repeat(1024 * 1024) + "\"}\n")
            }
            val started = System.nanoTime()
            while (!Files.exists(pidFile) && System.nanoTime() - started < java.util.concurrent.TimeUnit.SECONDS.toNanos(2)) Thread.sleep(10)
            assertTrue(Files.exists(pidFile), "Adapter must be running before cancellation.")
            assertTrue(running.cancel(true))
            caller.shutdown()
            assertTrue(caller.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS))
            val pid = Files.readString(pidFile).trim().toLong()
            ProcessHandle.of(pid).ifPresent { it.onExit().get(2, java.util.concurrent.TimeUnit.SECONDS) }
            assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
            assertProviderIoStopped()
        } finally { caller.shutdownNow() }
    }

    @Test fun `orphaned child holding stdout is terminated after parent exits`() {
        val os = System.getProperty("os.name").lowercase()
        val launcherAvailable = when {
            os.contains("mac") || os.contains("darwin") -> Files.isExecutable(Path.of("/usr/bin/perl"))
            os.contains("linux") -> Files.isExecutable(Path.of("/usr/bin/setsid")) || Files.isExecutable(Path.of("/bin/setsid"))
            else -> false
        }
        if (!launcherAvailable) return // unsupported hosts use documented best-effort cleanup
        val childFile = root.resolve("child.pid")
        val started = System.nanoTime()
        try {
            val result = try {
                provider("""
                    IFS= read -r request
                    sleep 5 &
                    echo ${'$'}! > child.pid
                    printf '%s' '{"findings":[]}'
                    exit 0
                """.trimIndent(), 150).scan(VettingRequest(root, ScanPolicy(), ScanSettings()))
            } catch (_: TimeoutException) { null }
            // Some JDKs drain stdout when the parent exits; others keep waiting for the
            // inherited child pipe. Either outcome must still terminate the child promptly.
            if (result != null) assertTrue(result.findings.isEmpty())
            val pid = Files.readString(childFile).trim().toLong()
            ProcessHandle.of(pid).ifPresent { it.onExit().get(2, java.util.concurrent.TimeUnit.SECONDS) }
            assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
            assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 3_000)
        } finally {
            if (Files.exists(childFile)) {
                ProcessHandle.of(Files.readString(childFile).trim().toLong()).ifPresent { it.destroyForcibly() }
            }
        }
    }

    @Test fun `deadline terminates both a live adapter and its stdout holding child`() {
        val childFile = root.resolve("child.pid")
        val parentFile = root.resolve("parent.pid")
        val started = System.nanoTime()
        try {
            assertFailsWith<TimeoutException> {
                provider("""
                    IFS= read -r request
                    echo ${'$'}${'$'} > parent.pid
                    sleep 10 &
                    echo ${'$'}! > child.pid
                    wait
                """.trimIndent(), 250).scan(VettingRequest(root, ScanPolicy(), ScanSettings()))
            }
            for (file in listOf(parentFile, childFile)) {
                val pid = Files.readString(file).trim().toLong()
                ProcessHandle.of(pid).ifPresent { it.onExit().get(2, java.util.concurrent.TimeUnit.SECONDS) }
                assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
            }
            assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 3_000)
        } finally {
            for (file in listOf(parentFile, childFile)) {
                if (Files.exists(file)) ProcessHandle.of(Files.readString(file).trim().toLong())
                    .ifPresent { it.destroyForcibly() }
            }
        }
    }

    @Test fun `project binding is checked independently before any process starts`() {
        val allowed = Files.createTempDirectory("wg-command-allowed-").toRealPath()
        try {
            val adapter = CommandVettingProvider(CommandProviderConfiguration("bound", "Bound", "1",
                listOf("/bin/sh", "-c", "printf 'launched' > marker; printf '%s' '{\"findings\":[]}'"),
                ProviderPermissions(enabled = true), projectRoots = listOf(allowed.toString())))
            assertFailsWith<IllegalArgumentException> { adapter.scan(VettingRequest(root, ScanPolicy(), ScanSettings())) }
            assertFalse(Files.exists(root.resolve("marker")))
            val report = VettingCoordinator(listOf(ProviderRegistration(adapter, ProviderPermissions()))).scan(root, ScanPolicy(), ScanSettings())
            assertEquals(ProviderRunStatus.DISABLED, report.providerRuns.single().status)
            assertEquals("Not applicable to this project.", report.providerRuns.single().message)
            adapter.scan(VettingRequest(allowed, ScanPolicy(), ScanSettings()))
            assertTrue(Files.exists(allowed.resolve("marker")))
        } finally { allowed.toFile().deleteRecursively() }
    }
}
