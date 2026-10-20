package com.sal7one.wrapperguard.intellij.ui

import com.intellij.openapi.project.Project
import com.intellij.testFramework.ApplicationRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import javax.swing.JComponent

/**
 * Descriptor and UI-component checks need the real target application/plugin loader,
 * but no project. ApplicationRule avoids unrelated product project-startup activities;
 * the actual build/sync gate tests remain in HeavyPlatformTestCase.
 */
class JewelPlatformRuntimeTest {
    @JvmField
    @Rule
    val application = ApplicationRule()

    @Test
    fun testDescriptorLoadsBundledComposeWithoutRequiringLaterAggregateAlias() {
        val descriptor = com.intellij.ide.plugins.PluginManagerCore.getPlugin(
            com.intellij.openapi.extensions.PluginId.getId("com.sal7one.wrapperguard.next"))
        assertNotNull("The plugin must load on the minimum platform, not silently lose services and guards", descriptor)
        assertTrue("The installed plugin must remain enabled in the target runtime", descriptor!!.isEnabled)
        val factoryClass = com.sal7one.wrapperguard.intellij.ui.WrapperGuardToolWindowFactory::class.java
        assertNotNull("The public no-argument factory constructor must retain its ABI", factoryClass.getConstructor().newInstance())
        assertEquals("Only the public content entry point is declared; platform defaults must be inherited directly",
            setOf("createToolWindowContent"), factoryClass.declaredMethods.map { it.name }.toSet())
        assertNotNull(factoryClass.getDeclaredMethod("createToolWindowContent", Project::class.java,
            com.intellij.openapi.wm.ToolWindow::class.java))
        val platformDefaults = com.intellij.openapi.wm.ToolWindowFactory::class.java.methods
            .filter { it.name in setOf("getIcon", "getAnchor", "manage") }
        platformDefaults.forEach { platformMethod ->
            val inherited = factoryClass.getMethod(platformMethod.name, *platformMethod.parameterTypes)
            assertEquals("Available platform defaults must be inherited without a generated override: ${platformMethod.name}",
                platformMethod.declaringClass, inherited.declaringClass)
        }
        val loader = requireNotNull(descriptor.pluginClassLoader) { "The enabled plugin must have its own classloader" }
        listOf("androidx.compose.runtime.Composer", "org.jetbrains.jewel.foundation.theme.JewelTheme",
            "org.jetbrains.jewel.ui.component.TextKt", "org.jetbrains.jewel.bridge.JewelComposePanelWrapperKt").forEach {
            assertNotNull("Bundled Compose/Jewel must resolve through the installed plugin classloader: $it", loader.loadClass(it))
        }
        val xml = javaClass.getResourceAsStream("/META-INF/plugin.xml")!!.use {
            javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it)
        }
        val dependencies = xml.getElementsByTagName("depends")
        val alias = (0 until dependencies.length).map { dependencies.item(it) as org.w3c.dom.Element }
            .single { it.textContent.trim() == "com.intellij.modules.compose" }
        assertEquals("The aggregate alias is absent in original 252 and must be optional", "true", alias.getAttribute("optional"))
        assertNotNull(javaClass.getResource("/META-INF/${alias.getAttribute("config-file")}"))
    }

    @Test
    fun testBundledJewelBridgeCreatesAnActualPanelThroughCompatibilityBoundary() {
        System.getProperty("wrapperguard.bridge.expectedRuntimeVersion")?.let { expected ->
            val actual = com.intellij.openapi.application.ApplicationInfo.getInstance().fullVersion
            assertTrue("The runtime test must use $expected, not the compilation IDE $actual", actual.startsWith(expected))
            val factory = javaClass.classLoader.loadClass("org.jetbrains.jewel.bridge.JewelComposePanelWrapperKt")
            val parameters = com.sal7one.wrapperguard.intellij.ui.JewelPanelCompatibility.resolve(factory).parameterCount
            val expectedParameters = if (expected == "2026.2.2") 3 else 2
            assertEquals("The target's actual public bridge shape must be exercised", expectedParameters, parameters)
        }
        var panel: JComponent? = null
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeAndWait {
            panel = com.sal7one.wrapperguard.intellij.ui.JewelPanelCompatibility.create { }
        }
        assertNotNull("The installed platform's public factory must actually create a component", panel)
        assertTrue("The themed bridge hosts a real Compose component", panel!!.componentCount > 0)
    }

}
