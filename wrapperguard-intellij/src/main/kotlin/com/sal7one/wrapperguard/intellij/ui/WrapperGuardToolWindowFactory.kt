package com.sal7one.wrapperguard.intellij.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.sal7one.wrapperguard.intellij.WrapperGuardProjectService
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextArea

/**
 * Tool window factory — the Swing/IntelliJ platform boundary.
 *
 * Content is rendered with Compose via Jewel's public panel factory, which applies the
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
        val composeHost = try { JewelPanelCompatibility.create { WrapperGuardApp(service, project) } }
        catch (cancelled: ProcessCanceledException) { throw cancelled }
        catch (failure: LinkageError) { unavailablePanel(project, failure) }
        catch (failure: Exception) { unavailablePanel(project, failure) }
        val wrapper = JPanel(BorderLayout()).apply {
            add(composeHost, BorderLayout.CENTER)
        }
        val content = ContentFactory.getInstance().createContent(wrapper, "", false)
        toolWindow.contentManager.addContent(content)
    }

    private fun unavailablePanel(project: Project, failure: Throwable): JPanel {
        Logger.getInstance(WrapperGuardToolWindowFactory::class.java).warn("WrapperGuard could not create its bundled Jewel workspace", failure)
        return JPanel(BorderLayout(0, 12)).apply {
            accessibleContext.accessibleName = "WrapperGuard workspace unavailable"
            add(JTextArea("WrapperGuard could not open its workspace with this IDE's bundled UI. Open WrapperGuard settings or inspect the IDE log for details.").apply {
                isEditable = false; lineWrap = true; wrapStyleWord = true; isOpaque = false
                accessibleContext.accessibleName = "WrapperGuard workspace error"
            }, BorderLayout.CENTER)
            add(JButton("Open WrapperGuard settings").apply {
                addActionListener { ShowSettingsUtil.getInstance().showSettingsDialog(project, "WrapperGuard") }
            }, BorderLayout.SOUTH)
        }
    }
}
