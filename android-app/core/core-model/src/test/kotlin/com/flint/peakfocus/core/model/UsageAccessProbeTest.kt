package com.flint.peakfocus.core.model

import org.junit.Assert.*
import org.junit.Test

class UsageAccessProbeTest {
    @Test fun `missing permission is unmeasured and never queries or blocks on usage`() {
        var reads = 0
        val probe = UsageAccessProbe { false }
        assertNull(probe.measure(0) { reads++; Long.MAX_VALUE })
        assertEquals(0, reads)
    }

    @Test fun `confirmed grant preserves usage and fails closed if the read fails`() {
        val probe = UsageAccessProbe { true }
        assertEquals(60_000L, probe.measure(0) { 60_000L })
        assertEquals(Long.MAX_VALUE, probe.measure(1) { error("usage binder unavailable") })
    }

    @Test fun `permission cache expires at one second and refreshes on rewind or invalidation`() {
        var granted = true
        var checks = 0
        val probe = UsageAccessProbe { checks++; granted }
        assertEquals(1L, probe.measure(0) { 1L })
        granted = false
        assertEquals(1L, probe.measure(999) { 1L })
        assertEquals(1, checks)
        assertNull(probe.measure(1_000) { 1L })
        granted = true
        assertEquals(1L, probe.measure(500) { 1L })
        granted = false
        probe.invalidate()
        assertNull(probe.measure(501) { 1L })
        assertEquals(4, checks)
    }

    @Test fun `unconfirmed grant cannot authorize a fail closed budget decision`() {
        var reads = 0
        val probe = UsageAccessProbe { error("AppOps unavailable") }
        assertNull(probe.measure(0) { reads++; Long.MAX_VALUE })
        assertEquals(0, reads)
    }
}
