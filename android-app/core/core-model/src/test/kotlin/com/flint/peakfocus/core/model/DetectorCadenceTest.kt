package com.flint.peakfocus.core.model

import org.junit.Assert.*
import org.junit.Test

class DetectorCadenceTest {
    @Test fun `standby signal wakeups reuse the one minute permission probe`() {
        var queries = 0
        val probe = DetectorModeProbe { queries++; true }
        repeat(60) { second ->
            assertEquals(FallbackMode.STANDBY, probe.mode(second * 1_000L, true))
        }
        assertEquals(1, queries)
        probe.mode(60_000, true)
        assertEquals(2, queries)
        assertEquals(60_000L, DetectorCadence.delayMs(FallbackMode.STANDBY))
        assertEquals(1_000L, DetectorCadence.delayMs(FallbackMode.ENFORCING))
    }

    @Test fun `disconnect immediately probes usage access rather than waiting for standby deadline`() {
        var granted = true
        var queries = 0
        val probe = DetectorModeProbe { queries++; granted }
        assertEquals(FallbackMode.STANDBY, probe.mode(0, true))
        assertEquals(FallbackMode.ENFORCING, probe.mode(1, false))
        granted = false
        assertEquals(FallbackMode.OFF, probe.mode(2, false))
        assertEquals(3, queries)
    }

    @Test fun `standby detects revocation at its deadline and explicit invalidation or rewind refreshes`() {
        var granted = true
        val probe = DetectorModeProbe { granted }
        probe.mode(0, true)
        granted = false
        assertEquals(FallbackMode.OFF, probe.mode(60_000, true))
        granted = true
        assertEquals(FallbackMode.STANDBY, probe.mode(10, true))
        granted = false
        probe.invalidate()
        assertEquals(FallbackMode.OFF, probe.mode(11, true))
    }
}
