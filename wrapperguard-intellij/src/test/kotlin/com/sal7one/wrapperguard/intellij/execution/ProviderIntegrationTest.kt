package com.sal7one.wrapperguard.intellij.execution

import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.externalSystem.model.ExternalSystemException
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTask
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListenerAdapter
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemExecutionAware
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.UIUtil
import com.sal7one.wrapperguard.core.model.*
import com.sal7one.wrapperguard.core.vetting.*
import com.sal7one.wrapperguard.intellij.WrapperGuardProjectService
import com.sal7one.wrapperguard.intellij.settings.WrapperGuardSettings
import com.sal7one.wrapperguard.intellij.vetting.IntellijVettingProviders
import java.nio.file.Files
import java.nio.file.Path
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import org.jetbrains.plugins.gradle.util.GradleConstants
import com.sal7one.wrapperguard.intellij.demo.WrapperGuardDemo
import kotlinx.serialization.json.Json

/** Exercises SDK registration through the IDE host, not just the pure coordinator. */
class ProviderIntegrationTest : HeavyPlatformTestCase() {
    private var originalState = WrapperGuardSettings.State()

    override fun setUp() {
        super.setUp()
        val settings = WrapperGuardSettings.getInstance()
        originalState = settings.state.copy(providers = settings.state.providers.toMutableList())
        settings.loadState(WrapperGuardSettings.State())
        val root = Files.createDirectories(Path.of(project.basePath!!))
        Files.writeString(root.resolve("gradlew"), "#!/bin/sh\necho clean\n")
    }

    override fun tearDown() {
        try {
            WrapperGuardSettings.getInstance().loadState(originalState)
        } finally {
            super.tearDown()
        }
    }

    private fun register(provider: VettingProvider, permissions: ProviderPermissions?) {
        ExtensionTestUtil.maskExtensions(IntellijVettingProviders.EP_NAME, listOf(provider), testRootDisposable)
        if (permissions != null) WrapperGuardSettings.getInstance().state.providers.add(
            WrapperGuardSettings.ProviderState(provider.descriptor.id, permissions.enabled,
                permissions.actions.map { it.name }.toMutableList(), permissions.required, permissions.timeoutMs)
        )
    }

    fun testInstalledSdkIsDisabledUntilUserEnablesIt() {
        val calls = AtomicInteger()
        val sdk = provider { calls.incrementAndGet(); ProviderResult() }
        register(sdk, null)
        val report = runGuardScan(project)!!
        assertEquals(0, calls.get())
        assertEquals(ProviderRunStatus.DISABLED, report.providerRuns.single { it.descriptor.id == "example-sdk" }.status)
    }

    fun testSyncPermissionCannotBlockBuildAndBuildPermissionCannotBlockSync() {
        val sdk = provider { ProviderResult(findings = listOf(finding(setOf(VettingAction.BLOCK_BUILD, VettingAction.BLOCK_SYNC)))) }
        register(sdk, ProviderPermissions(actions = setOf(VettingAction.BLOCK_SYNC)))
        val build = runGuardScan(project, VettingOperation.BUILD)!!
        assertFalse(build.findings.any { VettingAction.BLOCK_BUILD in it.actions })
        assertFalse(shouldBlockSync(build))
        val sync = runGuardScan(project, VettingOperation.SYNC)!!
        assertTrue(shouldBlockSync(sync))
        assertEquals("example-sdk", sync.findings.single { it.ruleId == "SDK-001" }.providerId)

        WrapperGuardSettings.getInstance().state.providers.single { it.id == "example-sdk" }.actions =
            mutableListOf(VettingAction.BLOCK_BUILD.name)
        assertFalse(shouldBlockSync(runGuardScan(project, VettingOperation.SYNC)!!))
        assertTrue(runGuardScan(project, VettingOperation.BUILD)!!.findings.any { VettingAction.BLOCK_BUILD in it.actions })
    }

    fun testRequiredSdkFailureBlocksOnlyGrantedOperation() {
        register(provider { error("private provider exception") }, ProviderPermissions(
            actions = setOf(VettingAction.BLOCK_SYNC), required = true
        ))
        assertFalse(runGuardScan(project, VettingOperation.BUILD)!!.blockers.any { it.providerId == "example-sdk" })
        val sync = runGuardScan(project, VettingOperation.SYNC)!!
        assertTrue(shouldBlockSync(sync))
        assertFalse(sync.toString().contains("private provider exception"))
    }

    fun testRegisteredGradleHookVetoesBeforeResolverAndHonorsSyncGrant() {
        register(provider { ProviderResult(findings = listOf(finding(setOf(VettingAction.BLOCK_SYNC)))) },
            ProviderPermissions(actions = setOf(VettingAction.BLOCK_SYNC)))
        val gate = ExternalSystemExecutionAware.getExtensions(GradleConstants.SYSTEM_ID)
            .filterIsInstance<WrapperGuardGradleSyncGate>().single()
        val id = ExternalSystemTaskId.create(GradleConstants.SYSTEM_ID, ExternalSystemTaskType.RESOLVE_PROJECT, project)
        val task = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ExternalSystemTask::class.java)) { _, method, _ ->
            if (method.name == "getId") id else error("The gate must not execute the external-system task")
        } as ExternalSystemTask
        var vetoed = false
        try {
            gate.prepareExecution(task, project.basePath!!, false,
                object : ExternalSystemTaskNotificationListenerAdapter() {}, project)
        } catch (_: ExternalSystemException) {
            vetoed = true
        }
        assertTrue("the registered hook must veto sync before the resolver is invoked", vetoed)
        assertTrue(shouldBlockSync(project.getService(WrapperGuardProjectService::class.java).report!!))
        // Exercises the production preparation hook without launching a Gradle process.
        WrapperGuardSettings.getInstance().state.providers.single { it.id == "example-sdk" }.actions =
            mutableListOf(VettingAction.WARNING.name)
        gate.prepareExecution(task, project.basePath!!, false,
            object : ExternalSystemTaskNotificationListenerAdapter() {}, project)
    }

    fun testSafeModeDoesNotInvokeEnabledSdk() {
        val calls = AtomicInteger()
        register(provider { calls.incrementAndGet(); ProviderResult() }, ProviderPermissions(
            actions = VettingAction.entries.toSet(), required = true
        ))
        val properties = listOf("idea.trust.headless.disabled", "idea.trust.all.projects", "idea.trust.disabled")
        val previous = properties.associateWith { System.getProperty(it) }
        try {
            properties.forEach { System.setProperty(it, "false") }
            TrustedProjects.setProjectTrusted(project, false)
            assertFalse(TrustedProjects.isProjectTrusted(project))
            val report = runGuardScan(project)!!
            assertEquals(0, calls.get())
            assertTrue(report.notices.any { it.code == "WG-PROVIDERS-UNTRUSTED" })
            assertTrue(report.findings.any { it.ruleId == "WG-PROJECT-UNTRUSTED" && VettingAction.BLOCK_BUILD in it.actions })
            assertTrue(shouldBlockSync(runGuardScan(project, VettingOperation.SYNC)!!))
            // Static background analysis remains available without invoking third-party SDKs.
            val background = project.getService(WrapperGuardProjectService::class.java).scan()!!
            assertTrue(background.providerRuns.any { it.descriptor.id == "wrapperguard" && it.status == ProviderRunStatus.COMPLETED })
            assertEquals(0, calls.get())
        } finally {
            TrustedProjects.setProjectTrusted(project, true)
            previous.forEach { (key, value) ->
                if (value == null) System.clearProperty(key) else System.setProperty(key, value)
            }
        }
    }

    fun testRevokedGrantsDoNotPublishInflightBackgroundResultAsFresh() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val calls = AtomicInteger()
        register(provider {
            if (calls.incrementAndGet() == 1) { started.countDown(); release.await(5, TimeUnit.SECONDS) }
            else { secondStarted.countDown(); releaseSecond.await(5, TimeUnit.SECONDS) }
            ProviderResult(findings = listOf(finding(setOf(VettingAction.WARNING))))
        }, ProviderPermissions(actions = setOf(VettingAction.WARNING)))
        val service = project.getService(WrapperGuardProjectService::class.java)
        val reports = CopyOnWriteArrayList<ScanReport>()
        service.addListener { if (it != null) reports.add(it) }
        try {
            // Unit-test Task.queue can run synchronously; start from a worker so revocation
            // actually happens while the first scan is waiting, as it does in production.
            val pending = CompletableFuture.runAsync { service.scanAsync() }
            waitUntil { started.count == 0L }
            WrapperGuardSettings.getInstance().state.providers.single { it.id == "example-sdk" }.actions.clear()
            service.settingsChanged()
            release.countDown()
            waitUntil { secondStarted.count == 0L }
            assertTrue(reports.any { it.stale && it.findings.any { f -> f.providerId == "example-sdk" && VettingAction.WARNING in f.actions } })
            releaseSecond.countDown()
            waitUntil { !service.isScanning }
            pending.get(10, TimeUnit.SECONDS)
            assertFalse(service.report!!.stale)
            assertTrue(service.report!!.findings.single { it.ruleId == "SDK-001" }.actions.isEmpty())
        } finally {
            release.countDown()
            releaseSecond.countDown()
        }
    }

    fun testSyncDisposalDuringScanCancelsBeforePublishing() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        register(provider { started.countDown(); release.await(5, TimeUnit.SECONDS); ProviderResult() },
            ProviderPermissions(actions = setOf(VettingAction.WARNING)))
        val root = Files.createDirectories(Path.of(project.basePath!!).resolve("disposed-sync-project"))
        val other = ProjectManagerEx.getInstanceEx().newProject(root, OpenProjectTask(
            isNewProject = true, useDefaultProjectAsTemplate = false
        ))!!
        try {
            val id = ExternalSystemTaskId.create(GradleConstants.SYSTEM_ID, ExternalSystemTaskType.RESOLVE_PROJECT, other)
            val task = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ExternalSystemTask::class.java)) { _, method, _ ->
                if (method.name == "getId") id else error("The gate must not execute the external-system task")
            } as ExternalSystemTask
            val pending = CompletableFuture.runAsync {
                WrapperGuardGradleSyncGate().prepareExecution(task, root.toString(), false,
                    object : ExternalSystemTaskNotificationListenerAdapter() {}, other)
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            ApplicationManager.getApplication().runWriteAction { Disposer.dispose(other) }
            release.countDown()
            try {
                pending.get(10, TimeUnit.SECONDS)
                fail("a disposed project must cancel synchronization")
            } catch (error: ExecutionException) {
                assertTrue(error.cause is ProcessCanceledException)
            }
        } finally {
            release.countDown()
            if (!other.isDisposed) ApplicationManager.getApplication().runWriteAction { Disposer.dispose(other) }
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(10)
        }
        assertTrue("background scan must finish within the test deadline", condition())
    }

    fun testUserGrantChangesInvalidateExecutionSeal() {
        val service = project.getService(WrapperGuardProjectService::class.java)
        val initial = service.computeSeal()
        WrapperGuardSettings.getInstance().state.providers.add(WrapperGuardSettings.ProviderState(
            id = "example-sdk", enabled = true, actions = mutableListOf(VettingAction.BLOCK_BUILD.name)
        ))
        assertNotNull(initial)
        assertTrue(initial != service.computeSeal())
    }

    fun testInvalidProviderConfigurationProducesVisibleGuardFailure() {
        val root = Path.of(project.basePath!!)
        val file = root.resolve("providers.json")
        Files.writeString(file, "{}")
        WrapperGuardSettings.getInstance().state.providerConfigurationPath = file.toString()
        // A configuration shipped inside the project must not become a trusted authority.
        val report = runGuardedScanOrFailure(project)!!
        assertTrue(report.findings.any { VettingAction.BLOCK_BUILD in it.actions })
        assertFalse(report.complete)
    }

    fun testCleanDemoDataNeverRunsProvidersOrAuthorizesExecution() {
        val calls = AtomicInteger()
        register(provider { calls.incrementAndGet(); ProviderResult() }, ProviderPermissions(actions = VettingAction.entries.toSet()))
        val root = Path.of(project.basePath!!)
        val path = root.resolve("demo-report.json")
        val clean = ScanReport(projectPath = root.toString(), verdict = ScanVerdict.CLEAN,
            policyName = "Simulated", findings = emptyList(), scannedFiles = 42, durationMs = 18)
        Files.writeString(path, Json.encodeToString(ScanReport.serializer(), clean))
        val keys = listOf(WrapperGuardDemo.ENABLED_PROPERTY, WrapperGuardDemo.REPORT_PROPERTY)
        val previous = keys.associateWith { System.getProperty(it) }
        try {
            System.setProperty(WrapperGuardDemo.ENABLED_PROPERTY, "true")
            System.setProperty(WrapperGuardDemo.REPORT_PROPERTY, path.toString())
            WrapperGuardSettings.getInstance().state.providerConfigurationPath = root.resolve("missing-provider-config.json").toString()
            val service = project.getService(WrapperGuardProjectService::class.java)
            val displayed = service.scan()!!
            assertEquals(ScanVerdict.CLEAN, displayed.verdict)
            assertEquals(42, displayed.scannedFiles)
            assertTrue(displayed.notices.any { it.code == "WG-DEMO" })
            assertEquals(0, calls.get())
            assertNull(service.computeSeal())
            assertTrue(runGuardedScanOrFailure(project)!!.findings.any {
                it.ruleId == "WG-DEMO-EXECUTION-DISABLED" && VettingAction.BLOCK_BUILD in it.actions
            })
            assertTrue(shouldBlockSync(runGuardedScanOrFailure(project, VettingOperation.SYNC)!!))
            val id = ExternalSystemTaskId.create(GradleConstants.SYSTEM_ID, ExternalSystemTaskType.RESOLVE_PROJECT, project)
            val task = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ExternalSystemTask::class.java)) { _, method, _ ->
                if (method.name == "getId") id else error("Demo must not execute Gradle tasks")
            } as ExternalSystemTask
            try {
                WrapperGuardGradleSyncGate().prepareExecution(task, root.toString(), false,
                    object : ExternalSystemTaskNotificationListenerAdapter() {}, project)
                fail("demonstration mode must refuse actual Gradle sync")
            } catch (error: ExternalSystemException) {
                assertTrue(error.message!!.contains("demonstration mode"))
            }
            assertEquals(0, calls.get())
        } finally {
            previous.forEach { (key, value) -> if (value == null) System.clearProperty(key) else System.setProperty(key, value) }
        }
    }

    private fun provider(scan: (VettingRequest) -> ProviderResult) = object : VettingProvider {
        override val descriptor = ProviderDescriptor("example-sdk", "Example SDK", "1.0")
        override fun scan(request: VettingRequest) = scan.invoke(request)
    }

    private fun finding(actions: Set<VettingAction>) = Finding(
        fingerprint = "provider-owned", ruleId = "SDK-001", title = "SDK finding", message = "Example policy violation",
        severity = Severity.ERROR, confidence = Confidence.HIGH, category = FindingCategory.EXECUTION,
        location = SourceLocation("build.gradle"), actions = actions
    )
}
