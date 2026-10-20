package com.sal7one.wrapperguard.core.vetting

import com.sal7one.wrapperguard.core.policy.ScanPolicy
import com.sal7one.wrapperguard.core.policy.ScanSettings
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Use the identical installable adapter fixture exercised by the TypeScript bridge. */
class SharedProviderConformanceTest {
    @Test fun `interrupting the shared Python adapter kills its actual process`() {
        val candidates = listOfNotNull(System.getenv("WRAPPERGUARD_CONFORMANCE_PYTHON"),
            System.getenv("pythonLocation")?.let { Path.of(it, if (System.getProperty("os.name").startsWith("Windows")) "python.exe" else "bin/python").toString() },
            "/usr/bin/python3", "/usr/local/bin/python3").map(Path::of)
        val python = candidates.firstOrNull { it.isAbsolute && Files.isExecutable(it) }
            ?: error("Set WRAPPERGUARD_CONFORMANCE_PYTHON to run the cross-host provider fixture")
        val user = Files.createTempDirectory("wg-jvm-shared-conformance-")
        val caller = Executors.newSingleThreadExecutor()
        try {
            val project = Files.createDirectory(user.resolve("project"))
            val installed = Files.copy(Path.of("../examples/provider-conformance/adapter.py"), user.resolve("installed-adapter.py"))
            val marker = user.resolve("started.pid")
            val provider = CommandVettingProvider(CommandProviderConfiguration("example.conformance", "Inert adapter", "1",
                listOf(python.toString(), "-I", installed.toString(), "--mode", "cancellation", "--started-file", marker.toString()),
                ProviderPermissions(enabled = true, timeoutMs = 5000)))
            val pending = caller.submit { provider.scan(VettingRequest(project, ScanPolicy(), ScanSettings())) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
            while (!Files.exists(marker) && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(Files.exists(marker), "Adapter must start before cancellation")
            val pid = Files.readString(marker).trim().toLong()
            assertTrue(pending.cancel(true))
            caller.shutdown()
            assertTrue(caller.awaitTermination(2, TimeUnit.SECONDS), "Cancelled adapter worker must terminate")
            ProcessHandle.of(pid).ifPresent { it.onExit().get(2, TimeUnit.SECONDS) }
            assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "Cancelled adapter process must be dead")
        } finally {
            caller.shutdownNow()
            user.toFile().deleteRecursively()
        }
    }
}
