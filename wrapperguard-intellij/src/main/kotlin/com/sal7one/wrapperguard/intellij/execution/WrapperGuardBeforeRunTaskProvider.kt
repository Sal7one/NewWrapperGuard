package com.sal7one.wrapperguard.intellij.execution

import com.intellij.execution.BeforeRunTask
import com.intellij.execution.BeforeRunTaskProvider
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration
import com.intellij.openapi.progress.ProgressManager
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
import com.sal7one.wrapperguard.core.model.ScanVerdict
import com.sal7one.wrapperguard.core.model.Severity
import com.sal7one.wrapperguard.core.model.SourceLocation
import com.sal7one.wrapperguard.core.policy.EnforcementMode
import com.sal7one.wrapperguard.core.policy.ScanPolicy
import com.sal7one.wrapperguard.core.util.Fingerprints
import com.sal7one.wrapperguard.intellij.settings.buildScanSettings
import com.sal7one.wrapperguard.intellij.WrapperGuardProjectService
import com.sal7one.wrapperguard.rules.RepositoryScanner
import com.sal7one.wrapperguard.rules.RuleIds
import java.nio.file.Path

/**
 * Marker before-run task; carries no state. One enabled instance is attached to each
 * applicable run configuration.
 */
class WrapperGuardBeforeRunTask : BeforeRunTask<WrapperGuardBeforeRunTask>(WrapperGuardBeforeRunTaskProvider.KEY)

/**
 * Runs a fresh WrapperGuard scan before every applicable run configuration and hard-blocks
 * the run (returns false) when the policy marks any finding BLOCK. This is the core
 * "guard" promise: policy-selected malicious patterns cannot execute through IDE run
 * configurations before a scan has cleared them.
 *
 * Scope (stated honestly in the UI too): covers IDE Gradle/script run configurations.
 * Does NOT cover external terminals, CLI invocations, or other plugins' processes.
 */
open class WrapperGuardBeforeRunTaskProvider : BeforeRunTaskProvider<WrapperGuardBeforeRunTask>() {

    companion object {
        val KEY: Key<WrapperGuardBeforeRunTask> = Key.create("WrapperGuard.BeforeRunTask")

        /** Applicable = Gradle/ExternalSystem/shell-script run configurations. */
        fun isApplicable(configuration: RunConfiguration): Boolean {
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
        if (!isApplicable(configuration)) return true

        val project = configuration.project
        // The execution seal must reflect what will actually run: flush unsaved editor
        // documents before scanning (backlog #4: save unsaved documents, or account for them).
        FileDocumentManager.getInstance().saveAllDocuments()

        val report = if (ApplicationManager.getApplication().isUnitTestMode) {
            // Headless smoke tests drive the same path without the modal progress wrapper.
            runCatching { runGuardScan(project) }.getOrNull()
        } else {
            var result: ScanReport? = null
            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                { result = runGuardedScanWithSeal(project) },
                "WrapperGuard: Scanning project before run…",
                false,
                project
            )
            result
        }
        // No project path / scan failed open — do not block builds on scanner failure.
        val result = report ?: return true

        val blockers = result.findings.filter {
            it.policyAction == PolicyAction.BLOCK && it.status == FindingStatus.ACTIVE
        }
        if (blockers.isEmpty()) return true

        // Publish the fresh report so the tool window shows what blocked the run.
        project.getService(WrapperGuardProjectService::class.java).publishExternalReport(result)

        return askUserToRunOnce(project, configuration, blockers)
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
    ): Boolean = ExecutionBlockedDialog(project, configuration, blockers).showAndGetRunOnce()
}

/**
 * Fresh ENFORCE-mode scan shared by the run guard — the guard decides with blocking
 * enabled. Top-level so the smoke tests can exercise the exact production path.
 */
internal fun runGuardScan(project: Project): ScanReport? {
    val root = project.basePath?.let(Path::of) ?: return null
    return RepositoryScanner().scan(root, ScanPolicy(mode = EnforcementMode.ENFORCE), buildScanSettings())
}

/**
 * TOCTOU execution seal (backlog #3): computes the content-hash seal of every watched file
 * before and after the scan. A file that changed mid-scan triggers one retry on the new
 * content; a project that is still churning returns a synthetic WG-SCAN-TOCTOU blocker so
 * the launch is refused rather than allowed on unverified content.
 */
internal fun runGuardedScanWithSeal(project: Project): ScanReport? {
    val service = project.getService(WrapperGuardProjectService::class.java)
    val sealBefore = service.computeSeal() ?: return runCatching { runGuardScan(project) }.getOrNull()
    var report = runCatching { runGuardScan(project) }.getOrNull()
    val sealAfter = service.computeSeal()
    if (sealAfter != sealBefore) {
        report = runCatching { runGuardScan(project) }.getOrNull()
        if (service.computeSeal() != sealAfter) {
            return toctouBlockedReport(project)
        }
    }
    return report
}

/** Synthetic blocker used when protected files keep changing during the pre-run scan. */
internal fun toctouBlockedReport(project: Project): ScanReport {
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
        status = FindingStatus.ACTIVE
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
