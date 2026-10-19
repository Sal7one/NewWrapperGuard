package com.sal7one.wrapperguard.intellij

import com.intellij.execution.impl.RunManagerImpl
import com.intellij.ide.trustedProjects.TrustedProjectsListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.externalSystem.service.project.manage.ProjectDataImportListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.sal7one.wrapperguard.intellij.execution.WrapperGuardBeforeRunTask
import com.sal7one.wrapperguard.intellij.execution.WrapperGuardBeforeRunTaskProvider
import com.sal7one.wrapperguard.intellij.settings.WrapperGuardSettings
import com.sal7one.wrapperguard.intellij.demo.WrapperGuardDemo
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ToolWindowType
import java.awt.GraphicsEnvironment
import javax.swing.SwingUtilities

/**
 * Scans the project as early as possible after open. The scanner is pure text analysis
 * (no PSI/index dependency), so this intentionally does NOT wait for smart mode.
 *
 * Also wires the file-change watcher (mark STALE immediately, debounced rescan) and back-fills
 * the before-run guard task into run configurations that predate the plugin installation.
 *
 * Honest limitation (documented in HANDOVER.md): this runs post-startup, so it cannot
 * retroactively stop a Gradle sync already in flight. Before-run interception and
 * post-import revalidation complement startup scanning.
 */
class WrapperGuardStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val service = project.getService(WrapperGuardProjectService::class.java)
        if (service.isDemo) {
            backfillBeforeRunTasks(project)
            service.scanAsync()
            WrapperGuardDemo.writePid()
            showDemoWorkspace(project)
            return
        }
        WrapperGuardVfsListener.register(project)
        backfillBeforeRunTasks(project)
        registerPostSyncRescan(project)
        registerTrustListener(project)
        // scanAsync runs on a background task; the Compose UI observes the result.
        if (WrapperGuardSettings.getInstance().state.scanOnOpen) service.scanAsync()
        // Dev/sandbox-only convenience (set by the runIde task): open the tool window on
        // startup so UI screenshots and visual checks do not need a manual click. Inert
        // in production — the property is only set by the Gradle sandbox task.
        if (System.getProperty("wrapperguard.autoshow") == "true") {
            ApplicationManager.getApplication().invokeLater {
                com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                    .getToolWindow("WrapperGuard")?.activate(null)
            }
        }
    }
}

/** Floats the existing native tool window; the screenshot harness does not rebuild its UI. */
private fun showDemoWorkspace(project: Project) {
    ApplicationManager.getApplication().invokeLater {
        if (project.isDisposed) return@invokeLater
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("WrapperGuard") ?: return@invokeLater
        val dimensions = System.getProperty(WrapperGuardDemo.WINDOW_PROPERTY)
            ?.let { Regex("([0-9]{3,4})x([0-9]{3,4})").matchEntire(it) }
        if (dimensions == null) {
            toolWindow.activate(null)
        } else {
            toolWindow.setType(ToolWindowType.FLOATING, Runnable {
                toolWindow.activate(Runnable {
                    SwingUtilities.getWindowAncestor(toolWindow.component)?.let { window ->
                        val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
                        val width = dimensions.groupValues[1].toInt().coerceIn(640, maxOf(640, screen.width))
                        val height = dimensions.groupValues[2].toInt().coerceIn(480, maxOf(480, screen.height))
                        window.setSize(width, height)
                        window.setLocationRelativeTo(null)
                        window.toFront()
                    }
                })
            })
        }
    }
}

private fun rescanOnTrustChange(project: Project) {
    if (project.isDisposed) return
    project.getService(WrapperGuardProjectService::class.java).settingsChanged()
}

/** Re-scans whenever the project's trust state changes (trusted ⇄ untrusted). */
internal fun registerTrustListener(project: Project) {
    project.messageBus.connect(project).subscribe(
        TrustedProjectsListener.TOPIC,
        object : TrustedProjectsListener {
            override fun onProjectTrusted(trustedProject: com.intellij.openapi.project.Project) {
                rescanOnTrustChange(project)
            }

            override fun onProjectUntrusted(untrustedProject: com.intellij.openapi.project.Project) {
                rescanOnTrustChange(project)
            }
        }
    )
}

/** Post-import refresh complements the separately registered pre-resolver sync veto. */
internal fun registerPostSyncRescan(project: Project) {
    project.messageBus.connect(project).subscribe(
        ProjectDataImportListener.TOPIC,
        object : ProjectDataImportListener {
            override fun onFinalTasksFinished(projectPath: String?) {
                if (project.isDisposed) return
                project.getService(WrapperGuardProjectService::class.java).scanAsync()
            }
        }
    )
}

internal fun backfillBeforeRunTasks(project: Project) {
    val action = Runnable {
        if (project.isDisposed) return@Runnable
        val runManager = RunManagerImpl.getInstanceImpl(project)
        for (settings in runManager.allSettings) {
            val config = settings.configuration
            if (!WrapperGuardBeforeRunTaskProvider.isApplicable(config)) continue
            val tasks = runManager.getBeforeRunTasks(config)
            if (tasks.none { it.providerId == WrapperGuardBeforeRunTaskProvider.KEY }) {
                val newTask = WrapperGuardBeforeRunTask().also { it.isEnabled = true }
                runManager.setBeforeRunTasks(config, tasks + newTask)
            }
        }
    }
    if (ApplicationManager.getApplication().isUnitTestMode) action.run()
    else ApplicationManager.getApplication().invokeLater(action)
}

/** Dispatcher helper used by the startup activity so scans run off the EDT/coroutine context. */
internal fun dispatchScan(project: Project) {
    ApplicationManager.getApplication().executeOnPooledThread {
        project.getService(WrapperGuardProjectService::class.java).scan()
    }
}
