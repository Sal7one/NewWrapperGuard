package com.sal7one.wrapperguard.intellij.settings

import com.sal7one.wrapperguard.core.policy.ScanSettings
import org.junit.Assert.*
import org.junit.Test

class ScanAutomationTest {
    private class Clock : AutomationClock {
        data class Job(val delay: Long, val task: () -> Unit, var cancelled: Boolean = false)
        val jobs = mutableListOf<Job>()
        override fun schedule(delayMs: Long, task: () -> Unit): () -> Unit {
            val job = Job(delayMs, task); jobs.add(job); return { job.cancelled = true }
        }
        fun fireNext() { val job = jobs.first { !it.cancelled }; job.cancelled = true; job.task() }
        fun fireLast() { val job = jobs.last { !it.cancelled }; job.cancelled = true; job.task() }
    }
    private fun preferences(interval: Int = 0) = ScanAutomationState(enabled = true, intervalMinutes = interval,
        providers = mutableMapOf("wrapperguard" to AutomationProviderState("NONE", "LOW", true)))

    @Test fun `save open and start never execute until a future reviewed trigger`() {
        val clock = Clock(); var calls = 0
        val controller = ScanAutomationController(clock, { true }, { _, complete -> calls++; complete(true); {} })
        controller.configure(preferences())
        assertFalse(controller.view.running); assertEquals(0, calls)
        controller.start("/project", setOf("wrapperguard"))
        assertEquals(0, calls); assertTrue(clock.jobs.isEmpty())
        controller.inputChanged("/other"); assertTrue(clock.jobs.isEmpty())
        controller.inputChanged("/project"); assertEquals(2000L, clock.jobs.single().delay)
        clock.fireNext(); assertEquals(1, calls); assertTrue(controller.view.running)
        controller.configure(preferences()); assertFalse(controller.view.running); assertEquals(1, calls)
    }

    @Test fun `pause cancels pending timers and the current automatic run without overlap or replay`() {
        val clock = Clock(); var calls = 0; var cancellations = 0; var finish: ((Boolean) -> Unit)? = null
        val controller = ScanAutomationController(clock, { true }, { _, complete -> calls++; finish = complete; { cancellations++ } })
        controller.configure(preferences(5)); controller.start("/project", setOf("wrapperguard"))
        assertEquals(300000L, clock.jobs.single().delay)
        controller.inputChanged("/project"); controller.inputChanged("/project")
        clock.fireLast(); assertEquals(1, calls)
        controller.inputChanged("/project"); clock.fireLast(); assertEquals(1, calls)
        controller.pause(); assertEquals(1, cancellations); assertFalse(controller.view.running)
        finish!!.invoke(true); assertFalse(controller.view.running)
        assertTrue(clock.jobs.all { it.cancelled })
    }

    @Test fun `unreviewed disabled inapplicable changed or failed providers cannot keep automation running`() {
        val clock = Clock(); var verified = false; var calls = 0
        val controller = ScanAutomationController(clock, { verified }, { _, complete -> calls++; complete(false); {} })
        controller.configure(preferences().apply { providers.getValue("wrapperguard").acknowledged = false })
        assertThrows(IllegalArgumentException::class.java) { controller.start("/project", setOf("wrapperguard")) }
        controller.configure(preferences())
        assertThrows(IllegalArgumentException::class.java) { controller.start("/project", emptySet()) }
        controller.start("/project", setOf("wrapperguard")); controller.inputChanged("/project"); clock.fireNext()
        assertFalse(controller.view.running); assertEquals(0, calls)
        verified = true; controller.start("/project", setOf("wrapperguard")); controller.inputChanged("/project"); clock.fireNext()
        assertFalse(controller.view.running); assertEquals(1, calls)
    }

    @Test fun `automatic settings suppress every nonselected method without granting permissions`() {
        val original = ScanSettings(methodFlags = mapOf("sast.bandit" to true, "sca.osv" to true))
        val filtered = automationSettings(original, setOf("wrapperguard", "python.bandit"))
        assertEquals(true, filtered.methodFlags["sast.bandit"])
        assertEquals(false, filtered.methodFlags["sca.osv"])
        assertEquals(false, filtered.methodFlags["ai.external-model"])
        assertEquals(true, original.methodFlags["sca.osv"])
        assertFalse(filtered.methodFlags.containsKey("builtin.wrapper-integrity"))
        assertThrows(IllegalArgumentException::class.java) { preferences().copy(onChanges = false).validate() }
        assertThrows(IllegalArgumentException::class.java) { preferences().copy(debounceMs = 0).validate() }
    }
}
