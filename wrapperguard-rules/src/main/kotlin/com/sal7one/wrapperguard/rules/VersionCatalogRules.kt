package com.sal7one.wrapperguard.rules

import com.sal7one.wrapperguard.core.model.*
import com.sal7one.wrapperguard.core.policy.ScanSettings
import com.sal7one.wrapperguard.core.spi.Rule
import com.sal7one.wrapperguard.core.spi.TextSource
import com.sal7one.wrapperguard.core.util.Fingerprints

/**
 * Analyzes `libs.versions.toml` for dynamic versions (e.g. `latest.release`, `+`).
 * Ports old_project's VersionCatalogAnalyzer.
 */
object VersionCatalogRules : Rule {
    override val id: String = "WG-CATALOG"

    private val DYNAMIC_VERSION = Regex("""=\s*["'](?<version>\+|latest\.release|latest\.integration)["']""", RegexOption.IGNORE_CASE)
    private val INLINE_VERSION = Regex("""version\s*=\s*["'](?<version>\+|latest\.release|latest\.integration)["']""", RegexOption.IGNORE_CASE)

    override fun analyze(source: TextSource, settings: ScanSettings): List<Finding> {
        val findings = mutableListOf<Finding>()
        source.content.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isBlank() || line.startsWith("#")) return@forEachIndexed
            if (DYNAMIC_VERSION.containsMatchIn(line) || INLINE_VERSION.containsMatchIn(line)) {
                findings += Finding(
                    fingerprint = Fingerprints.finding(RuleIds.CATALOG_DYNAMIC_VERSION, source.relativePath, line),
                    ruleId = RuleIds.CATALOG_DYNAMIC_VERSION,
                    title = "Dynamic version in Gradle version catalog",
                    message = "Dynamic version in Gradle version catalog",
                    severity = Severity.WARN, confidence = Confidence.MEDIUM,
                    category = FindingCategory.SUPPLY_CHAIN,
                    location = SourceLocation(source.relativePath, index + 1),
                    evidence = Evidence(line),
                    remediation = "Pin exact versions in libs.versions.toml for reproducible builds."
                )
            }
        }
        return findings.distinctBy { listOf(it.location.path, it.location.line, it.evidence?.text) }
    }
}
