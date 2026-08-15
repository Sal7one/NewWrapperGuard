package com.sal7one.wrapperguard.intellij

import com.intellij.execution.impl.RunManagerImpl
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.sal7one.wrapperguard.intellij.execution.WrapperGuardBeforeRunTask
import com.sal7one.wrapperguard.intellij.execution.WrapperGuardBeforeRunTaskProvider

/**
 * Scans the project as early as possible after open. The scanner is pure text analysis
 * (no PSI/index dependency), so this intentionally does NOT wait for smart mode — per the
 * backlog's security-first ordering, WrapperGuard must establish its verdict before the user
 * (or another plugin) can trigger a build, sync, or run.
 *
 * Also wires the file-change watcher (mark STALE immediately, debounced rescan) and back-fills
 * the before-run guard task into run configurations that predate the plugin installation.
 *
 * Honest limitation (documented in BACKLOG.md): this runs post-startup, so it cannot retro-
 * actively stop a Gradle sync that was already in flight when the project opened. The sync
 * gate (planned) closes that window further.
 */
class WrapperGuardStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val service = project.getService(WrapperGuardProjectService::class.java)
        WrapperGuardVfsListener.register(project)
        backfillBeforeRunTasks(project)
        // scanAsync runs on a background task; the Compose UI observes the result.
        service.scanAsync()
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

/**
 * Adds [WrapperGuardBeforeRunTask] to existing applicable run configurations that predate
 * the plugin installation (new configurations receive it via
 * [WrapperGuardBeforeRunTaskProvider.createTask]). Ported from old_project's
 * ProjectStartup via RunManagerImpl.
 *
 * Top-level and internal so the guard smoke tests can exercise it directly.
 */
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
