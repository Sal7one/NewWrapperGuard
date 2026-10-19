package com.sal7one.wrapperguard.core.vetting

import com.sal7one.wrapperguard.core.export.SarifExporter
import com.sal7one.wrapperguard.core.model.*
import com.sal7one.wrapperguard.core.policy.EnforcementMode
import com.sal7one.wrapperguard.core.policy.ScanPolicy
import com.sal7one.wrapperguard.core.policy.ScanSettings
import com.sal7one.wrapperguard.core.spi.ScannerEngine
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

class VettingCoordinatorTest {
    private val root = Path.of(System.getProperty("java.io.tmpdir"), "wrapperguard-provider-test")
    private val enforce = ScanPolicy(mode = EnforcementMode.ENFORCE)
    private val all = ProviderPermissions(actions = VettingAction.entries.toSet())

    private fun finding(
        actions: Set<VettingAction> = setOf(VettingAction.WARNING, VettingAction.BLOCK_BUILD, VettingAction.BLOCK_SYNC),
        severity: Severity = Severity.ERROR,
        kind: FindingKind = FindingKind.ACTIONABLE
    ) = Finding("forged-fingerprint", "SDK-RULE", "SDK observation", "Review this command", severity,
        Confidence.HIGH, FindingCategory.EXECUTION, kind, SourceLocation("build.gradle", 3),
        evidence = Evidence("exec dangerous-command"), actions = actions)

    private fun provider(id: String = "sdk", scan: (VettingRequest) -> ProviderResult = { ProviderResult(listOf(finding())) }) =
        object : VettingProvider {
            override val descriptor = ProviderDescriptor(id, "Test SDK $id", "1.0.0")
            override fun scan(request: VettingRequest) = scan.invoke(request)
        }

    private fun report(
        providers: List<ProviderRegistration> = listOf(ProviderRegistration(provider(), all)),
        policy: ScanPolicy = enforce,
        operation: VettingOperation = VettingOperation.SCAN,
        baseline: List<BaselineEntry> = emptyList()
    ) = VettingCoordinator(providers).scan(root, policy, ScanSettings(), baseline, operation)

    @Test
    fun runsProvidersConcurrentlyAndPublishesInRegistrationOrder() {
        val entered = CountDownLatch(2)
        val first = provider("first") {
            entered.countDown()
            check(entered.await(2, TimeUnit.SECONDS))
            Thread.sleep(40)
            ProviderResult(listOf(finding()))
        }
        val second = provider("second") {
            entered.countDown()
            check(entered.await(2, TimeUnit.SECONDS))
            ProviderResult(listOf(finding()))
        }
        val output = report(listOf(ProviderRegistration(first, all), ProviderRegistration(second, all)))
        assertEquals(listOf("first", "second"), output.findings.map { it.providerId })
        assertTrue(output.providerRuns.all { it.status == ProviderRunStatus.COMPLETED })
    }

    @Test
    fun boundsProviderConcurrency() {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val providers = (1..6).map { index ->
            ProviderRegistration(provider("sdk$index") {
                val running = active.incrementAndGet()
                peak.accumulateAndGet(running, ::maxOf)
                Thread.sleep(30)
                active.decrementAndGet()
                ProviderResult()
            }, all)
        }
        val output = VettingCoordinator(providers, 2).scan(root, enforce, ScanSettings())
        assertTrue(output.complete)
        assertEquals(2, peak.get())
    }

    @Test
    fun ignoresSpoofedIdentityStatusAndBlockActionWithoutGrants() {
        val spoof = provider {
            ProviderResult(listOf(finding().copy(providerId = "wrapperguard", policyAction = PolicyAction.BLOCK,
                status = FindingStatus.SUPPRESSED)))
        }
        val output = report(listOf(ProviderRegistration(spoof, ProviderPermissions())))
        val actual = output.findings.single()
        assertEquals("sdk", actual.providerId)
        assertEquals(FindingStatus.ACTIVE, actual.status)
        assertEquals(PolicyAction.ALLOW, actual.policyAction)
        assertTrue(actual.actions.isEmpty())
        assertTrue(output.blockers.isEmpty())
    }

    @Test
    fun warningOnlySdkCanNeverBlock() {
        val permissions = ProviderPermissions(actions = setOf(VettingAction.WARNING))
        val output = report(listOf(ProviderRegistration(provider(), permissions)))
        assertEquals(ScanVerdict.REVIEW_REQUIRED, output.verdict)
        assertEquals(setOf(VettingAction.WARNING), output.findings.single().actions)
        assertTrue(output.blockers.isEmpty())
    }

    @Test
    fun grantedActionsStillRequireProviderRequest() {
        val output = report(listOf(ProviderRegistration(provider { ProviderResult(listOf(finding(emptySet()))) }, all)))
        assertEquals(PolicyAction.ALLOW, output.findings.single().policyAction)
        assertTrue(output.blockers.isEmpty())
    }

    @Test
    fun filtersBlockCapabilitiesForTheCurrentOperation() {
        for (operation in VettingOperation.entries) {
            val output = report(operation = operation)
            val actions = output.findings.single().actions
            assertEquals(ScanVerdict.EXECUTION_BLOCKED, output.verdict)
            assertEquals(operation != VettingOperation.SYNC, VettingAction.BLOCK_BUILD in actions)
            assertEquals(operation != VettingOperation.BUILD, VettingAction.BLOCK_SYNC in actions)
        }
        val buildOnly = ProviderPermissions(actions = setOf(VettingAction.BLOCK_BUILD))
        val output = report(listOf(ProviderRegistration(provider(), buildOnly)), operation = VettingOperation.SYNC)
        assertTrue(output.blockers.isEmpty())
        assertTrue(output.findings.single().actions.isEmpty())
    }

    @Test
    fun hostSeverityReachabilityModeAndAuditInvariantsApply() {
        val inputs = listOf(
            finding(severity = Severity.INFO),
            finding(severity = Severity.WARN),
            finding().copy(reachable = false),
            finding(kind = FindingKind.AUDIT_EVIDENCE),
            finding(kind = FindingKind.SCANNER_NOTICE)
        )
        inputs.forEach { input ->
            val output = report(listOf(ProviderRegistration(provider { ProviderResult(listOf(input)) }, all)))
            assertTrue(output.blockers.isEmpty(), "Must not block: $input")
            assertFalse(output.findings.single().actions.any { it == VettingAction.BLOCK_BUILD || it == VettingAction.BLOCK_SYNC })
        }
        listOf(EnforcementMode.AUDIT, EnforcementMode.WARN).forEach { mode ->
            assertTrue(report(policy = enforce.copy(mode = mode)).blockers.isEmpty())
        }
    }

    @Test
    fun infoCannotBlockEvenWithExplicitRuleAndInfoThreshold() {
        val info = finding(severity = Severity.INFO)
        val policy = enforce.copy(minimumBlockingSeverity = Severity.INFO, blockedRuleIds = setOf(info.ruleId))
        assertEquals(PolicyAction.ALLOW, policy.actionFor(info))
        assertTrue(report(listOf(ProviderRegistration(provider { ProviderResult(listOf(info)) }, all)), policy).blockers.isEmpty())
    }

    @Test
    fun fingerprintsAndTrustedSuppressionsAreNamespacedByProvider() {
        val registrations = listOf(ProviderRegistration(provider("one"), all), ProviderRegistration(provider("two"), all))
        val original = report(registrations, baseline = listOf(BaselineEntry("forged-fingerprint")))
        assertEquals(2, original.blockers.size)
        assertNotEquals(original.findings[0].fingerprint, original.findings[1].fingerprint)
        val suppressed = report(registrations, baseline = listOf(BaselineEntry(original.findings[0].fingerprint)))
        assertEquals(FindingStatus.SUPPRESSED, suppressed.findings[0].status)
        assertEquals(FindingStatus.ACTIVE, suppressed.findings[1].status)
        assertEquals("two", suppressed.blockers.single().providerId)
    }

    @Test
    fun doesNotExposeTrustedBaselinesToExternalSdk() {
        val sdk = provider { request ->
            assertTrue(request.baseline.isEmpty())
            ProviderResult()
        }
        assertTrue(report(listOf(ProviderRegistration(sdk, all)), baseline = listOf(BaselineEntry("private-approval"))).complete)
    }

    @Test
    fun builtInRetainsLegacyFingerprintsAndTrustedSuppression() {
        var receivedBaseline: List<BaselineEntry>? = null
        val original = finding().copy(fingerprint = "legacy-stable-fingerprint", status = FindingStatus.SUPPRESSED)
        val engine = object : ScannerEngine {
            override fun scan(projectRoot: Path, policy: ScanPolicy, settings: ScanSettings, baseline: List<BaselineEntry>): ScanReport {
                receivedBaseline = baseline
                return ScanReport(projectPath = projectRoot.toString(), verdict = ScanVerdict.CLEAN, policyName = policy.name,
                    findings = listOf(original), scannedFiles = 1, durationMs = 0)
            }
        }
        val baseline = listOf(BaselineEntry(original.fingerprint))
        val output = report(listOf(ProviderRegistration(BuiltInVettingProvider(engine), BuiltInVettingProvider.DEFAULT_PERMISSIONS)), baseline = baseline)
        assertEquals(baseline, receivedBaseline)
        assertEquals(original.fingerprint, output.findings.single().fingerprint)
        assertEquals(FindingStatus.SUPPRESSED, output.findings.single().status)
        assertEquals(1, output.scannedFiles)
        assertEquals(ScanVerdict.CLEAN, output.verdict)
        assertTrue(BuiltInVettingProvider.DEFAULT_PERMISSIONS.required)
    }

    @Test
    fun failuresAreIsolatedAndExceptionDetailsAreNeverPublished() {
        val broken = provider("broken") { throw IllegalStateException("token=very-secret-value") }
        val output = report(listOf(ProviderRegistration(broken, all), ProviderRegistration(provider("healthy"), all)))
        assertFalse(output.complete)
        assertEquals(listOf(ProviderRunStatus.FAILED, ProviderRunStatus.COMPLETED), output.providerRuns.map { it.status })
        assertEquals("healthy", output.findings.single().providerId)
        assertFalse(Json.encodeToString(output).contains("very-secret-value"))
    }

    @Test
    fun requiredFailureBlocksOnlyWhenHostAllowsTheMatchingCapability() {
        val broken = provider { throw IllegalStateException("failure") }
        val required = ProviderPermissions(required = true, actions = setOf(VettingAction.BLOCK_BUILD))
        val registration = listOf(ProviderRegistration(broken, required))
        val output = report(registration, operation = VettingOperation.BUILD)
        assertEquals(ScanVerdict.EXECUTION_BLOCKED, output.verdict)
        assertEquals("WG-PROVIDER-UNAVAILABLE", output.blockers.single().ruleId)
        assertTrue(report(registration, operation = VettingOperation.SYNC).blockers.isEmpty())
        assertTrue(report(registration, policy = ScanPolicy()).blockers.isEmpty())
        assertTrue(report(listOf(ProviderRegistration(broken, required.copy(actions = emptySet())))).blockers.isEmpty())
    }

    @Test
    fun incompleteRequiredProviderRetainsPartialFindingsAndBlocksWhenGranted() {
        val partial = provider { ProviderResult(listOf(finding(severity = Severity.INFO)), complete = false) }
        val output = report(listOf(ProviderRegistration(partial, all.copy(required = true))))
        assertFalse(output.complete)
        assertEquals(2, output.findings.size)
        assertEquals("WG-PROVIDER-UNAVAILABLE", output.blockers.single().ruleId)
        assertTrue(output.notices.any { it.code == "WG-PROVIDER-INCOMPLETE" })
        assertEquals(ProviderRunStatus.COMPLETED, output.providerRuns.single().status)
        assertNotNull(output.providerRuns.single().message)
    }

    @Test
    fun timesOutUncooperativeProvidersAndDiscardsLateResults() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val late = provider("late-sdk") {
            entered.countDown()
            while (true) {
                try {
                    release.await()
                    break
                } catch (_: InterruptedException) { /* simulate an uncooperative SDK */ }
            }
            completed.countDown()
            ProviderResult(listOf(finding()))
        }
        val started = System.nanoTime()
        val output = report(listOf(ProviderRegistration(late, all.copy(timeoutMs = 100))))
        try {
            assertEquals(0L, entered.count)
            assertEquals(ProviderRunStatus.TIMED_OUT, output.providerRuns.single().status)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_000)
            assertTrue(output.findings.isEmpty())
        } finally {
            release.countDown()
        }
        assertTrue(completed.await(1, TimeUnit.SECONDS))
        assertTrue(output.findings.isEmpty())
    }

    @Test
    fun providerReportedTimeoutHasTimeoutStatus() {
        val output = report(listOf(ProviderRegistration(provider { throw TimeoutException() }, all)))
        assertEquals(ProviderRunStatus.TIMED_OUT, output.providerRuns.single().status)
    }

    @Test
    fun timedOutSdkIsNotInvokedAgainUntilItsOriginalCallReturns() {
        val calls = AtomicInteger()
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val sdk = provider("retry-sdk") {
            calls.incrementAndGet()
            try {
                while (true) {
                    try { release.await(); break } catch (_: InterruptedException) { }
                }
                ProviderResult()
            } finally { finished.countDown() }
        }
        val registration = listOf(ProviderRegistration(sdk, all.copy(timeoutMs = 100)))
        try {
            assertEquals(ProviderRunStatus.TIMED_OUT, report(registration).providerRuns.single().status)
            assertEquals(ProviderRunStatus.FAILED, report(registration).providerRuns.single().status)
            assertEquals(1, calls.get())
        } finally { release.countDown() }
        assertTrue(finished.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun survivingSdkCallbacksHaveAHostWideLimitAcrossScans() {
        val entered = CountDownLatch(32)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(32)
        val registrations = (1..32).map { index ->
            ProviderRegistration(provider("capacity$index") {
                entered.countDown()
                try {
                    while (true) {
                        try { release.await(); break } catch (_: InterruptedException) { }
                    }
                    ProviderResult()
                } finally { finished.countDown() }
            }, all.copy(timeoutMs = 1_000))
        }
        try {
            val output = VettingCoordinator(registrations, 32).scan(root, enforce, ScanSettings())
            assertEquals(0L, entered.count)
            assertTrue(output.providerRuns.all { it.status == ProviderRunStatus.TIMED_OUT })
            val extraCalls = AtomicInteger()
            val extra = provider("capacity-overflow") { extraCalls.incrementAndGet(); ProviderResult() }
            val overflow = report(listOf(ProviderRegistration(extra, all)))
            assertEquals(ProviderRunStatus.FAILED, overflow.providerRuns.single().status)
            assertEquals(0, extraCalls.get())
        } finally { release.countDown() }
        assertTrue(finished.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun noEnabledProvidersNeverProducesClean() {
        assertEquals(ScanVerdict.INCOMPLETE, report(emptyList()).verdict)
        val disabled = report(listOf(ProviderRegistration(provider { error("Must not execute") }, all.copy(enabled = false))))
        assertEquals(ScanVerdict.INCOMPLETE, disabled.verdict)
        assertEquals(ProviderRunStatus.DISABLED, disabled.providerRuns.single().status)
    }

    @Test
    fun rejectsDuplicateIdsUnsupportedApiAndReservedIdentity() {
        assertFailsWith<IllegalArgumentException> { VettingCoordinator(listOf(ProviderRegistration(provider(), all), ProviderRegistration(provider(), all))) }
        listOf("../invalid", "wrapperguard", "SDK", "x".repeat(81)).forEach { id ->
            assertFailsWith<IllegalArgumentException> { VettingCoordinator(listOf(ProviderRegistration(provider(id), all))) }
        }
        val unsupported = object : VettingProvider {
            override val descriptor = ProviderDescriptor("sdk", "SDK", "1", apiVersion = 2)
            override fun scan(request: VettingRequest) = ProviderResult()
        }
        assertFailsWith<IllegalArgumentException> { VettingCoordinator(listOf(ProviderRegistration(unsupported, all))) }
    }

    @Test
    fun invalidResultCannotPoisonOtherProviderResults() {
        val invalidResults = listOf(
            ProviderResult(scannedFiles = -1),
            ProviderResult(listOf(finding().copy(location = SourceLocation("../../outside")))),
            ProviderResult(listOf(finding().copy(location = SourceLocation("build.gradle", -1)))),
            ProviderResult(listOf(finding().copy(mlSignal = MlSignal(Double.NaN, "invalid-score")))),
            ProviderResult(listOf(finding().copy(ruleId = "token=secret")))
        )
        invalidResults.forEach { invalid ->
            val output = report(listOf(ProviderRegistration(provider("invalid") { invalid }, all), ProviderRegistration(provider("valid"), all)))
            assertEquals(ProviderRunStatus.FAILED, output.providerRuns.first().status)
            assertEquals("valid", output.findings.single().providerId)
        }
    }

    @Test
    fun redactsAllProviderTextIncludingNoticesEdgesAndModelMetadata() {
        val secret = "token=sensitive-value"
        val sdk = provider {
            ProviderResult(
                findings = listOf(finding().copy(title = secret, message = secret, remediation = secret,
                    evidence = Evidence(secret), callChain = listOf(CallChainLink(SourceLocation("build.gradle"), secret)),
                    mlSignal = MlSignal(0.9, secret, listOf(secret)))),
                notices = listOf(ScanNotice(secret, secret, SourceLocation("build.gradle"))),
                edges = listOf(ScriptEdge(SourceLocation("build.gradle"), SourceLocation("script.sh"), secret))
            )
        }
        val output = report(listOf(ProviderRegistration(sdk, all)))
        assertTrue(output.complete)
        assertFalse(Json.encodeToString(output).contains("sensitive-value"))
        assertFalse(SarifExporter.toSarif(output).toString().contains("sensitive-value"))
        assertTrue(output.findings.single().evidence!!.redacted)
    }

    @Test
    fun mlSignalCannotChangePolicyDecision() {
        for (severity in Severity.entries) {
            val shadow = finding(severity = severity).copy(mlSignal = MlSignal(1.0, "research"))
            val withMl = provider { ProviderResult(listOf(shadow)) }
            val output = report(listOf(ProviderRegistration(withMl, all)))
            assertEquals(PolicyAction.ALLOW, enforce.actionFor(shadow))
            assertTrue(output.blockers.isEmpty())
            assertTrue(output.warnings.isEmpty())
            assertEquals(ScanVerdict.CLEAN, output.verdict)
            assertEquals(FindingKind.AUDIT_EVIDENCE, output.findings.single().kind)
            assertTrue(output.findings.single().actions.isEmpty())
        }
    }

    @Test
    fun legacyJsonOmitsNewDefaultFieldsAndStillDecodes() {
        val legacyFinding = finding().copy(actions = emptySet())
        val encoded = Json.encodeToString(legacyFinding)
        assertFalse(encoded.contains("providerId"))
        assertFalse(encoded.contains("actions"))
        val decoded = Json.decodeFromString<Finding>(encoded)
        assertNull(decoded.providerId)
        assertTrue(decoded.actions.isEmpty())
        val legacyReport = ScanReport(projectPath = ".", verdict = ScanVerdict.CLEAN, policyName = "Default", findings = emptyList(), scannedFiles = 0, durationMs = 0)
        assertFalse(Json.encodeToString(legacyReport).contains("providerRuns"))
    }

    @Test
    fun sarifNamespacesRulesByProviderWhilePreservingBuiltInKeys() {
        val base = finding().copy(providerId = "wrapperguard")
        val report = ScanReport(projectPath = ".", verdict = ScanVerdict.CLEAN, policyName = "Default",
            findings = listOf(base, base.copy(providerId = "vendor"), base.copy(providerId = "other")), scannedFiles = 0, durationMs = 0)
        val run = SarifExporter.toSarif(report)["runs"]!!.jsonArray.single().jsonObject
        val ids = run["tool"]!!.jsonObject["driver"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(listOf("SDK-RULE", "vendor/SDK-RULE", "other/SDK-RULE"), ids)
        assertEquals("vendor", run["results"]!!.jsonArray[1].jsonObject["properties"]!!.jsonObject["providerId"]!!.jsonPrimitive.content)
    }
}
