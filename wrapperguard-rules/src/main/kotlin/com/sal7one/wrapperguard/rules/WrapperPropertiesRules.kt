package com.sal7one.wrapperguard.rules

import com.sal7one.wrapperguard.core.model.*
import com.sal7one.wrapperguard.core.policy.ScanSettings
import com.sal7one.wrapperguard.core.spi.Rule
import com.sal7one.wrapperguard.core.spi.TextSource
import com.sal7one.wrapperguard.core.util.Fingerprints
import com.sal7one.wrapperguard.core.util.UrlAllowlist

/**
 * Analyzes `gradle-wrapper.properties`. Ports old_project's analyzeWrapperPropertiesContent.
 * Checks: missing distributionUrl, non-HTTPS host, host not allowlisted, pre-release marker,
 * and (if required) missing distributionSha256Sum.
 */
object WrapperPropertiesRules : Rule {
    override val id: String = "WG-WRAPPER-PROPS"

    private val DISTRIBUTION_URL = Regex("""^\s*distributionUrl\s*=\s*(.+)$""", RegexOption.MULTILINE)
    private val PRE_RELEASE = Regex("""-snapshot-|nightly|release-candidate|rc-""", RegexOption.IGNORE_CASE)
    private val SHA256_PRESENT = Regex("""^\s*distributionSha256Sum\s*=""", RegexOption.MULTILINE)

    override fun analyze(source: TextSource, settings: ScanSettings): List<Finding> {
        val findings = mutableListOf<Finding>()

        val urlMatch = DISTRIBUTION_URL.find(source.content)
        if (urlMatch == null) {
            findings += base(
                RuleIds.WRAPPER_MISSING_DISTRIBUTION_URL,
                "Missing Gradle distribution URL",
                "Add a distributionUrl that points to an approved HTTPS Gradle distribution.",
                source, null, "distributionUrl=", Severity.ERROR, Confidence.HIGH,
                FindingCategory.WRAPPER
            )
        } else {
            val rawUrl = urlMatch.groupValues[1].trim()
            val url = rawUrl.replace("\\:", ":")
            val lineNumber = lineNumber(source.content, urlMatch.range.first)

            if (!url.startsWith("https://")) {
                findings += base(
                    RuleIds.WRAPPER_NOT_HTTPS,
                    "Gradle distribution is not loaded over HTTPS",
                    "Use an HTTPS distribution URL.",
                    source, lineNumber, url, Severity.ERROR, Confidence.HIGH,
                    FindingCategory.WRAPPER
                )
            }

            if (!UrlAllowlist.isAllowed(url, settings.allowedHosts, settings.allowedUrlPatterns)) {
                findings += base(
                    RuleIds.WRAPPER_HOST_NOT_ALLOWLISTED,
                    "Gradle distribution host is not allowlisted",
                    "Use services.gradle.org/downloads.gradle.org or add your trusted mirror in WrapperGuard settings.",
                    source, lineNumber, url, Severity.ERROR, Confidence.HIGH,
                    FindingCategory.SUPPLY_CHAIN
                )
            }

            if (PRE_RELEASE.containsMatchIn(url)) {
                findings += base(
                    RuleIds.WRAPPER_PRE_RELEASE,
                    "Gradle distribution appears to be a pre-release or nightly build",
                    "Prefer pinned stable Gradle distributions for reproducible builds.",
                    source, lineNumber, url, Severity.WARN, Confidence.MEDIUM,
                    FindingCategory.SUPPLY_CHAIN
                )
            }
        }

        if (settings.requireDistributionSha256 && !SHA256_PRESENT.containsMatchIn(source.content)) {
            findings += base(
                RuleIds.WRAPPER_CHECKSUM_MISSING,
                "Gradle distribution checksum is missing",
                "Add distributionSha256Sum to pin the wrapper download artifact.",
                source, null, "distributionSha256Sum=", Severity.WARN, Confidence.HIGH,
                FindingCategory.WRAPPER
            )
        }

        return findings
    }

    private fun base(
        ruleId: String, title: String, remediation: String, source: TextSource,
        lineNumber: Int?, evidence: String, severity: Severity, confidence: Confidence,
        category: FindingCategory
    ) = Finding(
        fingerprint = Fingerprints.finding(ruleId, source.relativePath, evidence),
        ruleId = ruleId, title = title, message = title,
        severity = severity, confidence = confidence, category = category,
        location = SourceLocation(source.relativePath, lineNumber),
        evidence = Evidence(evidence), remediation = remediation
    )

    private fun lineNumber(content: String, charIndex: Int): Int =
        content.substring(0, charIndex.coerceAtMost(content.length)).count { it == '\n' } + 1
}
