package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.BlockTargets
import com.flint.peakfocus.core.model.BreakLevel
import com.flint.peakfocus.core.model.OpenLimit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BlockEventHooksTest {

    private val events = mutableListOf<String>()
    private val recorder = object : BlockEventHooks.Listener {
        override fun onOpenIntercepted(ruleId: String?, atEpochMs: Long) { events += "open:$ruleId@$atEpochMs" }
        override fun onBreakGranted(ruleId: String?, untilEpochMs: Long, atEpochMs: Long) { events += "break:$ruleId:$untilEpochMs@$atEpochMs" }
        override fun onEmergencyPassUsed(ruleId: String?, atEpochMs: Long) { events += "pass:$ruleId@$atEpochMs" }
    }
    private val throwing = object : BlockEventHooks.Listener {
        override fun onOpenIntercepted(ruleId: String?, atEpochMs: Long) = error("listener bug")
        override fun onBreakGranted(ruleId: String?, untilEpochMs: Long, atEpochMs: Long) = error("listener bug")
        override fun onEmergencyPassUsed(ruleId: String?, atEpochMs: Long) = error("listener bug")
    }

    @After fun tearDown() {
        BlockEventHooks.unregister(recorder)
        BlockEventHooks.unregister(throwing)
    }

    @Test fun `registered listeners receive each event once`() {
        BlockEventHooks.register(recorder)
        BlockEventHooks.register(recorder) // idempotent
        BlockEventHooks.openIntercepted("session-1", 10L)
        BlockEventHooks.breakGranted(null, 400L, 100L)
        BlockEventHooks.emergencyPassUsed("r2", 20L)
        assertEquals(listOf("open:session-1@10", "break:null:400@100", "pass:r2@20"), events)
    }

    @Test fun `a throwing listener never breaks enforcement or other listeners`() {
        BlockEventHooks.register(throwing)
        BlockEventHooks.register(recorder)
        BlockEventHooks.openIntercepted("x", 1L)
        BlockEventHooks.breakGranted("x", 2L, 1L)
        BlockEventHooks.emergencyPassUsed("x", 1L)
        assertEquals(3, events.size)
    }

    @Test fun `unregistered listeners stop receiving`() {
        BlockEventHooks.register(recorder)
        BlockEventHooks.unregister(recorder)
        BlockEventHooks.openIntercepted("x", 1L)
        assertEquals(emptyList<String>(), events)
    }

    @Test fun `only rule causes carry a rule id`() {
        val rule = BlockRule(id = "session-timer-1", name = "t", targets = BlockTargets(), breakLevel = BreakLevel.HARDER)
        assertEquals("session-timer-1", ruleBlockCause(rule, 0L, 0L).ruleId)
        assertNull(ruleBlockCause(null, 0L, 0L).ruleId)
        assertNull(timeLimitBlockCause(BreakLevel.EASY, 0L, 0L).ruleId)
        assertNull(openLimitBlockCause(OpenLimit("com.x", 1), 0L, 0L).ruleId)
        assertNull(uninstallGuardBlockCause(rule, 0L, 0L).ruleId)
    }
}
