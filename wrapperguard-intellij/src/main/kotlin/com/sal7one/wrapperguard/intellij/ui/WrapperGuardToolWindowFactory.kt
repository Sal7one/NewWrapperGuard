package com.sal7one.wrapperguard.intellij.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.sal7one.wrapperguard.intellij.WrapperGuardProjectService
import org.jetbrains.jewel.bridge.addComposeTab

/**
 * Tool window factory — the Swing/IntelliJ platform boundary.
 *
 * Content is rendered with Compose via Jewel's [addComposeTab], which mounts a Compose
 * panel under the IntelliJ/Jewel bridge theme (so colors, typography, and metrics track
 * the active IDE light/dark theme automatically). Per the project architecture, Swing is
 * allowed only at this platform boundary; all real UI lives in Compose.
 */
class WrapperGuardToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val service = project.getService(WrapperGuardProjectService::class.java)
        toolWindow.addComposeTab("WrapperGuard", isLockable = true, isCloseable = false) {
            WrapperGuardApp(service)
        }
    }
}
