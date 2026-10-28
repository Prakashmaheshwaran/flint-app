package com.flint.peakfocus.blocking.overlay

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionStatusHubTest {

    @After fun tearDown() = ProtectionStatusHub.publish(ProtectionStatus.UNKNOWN)

    @Test fun `before enforcement publishes, the status is honestly unavailable`() {
        val s = ProtectionStatusHub.status.value
        assertEquals(ProtectionStatus.Readiness.UNAVAILABLE, s.readiness)
        assertTrue(s.activeCauses.isEmpty())
        assertEquals(ProtectionStatus.PassStatus.Unavailable, s.pass)
        assertEquals(false, s.safetyFloorResolved)
        assertEquals(ProtectionStatus.PathBState.UNKNOWN, s.detectors.pathB)
    }

    @Test fun `display names are single-line and capped`() {
        assertEquals("Deep work", ProtectionStatus.sanitizeDisplayName("  Deep\n\twork  "))
        assertEquals(64, ProtectionStatus.sanitizeDisplayName("x".repeat(100)).length)
    }

    @Test fun `published snapshots are observed`() {
        val ready = ProtectionStatus.UNKNOWN.copy(readiness = ProtectionStatus.Readiness.READY, safetyFloorResolved = true)
        ProtectionStatusHub.publish(ready)
        assertEquals(ready, ProtectionStatusHub.status.value)
    }
}
