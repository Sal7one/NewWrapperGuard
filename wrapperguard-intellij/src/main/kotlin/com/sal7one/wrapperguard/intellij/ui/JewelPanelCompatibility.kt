package com.sal7one.wrapperguard.intellij.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.awt.ComposePanel
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import javax.swing.JComponent

/** The public Jewel bridge changed binary signatures within our supported IDE range. */
internal object JewelPanelCompatibility {
    private const val FACTORY_CLASS = "org.jetbrains.jewel.bridge.JewelComposePanelWrapperKt"
    private val legacyTypes = arrayOf(Function1::class.java, Function2::class.java)
    private val focusedTypes = arrayOf(java.lang.Boolean.TYPE, Function1::class.java, Function2::class.java)

    fun create(content: @Composable () -> Unit): JComponent {
        // Resolve only this trusted bundled class, using the plugin's declared module parents.
        val factory = Class.forName(FACTORY_CLASS, false, JewelPanelCompatibility::class.java.classLoader)
        val configuration: ComposePanel.() -> Unit = { }
        return createWithFactory(factory, configuration, content)
    }

    internal fun resolve(factory: Class<*>): Method {
        val candidates = factory.declaredMethods.filter {
            it.name == "JewelComposePanel" && Modifier.isPublic(it.modifiers) && Modifier.isStatic(it.modifiers) &&
                it.returnType == JComponent::class.java
        }
        return candidates.singleOrNull { it.parameterTypes.contentEquals(legacyTypes) }
            ?: candidates.singleOrNull { it.parameterTypes.contentEquals(focusedTypes) }
            ?: error("The installed IDE has no supported public Jewel panel factory signature.")
    }

    internal fun createWithFactory(factory: Class<*>, configuration: Any, content: Any): JComponent {
        val method = resolve(factory)
        // The newer public overload documents focusOnClickInside=true as its default.
        val arguments = if (method.parameterCount == 2) arrayOf(configuration, content) else arrayOf(true, configuration, content)
        val panel = try { method.invoke(null, *arguments) }
        catch (failure: InvocationTargetException) { throw failure.targetException }
        return panel as? JComponent ?: error("The bundled Jewel panel factory returned no component.")
    }
}
