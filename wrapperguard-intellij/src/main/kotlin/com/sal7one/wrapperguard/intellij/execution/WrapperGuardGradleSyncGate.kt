package com.sal7one.wrapperguard.intellij.execution

import com.intellij.openapi.externalSystem.model.ExternalSystemException
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTask
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemExecutionAware
import com.intellij.openapi.project.Project
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.progress.ProcessCanceledException
import com.sal7one.wrapperguard.core.model.FindingStatus
import com.sal7one.wrapperguard.core.model.ScanReport
import com.sal7one.wrapperguard.core.vetting.VettingAction
import com.sal7one.wrapperguard.core.vetting.VettingOperation
import com.sal7one.wrapperguard.intellij.WrapperGuardProjectService
import java.nio.file.Path
import com.sal7one.wrapperguard.intellij.demo.WrapperGuardDemo

/**
 * Runs before Gradle's resolver, including automatic sync and initial project linking.
 * The platform calls this extension from ExternalSystemResolveProjectTask.doExecute before
 * obtaining the remote resolver. This is separate from run-configuration interception.
 * The public ExternalSystemExecutionAware API is currently marked experimental by JetBrains.
 */
class WrapperGuardGradleSyncGate : ExternalSystemExecutionAware {
    override fun prepareExecution(
        task: ExternalSystemTask,
        externalProjectPath: String,
        isPreviewMode: Boolean,
        taskNotificationListener: ExternalSystemTaskNotificationListener,
        project: Project
    ) {
        if (task.id.type != ExternalSystemTaskType.RESOLVE_PROJECT) return
        if (project.isDisposed) throw ProcessCanceledException()
        if (WrapperGuardDemo.enabled || project.getService(WrapperGuardProjectService::class.java).isDemo) {
            throw ExternalSystemException("WrapperGuard demonstration mode: Gradle sync is disabled. Simulated data cannot authorize execution.")
        }
        var report = runGuardedScanOrFailure(project, VettingOperation.SYNC, Path.of(externalProjectPath))
            ?: guardFailureReport(project, VettingOperation.SYNC)
        if (project.isDisposed) throw ProcessCanceledException()
        if (!TrustedProjects.isProjectTrusted(project)) report = untrustedProjectReport(project, VettingOperation.SYNC)
        project.getService(WrapperGuardProjectService::class.java).publishExternalReport(report)
        if (shouldBlockSync(report)) {
            // Only a fixed host message reaches Gradle output; provider evidence may be sensitive.
            throw ExternalSystemException("WrapperGuard stopped Gradle sync. Review blocking findings in the WrapperGuard tool window.")
        }
    }
}

internal fun shouldBlockSync(report: ScanReport): Boolean = report.findings.any {
    it.status == FindingStatus.ACTIVE && VettingAction.BLOCK_SYNC in it.actions
}
