package com.sal7one.wrapperguard.intellij.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.sal7one.wrapperguard.intellij.WrapperGuardProjectService
import org.jetbrains.jewel.bridge.JewelComposePanel
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * Tool window factory — the Swing/IntelliJ platform boundary.
 *
 * Content is rendered with Compose via Jewel's [JewelComposePanel], which applies the
 * IntelliJ/Jewel bridge theme (colors, typography, metrics track the active IDE light/dark
 * theme automatically). The panel is wrapped in a [BorderLayout] container so it is
 * *stretched* to the tool window's full size — mounting the compose panel directly lets it
 * shrink to its preferred (intrinsic) width, which squeezes the UI into a narrow column.
 *
 * Per the project architecture, Swing is allowed only at this platform boundary; all real
 * UI lives in Compose.
 */
class WrapperGuardToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val service = project.getService(WrapperGuardProjectService::class.java)
        // Compiling against 252 binds the original (config, content) bridge overload.
        // Newer Jewel versions retain that binary overload; the focusOnClickInside variant
        // was introduced later and must not be selected for a minimum-platform release.
        val composeHost = JewelComposePanel(
            config = { },
            content = { WrapperGuardApp(service, project) }
        )
        val wrapper = JPanel(BorderLayout()).apply {
            add(composeHost, BorderLayout.CENTER)
        }
        val content = ContentFactory.getInstance().createContent(wrapper, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
