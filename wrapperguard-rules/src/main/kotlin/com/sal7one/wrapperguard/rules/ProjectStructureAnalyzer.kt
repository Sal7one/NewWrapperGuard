package com.sal7one.wrapperguard.rules

import com.sal7one.wrapperguard.core.model.*
import com.sal7one.wrapperguard.core.util.Fingerprints
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.*

/**
 * Scanner-level project-structure checks. Ports old_project's ProjectStructureChecks plus the
 * wrapper-JAR SHA-256 observation from ScriptGraphScanner.projectStructureFindings. Unlike file
 * [Rule]s, this runs once per scan over the project root rather than once per file.
 *
 * All findings are anchored at the project root path (the wrapper-properties file is not always
 * present, so we don't depend on it for location).
 */
object ProjectStructureAnalyzer {

    fun analyze(
        projectRoot: Path,
        relativeRoot: String,
        trustedWrapperChecksums: Map<String, String> = emptyMap(),
        notices: MutableList<ScanNotice> = mutableListOf(),
        maxFileBytes: Long = 1_000_000
    ): List<Finding> {
        fun present(relative: String): Boolean {
            val path = projectRoot.resolve(relative)
            val status = ProjectFileAccess.status(projectRoot, path)
            if (status == ProjectFileAccess.Status.REJECTED) {
                val notice = ScanNotice(RuleIds.SCAN_FILE_SKIPPED, "File is not a readable regular input inside the project.", SourceLocation(relative))
                if (notice !in notices) notices += notice
            }
            return status == ProjectFileAccess.Status.REGULAR
        }
        val wrapperDir = projectRoot.resolve("gradle/wrapper")
        val hasWrapperProperties = present("gradle/wrapper/gradle-wrapper.properties")
        val wrapperJar = wrapperDir.resolve("gradle-wrapper.jar")
        val hasWrapperJar = present("gradle/wrapper/gradle-wrapper.jar")
        val hasUnixWrapper = present("gradlew")
        val hasWindowsWrapper = present("gradlew.bat")
        val hasSettingsFile = present("settings.gradle") ||
            present("settings.gradle.kts")

        val findings = mutableListOf<Finding>()

        if (hasWrapperJar) {
            val sha = runCatching { sha256(ProjectFileAccess.read(projectRoot, wrapperJar, maxFileBytes)) }.getOrElse {
                notices += ScanNotice(RuleIds.SCAN_FILE_SKIPPED, "File is not a readable regular input inside the project.", SourceLocation("gradle/wrapper/gradle-wrapper.jar"))
                return findings
            }
            val expected = trustedWrapperChecksums["gradle-wrapper.jar"]
            if (trustedWrapperChecksums.isNotEmpty() && expected != sha) {
                // Escalation: a known-good list is configured and this JAR is not on it.
                findings += Finding(
                    fingerprint = Fingerprints.finding(RuleIds.WRAPPER_JAR_UNKNOWN, relativeRoot, sha),
                    ruleId = RuleIds.WRAPPER_JAR_UNKNOWN,
                    title = "Gradle wrapper JAR checksum is not in the approved list",
                    message = "Gradle wrapper JAR checksum is not in the approved list: $sha",
                    severity = Severity.ERROR, confidence = Confidence.HIGH,
                    category = FindingCategory.WRAPPER, kind = FindingKind.ACTIONABLE,
                    location = SourceLocation("$relativeRoot/gradle/wrapper/gradle-wrapper.jar"),
                    evidence = Evidence(sha),
                    remediation = "Review the wrapper JAR, then either restore the approved version or add its checksum to your trusted list."
                )
            } else {
                // Observational: record the wrapper JAR SHA-256 if present (INFO, audit evidence).
                findings += Finding(
                    fingerprint = Fingerprints.finding(RuleIds.WRAPPER_JAR_SHA256, relativeRoot, sha),
                    ruleId = RuleIds.WRAPPER_JAR_SHA256,
                    title = "Gradle wrapper JAR SHA-256",
                    message = "Gradle wrapper JAR SHA-256: $sha",
                    severity = Severity.INFO, confidence = Confidence.HIGH,
                    category = FindingCategory.WRAPPER, kind = FindingKind.AUDIT_EVIDENCE,
                    location = SourceLocation("$relativeRoot/gradle/wrapper/gradle-wrapper.jar"),
                    evidence = Evidence(sha)
                )
            }
        }

        if (hasWrapperProperties && !hasWrapperJar) {
            findings += struct(RuleIds.STRUCT_WRAPPER_JAR_MISSING, "Gradle wrapper JAR is missing",
                "Regenerate the wrapper with Gradle or commit the reviewed wrapper JAR.",
                Severity.WARN, FindingCategory.WRAPPER, relativeRoot, "gradle/wrapper/gradle-wrapper.jar")
        }
        if (hasWrapperProperties && !hasUnixWrapper && !hasWindowsWrapper) {
            findings += struct(RuleIds.STRUCT_WRAPPER_SCRIPTS_MISSING,
                "Wrapper properties exist without wrapper launcher scripts",
                "Commit gradlew and gradlew.bat or remove incomplete wrapper metadata.",
                Severity.WARN, FindingCategory.WRAPPER, relativeRoot, null)
        }
        if (!hasSettingsFile) {
            findings += struct(RuleIds.STRUCT_NO_SETTINGS, "No Gradle settings file found",
                "Most Gradle projects should commit settings.gradle or settings.gradle.kts.",
                Severity.INFO, FindingCategory.CONFIGURATION, relativeRoot, null)
        }
        return findings
    }

    private fun struct(
        ruleId: String, title: String, remediation: String,
        severity: Severity, category: FindingCategory, relativeRoot: String, evidence: String?
    ) = Finding(
        fingerprint = Fingerprints.finding(ruleId, relativeRoot, evidence ?: title),
        ruleId = ruleId, title = title, message = title,
        severity = severity, confidence = Confidence.MEDIUM,
        category = category, location = SourceLocation(relativeRoot),
        evidence = evidence?.let { Evidence(it) }, remediation = remediation
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
