package com.flint.peakfocus.core.model

import org.junit.Assert.*
import org.junit.Test

class DetectorSignalsTest {
    @Test fun `live binding owns enforcement and revoke immediately wakes fallback`() {
        val signals = DetectorSignals()
        val owner = Any()
        val observed = mutableListOf<FallbackMode>()
        val cancel = signals.subscribe { observed += DetectorFailover.mode(true, signals.pathAConnected) }
        assertEquals(FallbackMode.ENFORCING, DetectorFailover.mode(true, signals.pathAConnected))
        signals.connected(owner)
        signals.disconnected(owner)
        signals.connected(owner)
        assertEquals(listOf(FallbackMode.STANDBY, FallbackMode.ENFORCING, FallbackMode.STANDBY), observed)
        cancel()
    }

    @Test fun `old service destruction cannot revoke a fresh binding`() {
        val signals = DetectorSignals()
        val old = Any()
        val fresh = Any()
        signals.connected(old)
        signals.connected(fresh)
        signals.disconnected(old)
        assertTrue(signals.pathAConnected)
        signals.disconnected(fresh)
        assertFalse(signals.pathAConnected)
    }

    @Test fun `process restart starts in fallback and missing usage access stops it`() {
        assertFalse(DetectorSignals().pathAConnected)
        for (connected in listOf(false, true)) {
            assertEquals(FallbackMode.OFF, DetectorFailover.mode(false, connected))
        }
    }

    @Test fun `boot and clock requests reach listeners and cancellation releases them`() {
        val signals = DetectorSignals()
        var calls = 0
        val cancel = signals.subscribe { calls++ }
        repeat(3) { signals.requestEvaluation() }
        assertEquals(3, calls)
        cancel()
        signals.requestEvaluation()
        assertEquals(3, calls)
    }

    @Test fun `rule publication immediately notifies once and unsubscribe works`() {
        val source = "continuous-eval-test"
        var calls = 0
        val rule = BlockRule("test", "test", BlockTargets(apps = setOf(AppRef("app"))))
        val cancel = ActiveRulesHolder.subscribe {
            assertTrue(ActiveRulesHolder.rules.contains(rule))
            calls++
        }
        try {
            ActiveRulesHolder.publish(source, listOf(rule))
            ActiveRulesHolder.publish(source, listOf(rule))
            assertEquals(1, calls)
        } finally {
            cancel()
            ActiveRulesHolder.publish(source, emptyList())
        }
        assertEquals(1, calls)
    }
}
