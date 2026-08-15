package com.sal7one.wrapperguard.rules

import com.sal7one.wrapperguard.core.model.*
import com.sal7one.wrapperguard.core.policy.ScanSettings
import com.sal7one.wrapperguard.core.spi.Rule
import com.sal7one.wrapperguard.core.spi.TextSource
import com.sal7one.wrapperguard.core.util.CredentialRedaction
import com.sal7one.wrapperguard.core.util.Fingerprints
import com.sal7one.wrapperguard.core.util.UrlAllowlist
import java.net.URI

/**
 * Analyzes Gradle build scripts (Groovy, Kotlin DSL, settings, init, generic .gradle).
 * Ports old_project's BuildScriptAnalyzer: repositories (with allowlist decision tree),
 * remote `apply from:`, process execution, network clients, dynamic/reflective code,
 * hardcoded credentials, unstable dependencies, mavenLocal(), flatDir{}, and init-script
 * lifecycle hooks. Includes the same `wrapperguard:ignore` suppression handling.
 *
 * [scriptType] is "Groovy"/"Kotlin"/"settings"/"init"/"Gradle" and only affects the wording
 * of process-execution / network findings. Init scripts additionally emit a supply-chain
 * warning and lifecycle-hook findings.
 */
class BuildScriptRules private constructor(
    override val id: String,
    private val scriptType: String,
    private val isInit: Boolean
) : Rule {

    override fun analyze(source: TextSource, settings: ScanSettings): List<Finding> {
        val findings = mutableListOf<Finding>()

        if (isInit) {
            findings += base(
                RuleIds.SUPPLY_INIT_SCRIPT,
                "Gradle init script runs before every build",
                "Review init scripts carefully; they can alter repositories, tasks, and all projects.",
                source, 1, source.path.fileName.toString(),
                Severity.WARN, Confidence.HIGH, FindingCategory.SUPPLY_CHAIN
            )
        }

        source.content.lineSequence().forEachIndexed { index, rawLine ->
            if (hasSuppressionWithoutReason(rawLine)) {
                findings += base(
                    RuleIds.CONFIG_SUPPRESS_NO_REASON,
                    "Suppression has no reason",
                    "Add a short reason after wrapperguard:ignore.",
                    source, index + 1, rawLine.trim(),
                    Severity.INFO, Confidence.LOW, FindingCategory.CONFIGURATION,
                    FindingKind.AUDIT_EVIDENCE
                )
            }
        }

        REPOSITORY_PATTERNS.forEach { pattern ->
            pattern.findAll(source.content).forEach { match ->
                val url = match.groupValues.getOrNull(1).orEmpty()
                findings += checkRepositoryUrl(source, url, match.value, lineNumber(source.content, match.range.first), settings)
            }
        }

        REMOTE_SCRIPT_PATTERNS.forEach { pattern ->
            pattern.findAll(source.content).forEach { match ->
                findings += base(
                    RuleIds.SUPPLY_REMOTE_SCRIPT,
                    "Remote Gradle script inclusion",
                    "Vendor the script, pin it by checksum, or replace it with a reviewed plugin dependency.",
                    source, lineNumber(source.content, match.range.first), match.value.take(160),
                    Severity.ERROR, Confidence.HIGH, FindingCategory.SUPPLY_CHAIN
                )
            }
        }

        PROCESS_EXECUTION_PATTERNS.forEach { pattern ->
            pattern.findAll(source.content).forEach { match ->
                findings += base(
                    RuleIds.EXECUTION_PROCESS,
                    "Process execution in $scriptType build logic",
                    "Keep build logic declarative; review any command execution path.",
                    source, lineNumber(source.content, match.range.first), match.value.take(160),
                    Severity.ERROR, Confidence.HIGH, FindingCategory.EXECUTION
                )
            }
        }

        NETWORK_CODE_PATTERNS.forEach { pattern ->
            pattern.findAll(source.content).forEach { match ->
                findings += base(
                    RuleIds.NETWORK_CLIENT,
                    "Network client usage in $scriptType build logic",
                    "Prefer Gradle dependency resolution with approved repositories.",
                    source, lineNumber(source.content, match.range.first), match.value.take(160),
                    Severity.WARN, Confidence.MEDIUM, FindingCategory.NETWORK
                )
            }
        }

        DYNAMIC_CODE_PATTERNS.forEach { pattern ->
            pattern.findAll(source.content).forEach { match ->
                findings += base(
                    RuleIds.OBFUSCATION_DYNAMIC,
                    "Dynamic or reflective code execution in $scriptType build logic",
                    "Avoid runtime code loading/evaluation in build scripts.",
                    source, lineNumber(source.content, match.range.first), match.value.take(160),
                    Severity.ERROR, Confidence.HIGH, FindingCategory.OBFUSCATION
                )
            }
        }

        CREDENTIAL_PATTERNS.forEach { pattern ->
            pattern.findAll(source.content).forEach { match ->
                val (maskedEvidence, didRedact) = CredentialRedaction.mask(match.value.take(160))
                findings += base(
                    RuleIds.CREDENTIALS_HARDCODED,
                    "Hardcoded credential-like value",
                    "Use environment variables, Gradle credentials providers, or a secret manager.",
                    source, lineNumber(source.content, match.range.first), maskedEvidence,
                    Severity.WARN, Confidence.MEDIUM, FindingCategory.CREDENTIALS,
                    redacted = didRedact
                )
            }
        }

        UNSTABLE_DEP_PATTERNS.forEach { pattern ->
            pattern.findAll(source.content).forEach { match ->
                findings += base(
                    RuleIds.SUPPLY_UNSTABLE_DEP,
                    "Unpinned or changing dependency version",
                    "Pin exact dependency versions for reproducible builds.",
                    source, lineNumber(source.content, match.range.first), match.value.take(160),
                    Severity.WARN, Confidence.MEDIUM, FindingCategory.SUPPLY_CHAIN
                )
            }
        }

        Regex("""\bmavenLocal\s*\(""").findAll(source.content).forEach { match ->
            findings += base(
                RuleIds.REPO_MAVEN_LOCAL,
                "mavenLocal() is enabled",
                "mavenLocal() can make builds depend on unreviewed local artifacts.",
                source, lineNumber(source.content, match.range.first), match.value,
                Severity.WARN, Confidence.MEDIUM, FindingCategory.REPOSITORY
            )
        }

        Regex("""\bflatDir\s*\{""").findAll(source.content).forEach { match ->
            findings += base(
                RuleIds.REPO_FLAT_DIR,
                "flatDir repository is enabled",
                "Prefer a managed repository with checksums and provenance.",
                source, lineNumber(source.content, match.range.first), match.value,
                Severity.WARN, Confidence.MEDIUM, FindingCategory.REPOSITORY
            )
        }

        if (isInit) {
            INIT_SCRIPT_PATTERNS.forEach { pattern ->
                pattern.findAll(source.content).forEach { match ->
                    findings += base(
                        RuleIds.SUPPLY_INIT_LIFECYCLE,
                        "Init script hooks into Gradle lifecycle",
                        "Confirm this lifecycle hook is expected and reviewed.",
                        source, lineNumber(source.content, match.range.first), match.value.take(140),
                        Severity.WARN, Confidence.MEDIUM, FindingCategory.SUPPLY_CHAIN
                    )
                }
            }
        }

        return findings.distinctBy { listOf(it.severity, it.ruleId, it.location.path, it.location.line, it.evidence?.text) }
    }

    private fun checkRepositoryUrl(
        source: TextSource, url: String, evidence: String, lineNumber: Int, settings: ScanSettings
    ): Finding {
        if (!url.startsWith("https://")) {
            return base(
                RuleIds.REPO_NOT_HTTPS, "Repository URL is not HTTPS", "Use HTTPS or remove the repository.",
                source, lineNumber, evidence.take(160), Severity.ERROR, Confidence.HIGH, FindingCategory.REPOSITORY
            )
        }
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull()
            ?: return base(
                RuleIds.REPO_MALFORMED, "Repository URL is malformed", "Fix or remove the repository URL.",
                source, lineNumber, evidence.take(160), Severity.ERROR, Confidence.HIGH, FindingCategory.REPOSITORY
            )

        val allowed = UrlAllowlist.isAllowed(url, settings.allowedHosts, settings.allowedUrlPatterns)
        if (!allowed) {
            return if (host in settings.knownSafeRepositoryHosts) {
                base(
                    RuleIds.REPO_KNOWN_PUBLIC,
                    "Known public repository is not in your allowlist",
                    "Add $host to the allowlist if public repositories are approved for this project.",
                    source, lineNumber, evidence.take(160), Severity.INFO, Confidence.LOW,
                    FindingCategory.REPOSITORY, FindingKind.AUDIT_EVIDENCE
                )
            } else {
                base(
                    RuleIds.REPO_NOT_ALLOWLISTED,
                    "Repository host is not allowlisted",
                    "Remove the repository or add $host after review.",
                    source, lineNumber, evidence.take(160), Severity.ERROR, Confidence.HIGH,
                    FindingCategory.REPOSITORY
                )
            }
        }
        return base(
            RuleIds.REPO_ALLOWLISTED, "Repository host is allowlisted", null,
            source, lineNumber, host, Severity.INFO, Confidence.LOW,
            FindingCategory.REPOSITORY, FindingKind.AUDIT_EVIDENCE
        )
    }

    private fun base(
        ruleId: String, title: String, remediation: String?, source: TextSource,
        lineNumber: Int, evidence: String, severity: Severity, confidence: Confidence,
        category: FindingCategory, kind: FindingKind = FindingKind.ACTIONABLE,
        redacted: Boolean = false
    ): Finding = Finding(
        fingerprint = Fingerprints.finding(ruleId, source.relativePath, evidence),
        ruleId = ruleId, title = title, message = title,
        severity = severity, confidence = confidence, category = category, kind = kind,
        location = SourceLocation(source.relativePath, lineNumber),
        evidence = Evidence(evidence, redacted = redacted), remediation = remediation
    )

    private fun hasSuppressionWithoutReason(line: String): Boolean {
        val match = Regex("""wrapperguard:ignore\b(.*)$""", RegexOption.IGNORE_CASE).find(line) ?: return false
        return match.groupValues[1].trim().isBlank()
    }

    companion object {
        private val REPOSITORY_PATTERNS = listOf(
            Regex("""maven\s*\{\s*url\s+['"](https?://[^'"]+)['"]""", RegexOption.DOT_MATCHES_ALL),
            Regex("""maven\s*\{\s*url\s*=\s*['"](https?://[^'"]+)['"]""", RegexOption.DOT_MATCHES_ALL),
            Regex("""maven\s*\(\s*['"](https?://[^'"]+)['"]"""),
            Regex("""maven\s*\(\s*url\s*=\s*['"](https?://[^'"]+)['"]"""),
            Regex("""url\s*=\s*uri\s*\(\s*['"](https?://[^'"]+)['"]""", RegexOption.IGNORE_CASE),
            Regex("""url\s*=\s*URI\s*\(\s*['"](https?://[^'"]+)['"]"""),
            Regex("""url\s*=\s*URL\s*\(\s*['"](https?://[^'"]+)['"]""")
        )
        private val REMOTE_SCRIPT_PATTERNS = listOf(
            Regex("""apply\s+from:\s*['"](https?://[^'"]+)['"]"""),
            Regex("""apply\([^)]*from\s*=\s*['"](https?://[^'"]+)['"][^)]*\)"""),
            Regex("""apply\([^)]*from:\s*['"](https?://[^'"]+)['"][^)]*\)""")
        )
        private val PROCESS_EXECUTION_PATTERNS = listOf(
            Regex("""exec\s*\{[^}]*\b(commandLine|executable)\b[^}]*\}""", RegexOption.DOT_MATCHES_ALL),
            Regex("""tasks?\.(register|create)\s*\([^)]*Exec::class"""),
            Regex("""tasks?\.(register|create)\s*<\s*Exec\s*>"""),
            Regex("""\b(commandLine|executable)\s*\("""),
            Regex("""Runtime\.getRuntime\(\)\.exec\s*\("""),
            Regex("""ProcessBuilder\s*\("""),
            Regex("""["'](?:bash|sh|cmd|powershell|pwsh)["']\s*[,)]""", RegexOption.IGNORE_CASE)
        )
        private val NETWORK_CODE_PATTERNS = listOf(
            Regex("""new\s+URL\s*\(['"](https?://[^'"]+)['"]"""),
            Regex("""URI\.create\s*\(['"](https?://[^'"]+)['"]"""),
            Regex("""(HttpClient|DefaultHttpClient|CloseableHttpClient|OkHttpClient)\b""")
        )
        private val DYNAMIC_CODE_PATTERNS = listOf(
            Regex("""Base64\.decode|\.decodeBase64|fromBase64"""),
            Regex("""Class\.forName\s*\(['"][^'"]+['"]"""),
            Regex("""\.getDeclared(Method|Field)\s*\("""),
            Regex("""URLClassLoader\s*\("""),
            Regex("""new\s+ClassLoader"""),
            Regex("""GroovyShell\s*\([^)]*\)\.evaluate"""),
            Regex("""Eval\.me\s*\(""")
        )
        private val CREDENTIAL_PATTERNS = listOf(
            Regex("""(?i)\b(password|passwd|apiKey|secret|token|accessKey|secretKey)\b\s*[=:]\s*['"][^'"\s]{6,}['"]"""),
            Regex("""(?i)\bcredentials\s*\{[^}]*['"][^'"]{6,}['"][^}]*\}""", RegexOption.DOT_MATCHES_ALL)
        )
        private val UNSTABLE_DEP_PATTERNS = listOf(
            Regex("""['"][^:'"]+:[^:'"]+:(latest\.(release|integration)|[+*])['"]""", RegexOption.IGNORE_CASE),
            Regex("""version\s*=\s*['"](latest\.(release|integration)|[+*])['"]""", RegexOption.IGNORE_CASE),
            Regex("""changing\s*=\s*true""", RegexOption.IGNORE_CASE)
        )
        private val INIT_SCRIPT_PATTERNS = listOf(
            Regex("""gradle\.addListener\s*\("""),
            Regex("""gradle\.(beforeProject|afterProject|projectsLoaded|buildFinished)\s*\{"""),
            Regex("""allprojects\s*\{[^}]*repositories[^}]*maven""", RegexOption.DOT_MATCHES_ALL)
        )

        fun groovy() = BuildScriptRules("WG-BUILD-GROOVY", "Groovy", isInit = false)
        fun kotlin() = BuildScriptRules("WG-BUILD-KOTLIN", "Kotlin", isInit = false)
        fun settings() = BuildScriptRules("WG-BUILD-SETTINGS", "settings", isInit = false)
        fun generic() = BuildScriptRules("WG-BUILD-GENERIC", "Gradle", isInit = false)
        fun init() = BuildScriptRules("WG-BUILD-INIT", "init", isInit = true)
    }
}

private fun lineNumber(content: String, charIndex: Int): Int =
    content.substring(0, charIndex.coerceAtMost(content.length)).count { it == '\n' } + 1
