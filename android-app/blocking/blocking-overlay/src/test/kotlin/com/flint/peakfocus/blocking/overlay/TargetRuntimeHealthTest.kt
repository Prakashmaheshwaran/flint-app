package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.core.model.AccessClockStamp
import org.junit.Assert.*
import org.junit.Test

class TargetRuntimeHealthTest {
    private val now = AccessClockStamp(1_800_000_000_000, 1_000, "boot")
    private fun status() = ProtectionStatus.UNKNOWN.copy(
        readiness = ProtectionStatus.Readiness.READY, observedAt = now,
        validUntilElapsedRealtimeMs = now.elapsedRealtimeMs + 2_000,
        detectors = ProtectionStatus.DetectorHealth(true, ProtectionStatus.PathBState.STANDBY, emptySet()),
    )

    // PC-61/PC-73/PC-77: actual service health and original check time, never permission bits.
    @Test fun aFreshBoundPrimaryCanEstablishPolicyEnforcementWithStandbyFallback() {
        assertNull(TargetRuntimeHealth.unavailableReason(status(), now))
    }

    @Test fun onlyLiveEnforcingFallbackCanSubstituteForPrimary() {
        val state = status()
        for (mode in ProtectionStatus.PathBState.entries) {
            val result = TargetRuntimeHealth.unavailableReason(state.copy(
                detectors = state.detectors.copy(accessibilityConnected = false, pathB = mode)), now)
            if (mode == ProtectionStatus.PathBState.ENFORCING) assertNull(result)
            else assertEquals("detector-not-live", result)
        }
    }

    @Test fun overlayLossDoesNotClaimTheActivityFallbackWillActuallyBlock() {
        val state = status()
        val degraded = state.copy(detectors = state.detectors.copy(accessibilityConnected = false,
            pathB = ProtectionStatus.PathBState.ENFORCING, degradedReasons = setOf("overlay-missing")))
        assertEquals("overlay-missing", TargetRuntimeHealth.unavailableReason(degraded, now))
        assertNull(TargetRuntimeHealth.unavailableReason(degraded.copy(
            detectors = degraded.detectors.copy(accessibilityConnected = true)), now))
    }

    @Test fun exactLeaseBoundaryRebootAndClockRegressionInvalidateTheCheck() {
        val state = status()
        assertEquals("enforcement-check-stale", TargetRuntimeHealth.unavailableReason(state,
            now.copy(elapsedRealtimeMs = state.validUntilElapsedRealtimeMs!!)))
        assertEquals("enforcement-check-stale", TargetRuntimeHealth.unavailableReason(state,
            now.copy(bootSessionId = "new", elapsedRealtimeMs = 0)))
        assertEquals("enforcement-check-stale", TargetRuntimeHealth.unavailableReason(state,
            now.copy(elapsedRealtimeMs = now.elapsedRealtimeMs - 1)))
    }

    @Test fun wallClockChangeDoesNotRefreshOrExpireASameBootHealthLease() {
        assertNull(TargetRuntimeHealth.unavailableReason(status(), now.copy(wallEpochMs = now.wallEpochMs + 86_400_000)))
        assertEquals(now, status().observedAt)
    }

    @Test fun unavailablePolicyOrAbsentCheckNeverBecomesAnEmptyUnprotectedSnapshot() {
        assertEquals("enforcement-state-unavailable", TargetRuntimeHealth.unavailableReason(ProtectionStatus.UNKNOWN, now))
        assertEquals("enforcement-check-unavailable", TargetRuntimeHealth.unavailableReason(status().copy(observedAt = null), now))
        assertEquals("enforcement-check-stale", TargetRuntimeHealth.unavailableReason(status().copy(validUntilElapsedRealtimeMs = null), now))
    }
}
