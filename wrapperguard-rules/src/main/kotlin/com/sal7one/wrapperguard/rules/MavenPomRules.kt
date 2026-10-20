package com.sal7one.wrapperguard.rules

import com.sal7one.wrapperguard.core.model.*
import com.sal7one.wrapperguard.core.policy.ScanSettings
import com.sal7one.wrapperguard.core.spi.Rule
import com.sal7one.wrapperguard.core.spi.TextSource
import com.sal7one.wrapperguard.core.util.CredentialRedaction
import com.sal7one.wrapperguard.core.util.Fingerprints
import com.sal7one.wrapperguard.core.util.UrlAllowlist
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** Bounded static POM observations, without resolving parents, profiles or dependencies. */
object MavenPomRules : Rule {
    override val id = "maven-pom"
    private val opaque = Regex("<!--[\\s\\S]*?-->|<!\\[CDATA\\[[\\s\\S]*?]]>")
    private val repositories = Regex("<((?:[A-Za-z_][\\w.-]*:)?(?:repository|pluginRepository))\\b[^>]*>([\\s\\S]*?)</\\1\\s*>")
    private val urls = Regex("<(?:[A-Za-z_][\\w.-]*:)?url\\b[^>]*>([^<]*)</(?:[A-Za-z_][\\w.-]*:)?url\\s*>")
    private val versions = Regex("<(?:[A-Za-z_][\\w.-]*:)?version\\b[^>]*>([^<]*)</(?:[A-Za-z_][\\w.-]*:)?version\\s*>")

    /** External declarations and malformed XML reduce coverage instead of resolving anything. */
    fun isSupported(source: TextSource): Boolean = runCatching {
        require(!source.content.contains("<!DOCTYPE", true) && !source.content.contains("<!ENTITY", true))
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isXIncludeAware = false
            isExpandEntityReferences = false
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }
        val builder = factory.newDocumentBuilder().apply {
            setErrorHandler(DefaultHandler())
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
        }
        val document = builder.parse(InputSource(StringReader(source.content)))
        document.documentElement.localName == "project"
    }.getOrDefault(false)

    override fun analyze(source: TextSource, settings: ScanSettings): List<Finding> {
        if (!isSupported(source)) return emptyList()
        // Keep offsets stable while ignoring markup examples inside comments/CDATA.
        val text = opaque.replace(source.content) { " ".repeat(it.value.length) }
        val findings = mutableListOf<Finding>()
        repositories.findAll(text).forEach { repository ->
            urls.findAll(repository.groupValues[2]).forEach { match ->
                val url = decodeXmlText(match.groupValues[1]).trim()
                if (url.isBlank() || url.contains("\${")) return@forEach
                val offset = repository.range.first + repository.value.indexOf(repository.groupValues[2]) + match.range.first
                val line = text.take(offset).count { it == '\n' } + 1
                when {
                    !url.startsWith("https://", ignoreCase = true) -> findings += finding(source, RuleIds.MAVEN_REPO_NOT_HTTPS,
                        "Maven repository does not use HTTPS", "Use an approved HTTPS repository URL.", FindingCategory.REPOSITORY, line, url)
                    !UrlAllowlist.isHostAllowed(url, settings.knownSafeRepositoryHosts) -> findings += finding(source, RuleIds.MAVEN_REPO_NOT_ALLOWLISTED,
                        "Maven repository host is not allowlisted", "Review the repository owner and configure an approved repository host.", FindingCategory.REPOSITORY, line, url)
                }
            }
        }
        versions.findAll(text).forEach { match ->
            val version = decodeXmlText(match.groupValues[1]).trim()
            if (version.contains('+') || version.contains("SNAPSHOT", true) || version.uppercase() in setOf("LATEST", "RELEASE") || version.startsWith('[') || version.startsWith('(')) {
                findings += finding(source, RuleIds.MAVEN_UNPINNED_VERSION, "Maven version is not pinned",
                    "Pin a reviewed exact version for reproducible dependency resolution.", FindingCategory.SUPPLY_CHAIN,
                    text.take(match.range.first).count { it == '\n' } + 1, version)
            }
        }
        return findings
    }

    private fun finding(source: TextSource, ruleId: String, title: String, remediation: String, category: FindingCategory, line: Int, raw: String): Finding {
        val (evidence, redacted) = CredentialRedaction.mask(raw.take(160))
        return Finding(fingerprint = Fingerprints.finding(ruleId, source.relativePath, evidence), ruleId = ruleId,
            title = title, message = title, severity = Severity.WARN, confidence = Confidence.MEDIUM,
            category = category, location = SourceLocation(source.relativePath, line), evidence = Evidence(evidence, redacted), remediation = remediation)
    }

    private fun decodeXmlText(value: String): String = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);").replace(value) { match ->
        when (val entity = match.groupValues[1]) {
            "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"
            else -> runCatching { String(Character.toChars(if (entity.startsWith("#x")) entity.drop(2).toInt(16) else entity.drop(1).toInt())) }.getOrDefault(match.value)
        }
    }
}
