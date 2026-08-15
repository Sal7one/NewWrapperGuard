package com.sal7one.wrapperguard.intellij.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.sal7one.wrapperguard.core.model.Finding
import java.nio.file.Path

/**
 * Platform-boundary helper: navigates the editor to a finding's location. Compose UI calls
 * this through a lambda so the composables stay IDE-agnostic.
 */
fun openFindingInEditor(project: Project, finding: Finding) {
    val basePath = project.basePath ?: return
    ApplicationManager.getApplication().invokeLater {
        val absolute = Path.of(basePath).resolve(finding.location.path).normalize()
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(absolute) ?: return@invokeLater
        OpenFileDescriptor(project, file, (finding.location.line ?: 1) - 1, 0).navigate(true)
    }
}
