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
    }

    @Test fun PC_41_committedGlobalPassHasExactScopeAndOnlyOneCompatibilityDelivery() {
        val committed = mutableListOf<String>()
        var oldCalls = 0
        val modern = object : BlockEventHooks.Listener {
            override fun onEmergencyPassUsed(ruleId: String?, atEpochMs: Long) { oldCalls++ }
            override fun onEmergencyPassCommitted(operationId: String, affectedRuleIds: Set<String>, recoveryEndEpochMs: Long, atEpochMs: Long) {
                committed += "$operationId:${affectedRuleIds.sorted()}:$recoveryEndEpochMs@$atEpochMs"
            }
        }
        BlockEventHooks.register(recorder)
        BlockEventHooks.register(modern)
        try {
            BlockEventHooks.emergencyPassCommitted("op", setOf("session", "schedule"), 3_600_010, 10)
            assertEquals(listOf("op:[schedule, session]:3600010@10"), committed)
            assertEquals(0, oldCalls)
            assertEquals(listOf("pass:null@10"), events)
        } finally { BlockEventHooks.unregister(modern) }
    }

    @Test fun PC_22_breakTerminalHooksCarryStableGrantAndReasonAndIsolateObservers() {
        val terminal = mutableListOf<String>()
        val bad = object : BlockEventHooks.Listener {
            override fun onBreakEnded(ruleId: String?, grantId: String, reason: BlockEventHooks.BreakEndReason, atEpochMs: Long) = error("observer")
            override fun onEmergencyPassCommitted(operationId: String, affectedRuleIds: Set<String>, recoveryEndEpochMs: Long, atEpochMs: Long) = error("observer")
        }
        val good = object : BlockEventHooks.Listener {
            override fun onBreakEnded(ruleId: String?, grantId: String, reason: BlockEventHooks.BreakEndReason, atEpochMs: Long) {
                terminal += "$ruleId:$grantId:${reason.wireValue}@$atEpochMs"
            }
        }
        BlockEventHooks.register(bad)
        BlockEventHooks.register(good)
        BlockEventHooks.register(recorder)
        try {
            BlockEventHooks.BreakEndReason.entries.forEach {
                BlockEventHooks.breakEnded("rule", "grant", it, 20)
            }
            BlockEventHooks.emergencyPassCommitted("op", setOf("rule"), 100, 20)
            assertEquals(listOf("rule:grant:expired@20", "rule:grant:resumed@20", "rule:grant:superseded@20", "rule:grant:recovery@20"), terminal)
            assertEquals(listOf("pass:rule@20"), events)
        } finally {
            BlockEventHooks.unregister(bad)
            BlockEventHooks.unregister(good)
        }
    }

    @Test fun PC_22_sequentialBreaksPairTheirStartAndEndByDurableGrantId() {
        val started = linkedMapOf<String, Long>()
        val ended = linkedMapOf<String, BlockEventHooks.BreakEndReason>()
        var oldCalls = 0
        val modern = object : BlockEventHooks.Listener {
            override fun onBreakGranted(ruleId: String?, untilEpochMs: Long, atEpochMs: Long) { oldCalls++ }
            override fun onBreakGranted(ruleId: String?, grantId: String, untilEpochMs: Long, atEpochMs: Long) {
                started[grantId] = untilEpochMs
            }
            override fun onBreakEnded(ruleId: String?, grantId: String, reason: BlockEventHooks.BreakEndReason, atEpochMs: Long) {
                ended[grantId] = reason
            }
        }
        BlockEventHooks.register(modern)
        BlockEventHooks.register(recorder)
        try {
            BlockEventHooks.breakGranted("rule", "g1", 300, 0)
            BlockEventHooks.breakEnded("rule", "g1", BlockEventHooks.BreakEndReason.EXPIRED, 300)
            BlockEventHooks.breakGranted("rule", "g2", 600, 300)
            BlockEventHooks.breakEnded("rule", "g2", BlockEventHooks.BreakEndReason.RESUMED, 400)
            assertEquals(mapOf("g1" to 300L, "g2" to 600L), started)
            assertEquals(mapOf("g1" to BlockEventHooks.BreakEndReason.EXPIRED, "g2" to BlockEventHooks.BreakEndReason.RESUMED), ended)
            assertEquals(0, oldCalls)
            assertEquals(listOf("break:rule:300@0", "break:rule:600@300"), events)
        } finally { BlockEventHooks.unregister(modern) }
    }
}
