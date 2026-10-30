package com.flint.peakfocus.core.model

import org.junit.Assert.*
import org.junit.Test

class DetectorCadenceTest {
    @Test fun `standby probes once a minute while enforcement polls once a second`() {
        assertEquals(60_000L, DetectorCadence.delayMs(FallbackMode.STANDBY))
        assertEquals(1_000L, DetectorCadence.delayMs(FallbackMode.ENFORCING))
    }
}
