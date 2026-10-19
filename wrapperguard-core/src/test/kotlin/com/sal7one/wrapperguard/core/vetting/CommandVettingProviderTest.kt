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
            assertFailsWith<TimeoutException> {
                provider("""
                    IFS= read -r request
                    sleep 5 &
                    echo ${'$'}! > child.pid
                    printf '%s' '{"findings":[]}'
                    exit 0
                """.trimIndent(), 150).scan(VettingRequest(root, ScanPolicy(), ScanSettings()))
            }
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
}
