package com.sal7one.wrapperguard.rules

import com.sal7one.wrapperguard.core.model.*
import com.sal7one.wrapperguard.core.spi.Rule
import com.sal7one.wrapperguard.core.spi.TextSource
import com.sal7one.wrapperguard.core.util.Fingerprints

class RegexRule(
    override val id: String,
    private val title: String,
    private val message: String,
    private val pattern: Regex,
    private val severity: Severity,
    private val confidence: Confidence,
    private val category: FindingCategory,
    private val remediation: String
) : Rule {
    override fun analyze(source: TextSource): List<Finding> = source.content.lineSequence()
        .mapIndexedNotNull { index, line ->
            if (!pattern.containsMatchIn(line) || line.trimStart().startsWith("#")) return@mapIndexedNotNull null
            Finding(
                fingerprint = Fingerprints.finding(id, source.relativePath, line),
                ruleId = id,
                title = title,
                message = message,
                severity = severity,
                confidence = confidence,
                category = category,
                location = SourceLocation(source.relativePath, index + 1),
                evidence = Evidence(line.trim()),
                remediation = remediation
            )
        }.toList()
}
