package com.sal7one.wrapperguard.core.spi

import com.sal7one.wrapperguard.core.model.*
import com.sal7one.wrapperguard.core.policy.ScanPolicy
import com.sal7one.wrapperguard.core.policy.ScanSettings
import java.nio.file.Path


/**
 * A source file passed to a [Rule]. Carries its absolute [path], project-relative path
 * (used for stable fingerprints and report output), and text content.
 */
data class TextSource(
    val path: Path,
    val relativePath: String,
    val content: String
)

/**
 * A single detector that produces [Finding]s from one source file.
 *
 * Rules are IDE-neutral and stateless: they receive a [TextSource] and the active
 * [ScanSettings], and return findings. Stable [id]s make baselines and policy possible.
 */
interface Rule {
    val id: String
    fun analyze(source: TextSource, settings: ScanSettings): List<Finding>
}

/**
 * Convenience for rules that don't depend on settings (most pattern-based detectors).
 */
abstract class StatelessRule(override val id: String) : Rule {
    final override fun analyze(source: TextSource, settings: ScanSettings): List<Finding> =
        analyze(source)
    protected abstract fun analyze(source: TextSource): List<Finding>
}

/**
 * A reference from one script to another, discovered by recursion. Populates
 * [ScanReport.edges] so callers can render a call graph.
 */
data class ScriptReference(
    val from: SourceLocation,
    val to: SourceLocation,
    val viaCommand: String
)

/**
 * A recursive script scanner. The default implementation lives in `wrapperguard-rules`.
 */
interface ScannerEngine {
    fun scan(projectRoot: Path, policy: ScanPolicy, settings: ScanSettings): ScanReport
}

/**
 * Optional research-backed ML scorer extension point.
 *
 * INTENTIONALLY INACTIVE: the deterministic scanner is the only active detection system.
 * ML output must never affect verdicts, policy, severity, or execution blocking. This
 * interface exists only so a future, separately-delivered PowerShell detector can be
 * plugged in once the researcher provides the follow-up paper and integration guidance.
 * [NoopMlScorer] remains the default and only implementation.
 */
fun interface MlScorer {
    fun scorePowerShell(source: TextSource): MlSignal?
}

object NoopMlScorer : MlScorer {
    override fun scorePowerShell(source: TextSource): MlSignal? = null
}
