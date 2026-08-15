package com.sal7one.wrapperguard.rules

import com.sal7one.wrapperguard.core.model.*
import com.sal7one.wrapperguard.core.policy.ScanSettings
import com.sal7one.wrapperguard.core.spi.Rule
import com.sal7one.wrapperguard.core.spi.TextSource
import com.sal7one.wrapperguard.core.util.CredentialRedaction
import com.sal7one.wrapperguard.core.util.Fingerprints
import com.sal7one.wrapperguard.core.util.UrlAllowlist

/**
 * Shell/batch/PowerShell per-line analysis. Ports old_project's ScriptAnalyzer line engine
 * faithfully, including the exact check ordering, the `eval "set -- $("` Gradle JVM-opts
 * special case, and generated-then-executed detection.
 *
 * SUPPRESSION MODEL (#5): a `wrapperguard:ignore` marker shipped inside a repository is
 * UNTRUSTED — it never suppresses findings by itself. Findings on ignored lines are emitted
 * normally (the marker is stripped from line-level evidence so fingerprints stay stable),
 * and only a trusted external baseline (user-approved fingerprint) marks them SUPPRESSED.
 *
 * One [ScriptRules] instance handles all three script flavors via a [flavor] parameter that
 * selects the network-command pattern and comment prefixes.
 */
class ScriptRules private constructor(
    override val id: String,
    private val flavor: Flavor
) : Rule {

    enum class Flavor(
        val networkCommandPattern: Regex,
        val commentPrefixes: List<String>
    ) {
        UNIX(UNIX_NETWORK_COMMANDS, listOf("#")),
        WINDOWS(WINDOWS_NETWORK_COMMANDS, listOf("@rem", "rem ", "::")),
        POWERSHELL(WINDOWS_NETWORK_COMMANDS, listOf("#"))
    }

    override fun analyze(source: TextSource, settings: ScanSettings): List<Finding> {
        val findings = mutableListOf<Finding>()

        source.content.lineSequence().forEachIndexed { idx, raw ->
            val line = raw.trim()
            if (line.isBlank()) return@forEachIndexed
            val lineNumber = idx + 1
            // Evidence for line-level findings: strip any untrusted ignore-marker suffix so
            // fingerprints stay stable when the suppression is later approved.
            val evidenceLine = stripIgnoreSuffix(line)

            if (line.contains("wrapperguard:ignore", ignoreCase = true)) {
                if (hasSuppressionWithoutReason(line)) {
                    findings += finding(
                        RuleIds.CONFIG_SUPPRESS_NO_REASON,
                        "Suppression has no reason",
                        "Add a short reason after wrapperguard:ignore.",
                        source, lineNumber, line, Severity.INFO, Confidence.LOW,
                        FindingCategory.CONFIGURATION, FindingKind.AUDIT_EVIDENCE
                    )
                }
                // UNTRUSTED (#5): the checks below still run — a repository must not be able
                // to suppress its own payload.
            }
            if (flavor.commentPrefixes.any { line.startsWith(it, ignoreCase = true) }) return@forEachIndexed

            settings.blockedCommands.forEach { blocked ->
                val blockedPattern = Regex("""(?<![w./-])${Regex.escape(blocked)}(?![w-])""", RegexOption.IGNORE_CASE)
                if (blockedPattern.containsMatchIn(line)) {
                    findings += finding(
                        RuleIds.SUPPLY_BLOCKED_COMMAND,
                        "Blocked command detected: $blocked",
                        "Remove this command or update the blocked command policy if it is approved.",
                        source, lineNumber, evidenceLine, Severity.ERROR, Confidence.HIGH,
                        FindingCategory.EXECUTION
                    )
                }
            }

            if (PIPE_TO_SHELL.containsMatchIn(line)) {
                findings += finding(
                    RuleIds.SUPPLY_PIPE_TO_SHELL,
                    "Remote content is piped into a shell",
                    "Download, verify, and execute pinned artifacts instead of piping network output into an interpreter.",
                    source, lineNumber, evidenceLine, Severity.ERROR, Confidence.HIGH,
                    FindingCategory.SUPPLY_CHAIN
                )
            } else if (flavor.networkCommandPattern.containsMatchIn(line)) {
                findings += finding(
                    RuleIds.NETWORK_COMMAND,
                    "Network-capable command detected",
                    "Review why wrapper/bootstrap code needs network access.",
                    source, lineNumber, evidenceLine, Severity.WARN, Confidence.MEDIUM,
                    FindingCategory.NETWORK
                )
            }

            if (INSECURE_TLS.containsMatchIn(line)) {
                findings += finding(
                    RuleIds.NETWORK_INSECURE_TLS,
                    "TLS verification is disabled",
                    "Remove insecure TLS flags and fix the certificate trust chain.",
                    source, lineNumber, evidenceLine, Severity.ERROR, Confidence.HIGH,
                    FindingCategory.NETWORK
                )
            }

            // eval "set -- $(" is standard Gradle JVM-opts parsing; skip it
            val isGradleJvmOptsEval = line.trimStart().startsWith("eval \"set -- $(")
            if (!isGradleJvmOptsEval && ENCODED_EXECUTION.containsMatchIn(line)) {
                findings += finding(
                    RuleIds.OBFUSCATION_ENCODED_EXEC,
                    "Encoded or dynamic execution pattern detected",
                    "Avoid eval/iex/base64 execution in wrapper and bootstrap scripts.",
                    source, lineNumber, evidenceLine, Severity.ERROR, Confidence.HIGH,
                    FindingCategory.OBFUSCATION
                )
            }

            if (WRAPPER_MUTATION.containsMatchIn(line)) {
                findings += finding(
                    RuleIds.WRAPPER_MUTATION,
                    "Script mutates Gradle wrapper files",
                    "Wrapper files should be reviewed and committed, not rewritten by bootstrap scripts.",
                    source, lineNumber, evidenceLine, Severity.ERROR, Confidence.HIGH,
                    FindingCategory.WRAPPER
                )
            }

            if (DESTRUCTIVE.containsMatchIn(line)) {
                findings += finding(
                    RuleIds.FILE_SYSTEM_DESTRUCTIVE,
                    "Destructive file-system command detected",
                    "Confirm this cannot delete project or user data unexpectedly.",
                    source, lineNumber, evidenceLine, Severity.WARN, Confidence.MEDIUM,
                    FindingCategory.FILE_SYSTEM
                )
            }

            if (PRIVILEGE.containsMatchIn(line)) {
                findings += finding(
                    RuleIds.EXECUTION_PRIVILEGE,
                    "Privilege or execution policy change detected",
                    "Avoid privilege escalation or execution policy bypass in project bootstrap code.",
                    source, lineNumber, evidenceLine, Severity.WARN, Confidence.MEDIUM,
                    FindingCategory.EXECUTION
                )
            }

            if (ENVIRONMENT_LEAK.containsMatchIn(line)) {
                val (maskedLine, didRedact) = CredentialRedaction.mask(evidenceLine)
                findings += finding(
                    RuleIds.CREDENTIALS_ENV_LEAK,
                    "Potential environment secret exposure",
                    "Do not print or transmit environment variables that may contain secrets.",
                    source, lineNumber, maskedLine, Severity.WARN, Confidence.MEDIUM,
                    FindingCategory.CREDENTIALS, redacted = didRedact
                )
            }

            URL_PATTERN.findAll(line).forEach { match ->
                val url = match.value.trimEnd('.', ',', ';')
                val (safeUrl, urlRedacted) = CredentialRedaction.mask(url)
                val allowed = UrlAllowlist.isAllowed(url, settings.allowedHosts, settings.allowedUrlPatterns)
                findings += if (allowed) {
                    finding(
                        RuleIds.NETWORK_URL_APPROVED,
                        "Approved URL observed",
                        null,
                        source, lineNumber, safeUrl, Severity.INFO, Confidence.LOW,
                        FindingCategory.NETWORK, FindingKind.AUDIT_EVIDENCE,
                        redacted = urlRedacted
                    )
                } else {
                    finding(
                        RuleIds.NETWORK_URL_NOT_ALLOWLISTED,
                        "URL is not allowlisted",
                        "Verify the URL and add it to the allowlist only if it is trusted.",
                        source, lineNumber, safeUrl, Severity.WARN, Confidence.MEDIUM,
                        FindingCategory.NETWORK, redacted = urlRedacted
                    )
                }
            }
        }

        findings += detectGeneratedThenExecutedScripts(source)
        return findings.distinctBy { listOf(it.severity, it.ruleId, it.location.path, it.location.line, it.evidence?.text) }
    }

    private fun detectGeneratedThenExecutedScripts(source: TextSource): List<Finding> {
        val written = SCRIPT_WRITE.findAll(source.content)
            .mapNotNull { it.groups["path"]?.value }
            .filter { it.isNotBlank() }
            .toSet()
        if (written.isEmpty()) return emptyList()
        return SCRIPT_EXECUTE.findAll(source.content).mapNotNull { match ->
            val path = match.groups["path"]?.value ?: return@mapNotNull null
            if (path !in written) return@mapNotNull null
            val lineNumber = lineNumber(source.content, match.range.first)
            finding(
                RuleIds.EXECUTION_GENERATED_THEN_RUN,
                "Generated script is later executed",
                "Avoid generating and executing scripts during wrapper/bootstrap flow, or review and pin the generated script content.",
                source, lineNumber, path, Severity.WARN, Confidence.MEDIUM,
                FindingCategory.EXECUTION
            )
        }.toList()
    }

    private fun finding(
        ruleId: String,
        title: String,
        remediation: String?,
        source: TextSource,
        lineNumber: Int,
        evidence: String,
        severity: Severity,
        confidence: Confidence,
        category: FindingCategory,
        kind: FindingKind = FindingKind.ACTIONABLE,
        redacted: Boolean = false
    ): Finding = Finding(
        fingerprint = Fingerprints.finding(ruleId, source.relativePath, evidence),
        ruleId = ruleId,
        title = title,
        message = title,
        severity = severity,
        confidence = confidence,
        category = category,
        kind = kind,
        location = SourceLocation(source.relativePath, lineNumber),
        evidence = Evidence(evidence, redacted = redacted),
        remediation = remediation
    )

    private fun hasSuppressionWithoutReason(line: String): Boolean {
        val match = Regex("""wrapperguard:ignore(.*)$""", RegexOption.IGNORE_CASE).find(line) ?: return false
        return match.groupValues[1].trim().isBlank()
    }

    /** Strips the untrusted wrapperguard:ignore suffix so fingerprints stay stable. */
    private fun stripIgnoreSuffix(line: String): String {
        val idx = line.indexOf("wrapperguard:ignore", ignoreCase = true)
        var result = if (idx < 0) line else line.substring(0, idx).trimEnd()
        // Drop the comment marker that introduced the ignore (shell '#', groovy '//').
        if (result.endsWith("//")) result = result.dropLast(2).trimEnd()
        else if (result.endsWith("#")) result = result.dropLast(1).trimEnd()
        return result
    }

    companion object {
        private val URL_PATTERN = Regex("""https?://[^\s"'<>(){}|\\^`\[\]]+""", RegexOption.IGNORE_CASE)
        private val INSECURE_TLS = Regex("""(?<!\w)(--insecure|-k|--no-check-certificate|-SkipCertificateCheck)(?!\w)""", RegexOption.IGNORE_CASE)
        private val UNIX_NETWORK_COMMANDS = Regex("""(?<![\w./-])(curl|wget|fetch|aria2c|nc|netcat|socat|ftp|scp|rsync)(?![\w-])""", RegexOption.IGNORE_CASE)
        private val WINDOWS_NETWORK_COMMANDS = Regex("""(?<![\w./-])(bitsadmin|certutil|curl(?:\.exe)?|wget(?:\.exe)?|Invoke-WebRequest|Invoke-RestMethod|iwr|irm|Start-BitsTransfer)(?![\w-])""", RegexOption.IGNORE_CASE)
        private val PIPE_TO_SHELL = Regex("""(curl|wget|iwr|irm|Invoke-WebRequest|Invoke-RestMethod).*(\|\s*(bash|sh|zsh|pwsh|powershell|iex|Invoke-Expression)|>\s*/tmp/[^;&|]+\s*[;&|]\s*(bash|sh))""", RegexOption.IGNORE_CASE)
        private val ENCODED_EXECUTION = Regex("""(base64\s+(-d|--decode)|certutil\s+-decode|FromBase64String|EncodedCommand|eval\s+["'$]|\biex\b|Invoke-Expression)""", RegexOption.IGNORE_CASE)
        private val WRAPPER_MUTATION = Regex("""(>\s*|>>\s*|rm\s+-f\s+|del\s+|move\s+|copy\s+).*(gradle-wrapper\.jar|gradle-wrapper\.properties|gradlew(?:\.bat)?)""", RegexOption.IGNORE_CASE)
        private val DESTRUCTIVE = Regex("""(?<!\w)(rm\s+-rf\s+[\w./$~*-]*|del\s+/[fsq]\s+|format\s+|Remove-Item\s+.*-Recurse)""", RegexOption.IGNORE_CASE)
        private val PRIVILEGE = Regex("""(?<!\w)(sudo|Set-ExecutionPolicy\s+Bypass|chmod\s+\+x\s+.*(?:/tmp|http|curl|wget))(?!\w)""", RegexOption.IGNORE_CASE)
        private val ENVIRONMENT_LEAK = Regex("""(?<!\w)(env|printenv|set)(?!\w).*(token|secret|password|key|credential)""", RegexOption.IGNORE_CASE)
        private val SCRIPT_WRITE = Regex("""(?:cat\s+>\s*|echo\b.*>\s*)(["']?)(?<path>[^\s;&|"']+\.sh)\1""", RegexOption.IGNORE_CASE)
        private val SCRIPT_EXECUTE = Regex("""(?<!\w)(?:bash|sh|chmod\s+\+x)\s+(["']?)(?<path>[^\s;&|"']+\.sh)\1""", RegexOption.IGNORE_CASE)

        fun unix() = ScriptRules("WG-SCRIPT-UNIX", Flavor.UNIX)
        fun windows() = ScriptRules("WG-SCRIPT-WIN", Flavor.WINDOWS)
        fun powershell() = ScriptRules("WG-SCRIPT-PS1", Flavor.POWERSHELL)
    }
}

private fun lineNumber(content: String, charIndex: Int): Int =
    content.substring(0, charIndex.coerceAtMost(content.length)).count { it == '\n' } + 1
