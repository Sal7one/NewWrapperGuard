package com.sal7one.wrapperguard.intellij

import com.intellij.execution.impl.RunManagerImpl
import com.intellij.ide.trustedProjects.TrustedProjectsListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.externalSystem.service.project.manage.ProjectDataImportListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.sal7one.wrapperguard.intellij.execution.WrapperGuardBeforeRunTask
import com.sal7one.wrapperguard.intellij.execution.WrapperGuardBeforeRunTaskProvider
import com.sal7one.wrapperguard.intellij.execution.runGuardScan

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
        registerSyncGate(project)
        registerTrustListener(project)
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
/**
 * Gradle-sync gate (#1). The BLOCKING half is provided by the before-run guard: a Gradle
 * sync executes through an [com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration],
 * which [WrapperGuardBeforeRunTaskProvider.isApplicable] covers, so a sync is hard-blocked
 * exactly like any other Gradle run when ENFORCE finds blockers.
 *
 * This half re-validates AFTER each import finishes: the report then reflects what the sync
 * actually executed (dependencies resolved, wrapper files rewritten), and any new blocker
 * surfaces in the tool window immediately.
 */
private fun rescanOnTrustChange(project: Project) {
    if (project.isDisposed) return
    project.getService(WrapperGuardProjectService::class.java).scanAsync()
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

internal fun registerSyncGate(project: Project) {
    project.messageBus.connect(project).subscribe(
        ProjectDataImportListener.TOPIC,
        object : ProjectDataImportListener {
            override fun onFinalTasksFinished(projectPath: String?) {
                if (project.isDisposed) return
                ApplicationManager.getApplication().executeOnPooledThread {
                    val report = runCatching { runGuardScan(project) }.getOrNull() ?: return@executeOnPooledThread
                    project.getService(WrapperGuardProjectService::class.java).publishExternalReport(report)
                }
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
