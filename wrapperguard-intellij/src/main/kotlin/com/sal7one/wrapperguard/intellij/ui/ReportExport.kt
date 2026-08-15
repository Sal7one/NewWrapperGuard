package com.sal7one.wrapperguard.intellij.ui

import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.sal7one.wrapperguard.core.model.ScanReport
import com.sal7one.wrapperguard.core.util.CredentialRedaction
import com.intellij.openapi.project.Project
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files

private val REPORT_JSON = Json { prettyPrint = true; encodeDefaults = true }

/**
 * Defense-in-depth pass: rules redact credential evidence at creation time, but every
 * evidence text is re-masked before the report leaves the IDE as JSON. Masking is
 * idempotent, so already-redacted evidence passes through unchanged.
 */
fun sanitizeForExport(report: ScanReport): ScanReport = report.copy(
    findings = report.findings.map { finding ->
        val evidence = finding.evidence ?: return@map finding
        val (masked, changed) = CredentialRedaction.mask(evidence.text)
        if (!changed) finding
        else finding.copy(evidence = evidence.copy(text = masked, redacted = true))
    }
)

/**
 * Platform-boundary helper: saves the canonical report JSON to a user-chosen file.
 * Uses the IDE's file-saver dialog; returns the chosen path or null if cancelled.
 */
fun exportReportJson(project: Project, report: ScanReport): String? {
    val descriptor = FileSaverDescriptor("Export WrapperGuard Report", "Choose where to save the JSON report", "json")
    val saver = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
    val destination = saver.save("wrapperguard-report.json") ?: return null
    val virtualFile = destination.getFile() ?: return null
    val path = java.nio.file.Path.of(virtualFile.path)
    Files.writeString(path, REPORT_JSON.encodeToString(sanitizeForExport(report)))
    return path.toString()
}
