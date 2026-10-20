package com.sal7one.wrapperguard.core.vetting

import com.sal7one.wrapperguard.core.model.*
import com.sal7one.wrapperguard.core.spi.ScannerEngine

/** Retains the deterministic scanner and its existing fingerprints/baselines. */
class BuiltInVettingProvider(private val engine: ScannerEngine) : VettingProvider {
    override val descriptor = ProviderDescriptor("wrapperguard", "WrapperGuard", "0.5.0")

    override fun scan(request: VettingRequest): ProviderResult {
        val report = engine.scan(request.projectRoot, request.policy, request.settings, request.baseline)
        return ProviderResult(
            findings = report.findings.map { finding ->
                finding.copy(actions = buildSet {
                    if (finding.kind == FindingKind.ACTIONABLE && finding.severity != Severity.INFO) {
                        add(if (finding.severity == Severity.ERROR) VettingAction.MAJOR_WARNING else VettingAction.WARNING)
                        if (request.policy.actionFor(finding) == PolicyAction.BLOCK) {
                            add(VettingAction.BLOCK_BUILD)
                            add(VettingAction.BLOCK_SYNC)
                        }
                    }
                })
            },
            edges = report.edges,
            scannedFiles = report.scannedFiles,
            complete = report.complete,
            notices = report.notices
        )
    }

    companion object {
        val DEFAULT_PERMISSIONS = ProviderPermissions(
            actions = VettingAction.entries.toSet(),
            required = true
        )
    }
}
