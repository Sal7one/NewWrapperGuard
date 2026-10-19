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
fun openFindingInEditor(project: Project, finding: Finding, scanRoot: String? = project.basePath) {
    val basePath = scanRoot ?: return
    ApplicationManager.getApplication().invokeLater {
        val absolute = runCatching {
            val root = Path.of(basePath).toRealPath()
            root.resolve(finding.location.path).normalize().toRealPath().also { require(it.startsWith(root)) }
        }.getOrNull() ?: return@invokeLater
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(absolute) ?: return@invokeLater
        if (file.isDirectory || project.isDisposed) return@invokeLater
        OpenFileDescriptor(project, file, ((finding.location.line ?: 1) - 1).coerceAtLeast(0), 0).navigate(true)
    }
}
