package com.sal7one.wrapperguard.intellij.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;

/**
 * Public platform entry point. Java inherits the platform's JVM defaults directly;
 * a Kotlin implementation emits forwarding overrides for inherited internal members.
 * The UI and its failure handling remain in the Kotlin content boundary.
 */
public final class WrapperGuardToolWindowFactory implements ToolWindowFactory {
    public WrapperGuardToolWindowFactory() {}

    @Override
    public void createToolWindowContent(Project project, ToolWindow toolWindow) {
        WrapperGuardToolWindowContent.create(project, toolWindow);
    }
}
