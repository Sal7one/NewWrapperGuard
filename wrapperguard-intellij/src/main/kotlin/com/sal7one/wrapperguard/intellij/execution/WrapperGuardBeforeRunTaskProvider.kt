package com.sal7one.wrapperguard.intellij.execution

import com.intellij.execution.BeforeRunTask
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.execution.BeforeRunTaskProvider
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.sal7one.wrapperguard.core.model.Confidence
import com.sal7one.wrapperguard.core.model.Evidence
import com.sal7one.wrapperguard.core.model.Finding
import com.sal7one.wrapperguard.core.model.FindingCategory
import com.sal7one.wrapperguard.core.model.FindingKind
import com.sal7one.wrapperguard.core.model.FindingStatus
import com.sal7one.wrapperguard.core.model.PolicyAction
import com.sal7one.wrapperguard.core.model.ScanReport
import com.sal7one.wrapperguard.core.model.ScanNotice
import com.sal7one.wrapperguard.core.model.ScanVerdict
import com.sal7one.wrapperguard.core.model.Severity
import com.sal7one.wrapperguard.core.model.SourceLocation
import com.sal7one.wrapperguard.core.policy.EnforcementMode
import com.sal7one.wrapperguard.core.policy.ScanPolicy
import com.sal7one.wrapperguard.core.util.Fingerprints
import com.sal7one.wrapperguard.intellij.WrapperGuardProjectService
import com.sal7one.wrapperguard.intellij.vetting.IntellijVettingProviders
import com.sal7one.wrapperguard.core.vetting.VettingAction
import com.sal7one.wrapperguard.core.vetting.VettingOperation
import com.sal7one.wrapperguard.rules.RuleIds
import com.sal7one.wrapperguard.intellij.demo.WrapperGuardDemo
import java.nio.file.Path
import java.util.concurrent.CancellationException

/**
 * Marker before-run task; carries no state. One enabled instance is attached to each
 * applicable run configuration.
 */
class WrapperGuardBeforeRunTask : BeforeRunTask<WrapperGuardBeforeRunTask>(WrapperGuardBeforeRunTaskProvider.KEY)

/**
 * Runs enabled providers before applicable run configurations. Active findings with a
 * host-authorized BLOCK_BUILD action require cancellation or an explicit Run Once override.
 *
 * Scope (stated honestly in the UI too): covers IDE Gradle/script run configurations.
 * Does NOT cover external terminals, CLI invocations, or other plugins' processes.
 */
open class WrapperGuardBeforeRunTaskProvider : BeforeRunTaskProvider<WrapperGuardBeforeRunTask>(), com.intellij.openapi.project.DumbAware {

    companion object {
        val KEY: Key<WrapperGuardBeforeRunTask> = Key.create("WrapperGuard.BeforeRunTask")

        /** Applicable = Gradle/ExternalSystem/shell-script run configurations. */
        fun isApplicable(configuration: RunConfiguration): Boolean {
            if (WrapperGuardDemo.enabled) return true
            val typeId = configuration.type.id.lowercase()
            return configuration is ExternalSystemRunConfiguration ||
                typeId.contains("gradle") ||
                typeId.contains("shell") ||
                typeId.contains("sh_run") ||
                typeId.contains("shrun") ||
                typeId == "sh" ||
                typeId.endsWith("shellscript")
        }
    }

    override fun getId(): Key<WrapperGuardBeforeRunTask> = KEY
    override fun getName(): String = "WrapperGuard Security Scan"
    override fun getDescription(task: WrapperGuardBeforeRunTask): String =
        "Run a WrapperGuard security scan before execution"

    override fun isConfigurable(): Boolean = false

    /** Auto-attach an enabled task to every applicable new run configuration. */
    override fun createTask(runConfiguration: RunConfiguration): WrapperGuardBeforeRunTask? =
        if (isApplicable(runConfiguration)) WrapperGuardBeforeRunTask().also { it.isEnabled = true }
        else null

    override fun executeTask(
        context: DataContext,
        configuration: RunConfiguration,
        env: ExecutionEnvironment,
        task: WrapperGuardBeforeRunTask
    ): Boolean {
        val project = configuration.project
        if (project.isDisposed) return false
        if (WrapperGuardDemo.enabled || project.getService(WrapperGuardProjectService::class.java).isDemo) return false
        if (!isApplicable(configuration)) return true
        // The execution seal must reflect what will actually run: flush unsaved editor
        // documents before scanning (backlog #4: save unsaved documents, or account for them).
        val application = ApplicationManager.getApplication()
        val saveDocuments = Runnable { FileDocumentManager.getInstance().saveAllDocuments() }
        if (application.isDispatchThread) saveDocuments.run() else application.invokeAndWait(saveDocuments)

        val report = if (ApplicationManager.getApplication().isUnitTestMode) {
            // Headless smoke tests drive the same path without the modal progress wrapper.
            runGuardedScanOrFailure(project)
        } else {
            var result: ScanReport? = null
            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                { result = runGuardedScanOrFailure(project) },
                "WrapperGuard: Scanning project before run…",
                false,
                project
            )
            result
        }
        // A configured guard must establish a fresh result before execution.
        val result = report ?: return false
        if (project.isDisposed) return false
        if (!TrustedProjects.isProjectTrusted(project)) {
            project.getService(WrapperGuardProjectService::class.java)
                .publishExternalReport(untrustedProjectReport(project, VettingOperation.BUILD), VettingOperation.BUILD)
            return false
        }

        project.getService(WrapperGuardProjectService::class.java).publishExternalReport(result, VettingOperation.BUILD)

        val blockers = result.findings.filter {
            it.status == FindingStatus.ACTIVE && VettingAction.BLOCK_BUILD in it.actions
        }
        if (blockers.isEmpty()) return true

        val accepted = askUserToRunOnce(project, configuration, blockers)
        // Trust can be revoked while the confirmation dialog is open.
        return accepted && !project.isDisposed && TrustedProjects.isProjectTrusted(project)
    }

    /**
     * Test seam: asks the user and returns true only on explicit "Run Once" acceptance.
     * The default implementation shows [ExecutionBlockedDialog]; smoke tests override it to
     * assert that the dialog would be raised and to simulate the user's choice.
     */
    protected open fun askUserToRunOnce(
        project: Project,
        configuration: RunConfiguration,
        blockers: List<Finding>
    ): Boolean = runDecisionOnEdt { ExecutionBlockedDialog(project, configuration, blockers).showAndGetRunOnce() }
}

/** Before-run providers execute on worker threads; Swing dialog creation and display belong on the EDT. */
internal fun runDecisionOnEdt(decision: () -> Boolean): Boolean {
    val application = ApplicationManager.getApplication()
    if (application.isDispatchThread) return decision()
    var accepted = false
    application.invokeAndWait({ accepted = decision() }, com.intellij.openapi.application.ModalityState.defaultModalityState())
    return accepted
}

/**
 * Fresh ENFORCE-mode scan shared by the run guard — the guard decides with blocking
 * enabled. Top-level so the smoke tests can exercise the exact production path.
 */
internal fun runGuardScan(
    project: Project,
    operation: VettingOperation = VettingOperation.BUILD,
    root: Path? = project.basePath?.let(Path::of)
): ScanReport? {
    if (WrapperGuardDemo.enabled || project.getService(WrapperGuardProjectService::class.java).isDemo) {
        return demoExecutionReport(project, operation)
    }
    root ?: return null
    if (operation != VettingOperation.SCAN && !TrustedProjects.isProjectTrusted(project)) {
        return untrustedProjectReport(project, operation)
    }
    return IntellijVettingProviders.scan(project, root, ScanPolicy(mode = EnforcementMode.ENFORCE), operation)
}

/**
 * TOCTOU execution seal (backlog #3): computes the content-hash seal of every watched file
 * before and after the scan. A file that changed mid-scan triggers one retry on the new
 * content; a project that is still churning returns a synthetic WG-SCAN-TOCTOU blocker so
 * the launch is refused rather than allowed on unverified content.
 */
internal fun runGuardedScanWithSeal(
    project: Project,
    operation: VettingOperation = VettingOperation.BUILD,
    root: Path? = project.basePath?.let(Path::of)
): ScanReport? {
    if (WrapperGuardDemo.enabled || project.getService(WrapperGuardProjectService::class.java).isDemo) {
        return demoExecutionReport(project, operation)
    }
    root ?: return null
    if (operation != VettingOperation.SCAN && !TrustedProjects.isProjectTrusted(project)) {
        return untrustedProjectReport(project, operation)
    }
    val service = project.getService(WrapperGuardProjectService::class.java)
    val sealBefore = service.computeSeal(root) ?: return guardFailureReport(project, operation)
    var report = runGuardScan(project, operation, root)
    val sealAfter = service.computeSeal(root)
    if (sealAfter != sealBefore) {
        report = runGuardScan(project, operation, root)
        if (sealAfter == null || service.computeSeal(root) != sealAfter) {
            return toctouBlockedReport(project, operation)
        }
    }
    return report
}

/** Preserve IDE cancellation and disposal instead of turning control flow into a failure dialog. */
internal fun runGuardedScanOrFailure(
    project: Project,
    operation: VettingOperation = VettingOperation.BUILD,
    root: Path? = project.basePath?.let(Path::of)
): ScanReport? = try {
    if (project.isDisposed) throw ProcessCanceledException()
    ProgressManager.checkCanceled()
    val report = runGuardedScanWithSeal(project, operation, root)
    if (project.isDisposed) throw ProcessCanceledException()
    ProgressManager.checkCanceled()
    report
} catch (error: ProcessCanceledException) {
    throw error
} catch (error: CancellationException) {
    throw error
} catch (_: Exception) {
    if (project.isDisposed) throw ProcessCanceledException()
    guardFailureReport(project, operation)
}

/** Synthetic blocker used when protected files keep changing during the pre-run scan. */
internal fun toctouBlockedReport(project: Project, operation: VettingOperation = VettingOperation.BUILD): ScanReport {
    val root = project.basePath ?: "<project>"
    val finding = Finding(
        fingerprint = Fingerprints.finding(RuleIds.SCAN_TOCTOU, root, "seal-mismatch"),
        ruleId = RuleIds.SCAN_TOCTOU,
        title = "Project changed while scanning",
        message = "Protected files kept changing during the pre-run scan; the execution seal could not be established.",
        severity = Severity.ERROR,
        confidence = Confidence.HIGH,
        category = FindingCategory.EXECUTION,
        kind = FindingKind.ACTIONABLE,
        location = SourceLocation(root),
        evidence = Evidence("execution seal mismatch"),
        remediation = "Wait for the project to settle, then run again.",
        policyAction = PolicyAction.BLOCK,
        status = FindingStatus.ACTIVE,
        providerId = "wrapperguard.host",
        actions = setOf(if (operation == VettingOperation.SYNC) VettingAction.BLOCK_SYNC else VettingAction.BLOCK_BUILD)
    )
    return ScanReport(
        projectPath = root,
        verdict = ScanVerdict.EXECUTION_BLOCKED,
        policyName = "OSS Defaults",
        findings = listOf(finding),
        scannedFiles = 0,
        durationMs = 0,
        complete = true
    )
}

/** Configuration/host errors never silently discard a user's required-provider policy. */
internal fun guardFailureReport(project: Project, operation: VettingOperation): ScanReport {
    val report = toctouBlockedReport(project, operation)
    return report.copy(complete = false, findings = report.findings.map { it.copy(
        fingerprint = Fingerprints.finding("WG-PROVIDER-CONFIGURATION", project.basePath.orEmpty(), "unavailable"),
        ruleId = "WG-PROVIDER-CONFIGURATION",
        title = "Vetting configuration could not be verified",
        message = "The guard could not load the configured providers or establish its execution seal.",
        evidence = null,
        remediation = "Review the provider configuration in WrapperGuard settings and retry."
    ) })
}

/** Trust is a host execution prerequisite; a paused required SDK cannot silently clear a run. */
internal fun untrustedProjectReport(project: Project, operation: VettingOperation): ScanReport {
    val report = guardFailureReport(project, operation)
    return report.copy(
        findings = report.findings.map { it.copy(
            fingerprint = Fingerprints.finding("WG-PROJECT-UNTRUSTED", project.basePath.orEmpty(), "untrusted-project"),
            ruleId = "WG-PROJECT-UNTRUSTED",
            title = "Project trust is required before execution",
            message = "Third-party providers are paused in Safe Mode. Build and sync require project trust.",
            remediation = "Review the project and manage its trust using the IDE's project trust controls."
        ) },
        notices = listOf(ScanNotice("WG-PROVIDERS-UNTRUSTED", "Third-party providers were not initialized. Built-in static analysis remains available through Scan project."))
    )
}

/** A demo report, including a simulated CLEAN report, never clears a real launch/import. */
internal fun demoExecutionReport(project: Project, operation: VettingOperation): ScanReport {
    val report = guardFailureReport(project, operation)
    return report.copy(findings = report.findings.map { it.copy(
        fingerprint = Fingerprints.finding("WG-DEMO-EXECUTION-DISABLED", project.basePath.orEmpty(), "demo-mode"),
        ruleId = "WG-DEMO-EXECUTION-DISABLED",
        title = "Execution is disabled in demonstration mode",
        message = "Simulated report data cannot authorize IDE builds, runs or Gradle sync.",
        remediation = "Close the demonstration IDE and restart without wrapperguard.demo to use real vetting."
    ) }, notices = listOf(ScanNotice("WG-DEMO", "DEMO • simulated data. Execution is disabled.")))
}
