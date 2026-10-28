package com.flint.peakfocus.blocking.resilience

import com.flint.peakfocus.core.model.DetectorFailover
import com.flint.peakfocus.core.model.DetectorSignals
import com.flint.peakfocus.core.model.FallbackMode
import org.junit.Assert.*
import org.junit.Test

class DetectorRearmTest {
    @Test fun `boot hooks run before immediate live detector evaluation`() {
        val signals = DetectorSignals()
        val order = mutableListOf<String>()
        signals.subscribe { order += "evaluate" }
        rearmDetectors(listOf({ order += "start standby" }, { order += "refresh" }), signals)
        assertEquals(listOf("start standby", "refresh", "evaluate"), order)
    }

    @Test fun `failed background service start cannot suppress active path reevaluation`() {
        val signals = DetectorSignals()
        signals.connected(Any())
        var mode: FallbackMode? = null
        signals.subscribe { mode = DetectorFailover.mode(true, signals.pathAConnected) }
        rearmDetectors(listOf({ error("background start denied") }), signals)
        assertEquals(FallbackMode.STANDBY, mode)
    }

    @Test fun `clock notification reevaluates against the changed fake clock immediately`() {
        val signals = DetectorSignals()
        var now = 1_000L
        var observed = 0L
        signals.subscribe { observed = now }
        rearmDetectors(emptyList(), signals)
        assertEquals(1_000L, observed)
        now = 500
        rearmDetectors(emptyList(), signals)
        assertEquals(500L, observed)
    }
}
