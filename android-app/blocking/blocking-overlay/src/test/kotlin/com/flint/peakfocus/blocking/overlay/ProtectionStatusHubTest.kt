package com.flint.peakfocus.blocking.overlay

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.flint.peakfocus.core.model.AccessClockStamp
import org.junit.Test

class ProtectionStatusHubTest {

    @After fun tearDown() = ProtectionStatusHub.resetForTests()

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

    @Test fun PC_55_healthUpdatesPreserveTheLastActualPolicyClockAndReadiness() {
        val stamp = AccessClockStamp(10_000, 100, "boot")
        val snapshot = ProtectionStatus.UNKNOWN.copy(readiness = ProtectionStatus.Readiness.READY,
            observedAt = stamp, validUntilElapsedRealtimeMs = 200, safetyFloorResolved = true)
        ProtectionStatusHub.publish(snapshot)
        ProtectionStatusHub.publishPathBHealth(ProtectionStatus.PathBState.ENFORCING, setOf("overlay-missing"))
        val result = ProtectionStatusHub.status.value
        assertEquals(stamp, result.observedAt)
        assertEquals(10_000L, result.lastCheckedEpochMs)
        assertEquals(200L, result.validUntilElapsedRealtimeMs)
        assertEquals(ProtectionStatus.Readiness.READY, result.readiness)
        assertEquals(setOf("overlay-missing"), result.detectors.degradedReasons)
    }

    @Test fun PC_55_policyAndServicePublishersCannotClobberEachOthersHealth() {
        ProtectionStatusHub.publishPathBHealth(ProtectionStatus.PathBState.STANDBY, setOf("usage-access-missing"))
        val policy = ProtectionStatus.UNKNOWN.copy(detectors = ProtectionStatus.DetectorHealth(true,
            ProtectionStatus.PathBState.ENFORCING, setOf("safety-floor-unresolved")))
        ProtectionStatusHub.publish(policy)
        assertEquals(ProtectionStatus.PathBState.STANDBY, ProtectionStatusHub.status.value.detectors.pathB)
        assertEquals(setOf("usage-access-missing", "safety-floor-unresolved"), ProtectionStatusHub.status.value.detectors.degradedReasons)
        ProtectionStatusHub.publishPathBHealth(ProtectionStatus.PathBState.OFF)
        assertEquals(setOf("safety-floor-unresolved"), ProtectionStatusHub.status.value.detectors.degradedReasons)
        assertTrue(ProtectionStatusHub.status.value.detectors.accessibilityConnected)
    }

    @Test fun PC_55_unreportedPathBRemainsUnknownAndPublishedCollectionsAreFrozen() {
        val reasons = mutableSetOf("a")
        val causes = mutableListOf<ProtectionStatus.ActiveCause>()
        ProtectionStatusHub.publish(ProtectionStatus.UNKNOWN.copy(activeCauses = causes,
            detectors = ProtectionStatus.DetectorHealth(false, ProtectionStatus.PathBState.ENFORCING, reasons)))
        causes += ProtectionStatus.ActiveCause(null, "new", ProtectionStatus.CauseKind.SESSION,
            com.flint.peakfocus.core.model.BreakLevel.EASY, 1, 1, 1, false)
        reasons.clear()
        assertEquals(ProtectionStatus.PathBState.UNKNOWN, ProtectionStatusHub.status.value.detectors.pathB)
        assertTrue(ProtectionStatusHub.status.value.activeCauses.isEmpty())
        assertEquals(setOf("a"), ProtectionStatusHub.status.value.detectors.degradedReasons)
        val serviceReasons = mutableSetOf("b")
        ProtectionStatusHub.publishPathBHealth(ProtectionStatus.PathBState.OFF, serviceReasons)
        serviceReasons.clear()
        assertTrue("b" in ProtectionStatusHub.status.value.detectors.degradedReasons)
    }

    @Test fun PC_73_safetyFloorHealthCopiesAllRuntimeProvenanceSets() {
        val required = mutableSetOf("home")
        val resolved = mutableSetOf("home")
        val failed = mutableSetOf("dialer")
        ProtectionStatusHub.publish(ProtectionStatus.UNKNOWN.copy(safetyFloor =
            ProtectionStatus.SafetyFloorStatus(required, resolved, failed, 10L, true)))
        required.clear(); resolved.clear(); failed.clear()
        val health = ProtectionStatusHub.status.value.safetyFloor!!
        assertEquals(setOf("home"), health.requiredSources)
        assertEquals(setOf("home"), health.resolvedSources)
        assertEquals(setOf("dialer"), health.failedSources)
        assertEquals(10L, health.checkedAtElapsedRealtimeMs)
    }
}
