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

    fun analyze(projectRoot: Path, relativeRoot: String): List<Finding> {
        val wrapperDir = projectRoot.resolve("gradle/wrapper")
        val hasWrapperProperties = Files.exists(wrapperDir.resolve("gradle-wrapper.properties"))
        val wrapperJar = wrapperDir.resolve("gradle-wrapper.jar")
        val hasWrapperJar = Files.exists(wrapperJar)
        val hasUnixWrapper = Files.exists(projectRoot.resolve("gradlew"))
        val hasWindowsWrapper = Files.exists(projectRoot.resolve("gradlew.bat"))
        val hasSettingsFile = Files.exists(projectRoot.resolve("settings.gradle")) ||
            Files.exists(projectRoot.resolve("settings.gradle.kts"))

        val findings = mutableListOf<Finding>()

        // Observational: record the wrapper JAR SHA-256 if present (INFO, audit evidence).
        if (hasWrapperJar) {
            val sha = sha256(wrapperJar)
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

    private fun sha256(path: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
            .joinToString("") { "%02x".format(it) }
}
