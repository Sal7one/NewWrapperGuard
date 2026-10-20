package com.sal7one.wrapperguard.intellij.ui

import org.junit.Assert.*
import org.junit.Test
import javax.swing.JComponent
import javax.swing.JPanel

class JewelPanelCompatibilityTest {
    class Legacy {
        companion object {
            @JvmStatic fun JewelComposePanel(configuration: (Any) -> Unit, content: (Any, Int) -> Unit): JComponent =
                JPanel().apply { configuration(this); content(this, 2) }
        }
    }
    class Focused {
        companion object {
            @JvmStatic fun JewelComposePanel(focusOnClickInside: Boolean, configuration: (Any) -> Unit, content: (Any, Int) -> Unit): JComponent =
                JPanel().apply { putClientProperty("focus", focusOnClickInside); configuration(this); content(this, 3) }
        }
    }
    class Both {
        companion object {
            @JvmStatic fun JewelComposePanel(configuration: (Any) -> Unit, content: (Any, Int) -> Unit): JComponent =
                Legacy.JewelComposePanel(configuration, content)
            @JvmStatic fun JewelComposePanel(focusOnClickInside: Boolean, configuration: (Any) -> Unit, content: (Any, Int) -> Unit): JComponent =
                Focused.JewelComposePanel(focusOnClickInside, configuration, content)
        }
    }
    class WrongSignatures {
        fun JewelComposePanel(configuration: (Any) -> Unit, content: (Any, Int) -> Unit): JComponent = Legacy.JewelComposePanel(configuration, content)
        companion object {
            @JvmStatic fun JewelComposePanel(configuration: (Any) -> Unit, content: String): JComponent = JPanel()
            @JvmStatic fun JewelComposePanel(focus: Boolean, configuration: (Any) -> Unit, content: (Any, Int) -> Unit): Any = JPanel()
        }
    }
    class Failure {
        companion object {
            @JvmStatic fun JewelComposePanel(configuration: (Any) -> Unit, content: (Any, Int) -> Unit): JComponent =
                error("Actual factory failure")
        }
    }

    @Test fun `legacy and focused factories receive the actual configuration and content`() {
        listOf(Legacy::class.java to 2, Focused::class.java to 3).forEach { (factory, expected) ->
            var configured: Any? = null; var composed: Any? = null; var selected = 0
            val configure: (Any) -> Unit = { configured = it }
            val content: (Any, Int) -> Unit = { panel, count -> composed = panel; selected = count }
            val panel = JewelPanelCompatibility.createWithFactory(factory, configure, content)
            assertSame(panel, configured); assertSame(panel, composed); assertEquals(expected, selected)
            if (expected == 3) assertEquals(true, panel.getClientProperty("focus"))
        }
    }

    @Test fun `legacy public signature wins when both are present`() {
        var selected = 0
        val configure: (Any) -> Unit = { }
        val content: (Any, Int) -> Unit = { _, count -> selected = count }
        JewelPanelCompatibility.createWithFactory(Both::class.java, configure, content)
        assertEquals(2, selected)
    }

    @Test fun `missing static shape or exact return and parameter types fails explicitly`() {
        val failure = runCatching { JewelPanelCompatibility.resolve(WrongSignatures::class.java) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message!!.contains("no supported public Jewel panel factory"))
    }

    @Test fun `a real invocation failure is surfaced without trying another factory`() {
        val configure: (Any) -> Unit = { }
        val content: (Any, Int) -> Unit = { _, _ -> }
        val failure = runCatching { JewelPanelCompatibility.createWithFactory(Failure::class.java, configure, content) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("Actual factory failure", failure!!.message)
    }
}
