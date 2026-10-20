package com.sal7one.wrapperguard.core.vetting

import com.sal7one.wrapperguard.core.inputs.ProviderInputScope
import com.sal7one.wrapperguard.core.inputs.InputSealLimits
import com.sal7one.wrapperguard.core.inputs.ProviderReadTracker
import com.sal7one.wrapperguard.core.model.*
import com.sal7one.wrapperguard.core.policy.ScanPolicy
import com.sal7one.wrapperguard.core.policy.ScanSettings
import com.sal7one.wrapperguard.core.policy.EnforcementMode
import com.sal7one.wrapperguard.core.spi.ScannerEngine
import java.nio.file.Path
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ProviderConformanceTest {
    private val root = Files.createTempDirectory("wg-conformance-").toRealPath()
    private val source = Files.writeString(root.resolve("Main.kt"), "good")
    private val scope = ProviderInputScope(includes = listOf("**/*.kt"))
    @AfterTest fun cleanup() { root.toFile().deleteRecursively() }
    private fun registration(descriptor: ProviderDescriptor = ProviderDescriptor("custom", "Custom SDK", "1", category = "SAST", inputScope = scope),
        required: Boolean = false, block: (VettingRequest) -> ProviderResult): ProviderRegistration =
        ProviderRegistration(object : VettingProvider {
            override val descriptor = descriptor
            override fun scan(request: VettingRequest) = block(request)
        }, ProviderPermissions(required = required))

    @Test fun `configuration checks seal explicit scopes but never execute providers`() {
        val calls = AtomicInteger()
        val result = ProviderConformance.check(listOf(registration { calls.incrementAndGet(); ProviderResult() }), root)
        assertTrue(result.valid)
        assertFalse(result.executed)
        assertEquals(1, result.sealedFileCount)
        assertEquals(0, calls.get())
        assertTrue(result.providers.single().scopeDeclared)
    }

    @Test fun `valid findings pass runtime conformance while protocol failures do not`() {
        val finding = Finding(ruleId = "CUSTOM-1", title = "Unsafe call", message = "Review source", severity = Severity.ERROR,
            confidence = Confidence.HIGH, category = FindingCategory.EXECUTION, location = SourceLocation("Main.kt"),
            actions = setOf(VettingAction.BLOCK_BUILD))
        val valid = ProviderConformance.check(listOf(registration { ProviderResult(listOf(finding), scannedFiles = 1) }), root, execute = true)
        assertTrue(valid.valid)
        assertTrue(valid.executed)
        assertEquals(1, valid.providers.single().findingCount)
        val invalid = ProviderConformance.check(listOf(registration { error("token=private-value") }), root, execute = true)
        assertFalse(invalid.valid)
        assertEquals(ProviderRunStatus.FAILED, invalid.providers.single().status)
        assertFalse(invalid.notices.toString().contains("private-value"))
        assertFalse(ProviderConformance.check(listOf(registration { ProviderResult(complete = false) }), root, execute = true).valid)
    }

    @Test fun `same-size source changes restored timestamp cannot pass conformance`() {
        val timestamp = Files.getLastModifiedTime(source)
        val result = ProviderConformance.check(listOf(registration {
            Files.writeString(source, "evil")
            Files.setLastModifiedTime(source, timestamp)
            ProviderResult()
        }), root, execute = true)
        assertFalse(result.valid)
        assertTrue(result.notices.any { it.code == "WG-INPUT-CHANGED" })
    }

    @Test fun `failed input boundaries stop before provider execution`() {
        val calls = AtomicInteger()
        Files.delete(source)
        Files.createSymbolicLink(source, java.nio.file.Path.of("/etc/passwd"))
        val result = ProviderConformance.check(listOf(registration { calls.incrementAndGet(); ProviderResult() }), root, execute = true)
        assertFalse(result.valid)
        assertFalse(result.executed)
        assertEquals(0, calls.get())
        assertTrue(result.notices.any { it.code == "WG-INPUT-SEAL-FAILED" })
    }

    @Test fun `unknown legacy scopes remain compatible and advisory category cannot claim analysis coverage`() {
        val descriptor = ProviderDescriptor("custom.ai", "Custom AI", "1", category = "AI")
        val provider = registration(descriptor) { ProviderResult() }
        val check = ProviderConformance.check(listOf(provider), root, execute = true)
        assertTrue(check.valid) // API conformance is separate from security coverage.
        assertFalse(check.providers.single().scopeDeclared)
        assertTrue(check.notices.any { it.code == "WG-PROVIDER-SCOPE-UNKNOWN" })
        val report = VettingCoordinator(listOf(provider)).scan(root, ScanPolicy(), ScanSettings())
        assertFalse(report.complete)
        assertTrue(report.notices.any { it.code == "WG-NO-ANALYSIS-PROVIDERS" })
    }

    @Test fun `descriptor metadata and declarations are immutable host snapshots`() {
        val includes = mutableListOf("**/*.kt")
        val tags = mutableListOf("android")
        val descriptor = ProviderDescriptor("custom", "Custom", "1").copyWithMetadata(tags = tags, inputScope = ProviderInputScope(includes))
        val coordinator = VettingCoordinator(listOf(registration(descriptor) { ProviderResult() }))
        includes.clear(); tags.clear()
        val report = coordinator.scan(root, ScanPolicy(), ScanSettings())
        assertEquals(listOf("android"), report.providerRuns.single().descriptor.tags)
        assertEquals(listOf("**/*.kt"), report.providerRuns.single().descriptor.inputScope?.includes)
    }

    @Test fun `slower parallel providers cannot change an already completed builtin read set unnoticed`() {
        val script = Files.writeString(root.resolve("extensionless-script"), "good")
        val timestamp = Files.getLastModifiedTime(script)
        val scanned = CountDownLatch(1)
        val scannerThread = AtomicReference<Thread>()
        val engine = object : ScannerEngine {
            override fun scan(projectRoot: Path, policy: ScanPolicy, settings: ScanSettings, baseline: List<BaselineEntry>): ScanReport {
                ProviderReadTracker.record(script, Files.readAllBytes(script))
                scannerThread.set(Thread.currentThread())
                scanned.countDown()
                return ScanReport(projectPath = projectRoot.toString(), verdict = ScanVerdict.CLEAN, policyName = policy.name,
                    findings = emptyList(), scannedFiles = 1, durationMs = 0)
            }
        }
        val parallel = registration {
            check(scanned.await(2, TimeUnit.SECONDS))
            // Wait for the built-in task to finish its own read verification and become idle.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (scannerThread.get().state != Thread.State.WAITING && System.nanoTime() < deadline) Thread.yield()
            check(scannerThread.get().state == Thread.State.WAITING)
            Files.writeString(script, "evil")
            Files.setLastModifiedTime(script, timestamp)
            ProviderResult()
        }
        val report = VettingCoordinator(listOf(ProviderRegistration(BuiltInVettingProvider(engine), BuiltInVettingProvider.DEFAULT_PERMISSIONS), parallel))
            .scan(root, ScanPolicy(mode = EnforcementMode.ENFORCE), ScanSettings(), operation = VettingOperation.BUILD)
        assertFalse(report.complete)
        assertTrue(report.notices.any { it.code == "WG-INPUT-CHANGED" })
        assertTrue(report.blockers.any { it.ruleId == "WG-PROVIDER-UNAVAILABLE" })
    }
}
