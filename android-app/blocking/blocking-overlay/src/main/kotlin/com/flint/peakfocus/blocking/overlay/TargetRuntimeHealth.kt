package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.blocking.engine.trustedElapsedMs
import com.flint.peakfocus.core.model.AccessClockStamp

/** A saved permission bit or a stale hub replay cannot establish current target enforcement. */
internal object TargetRuntimeHealth {
    fun unavailableReason(status: ProtectionStatus, now: AccessClockStamp): String? {
        if (status.readiness != ProtectionStatus.Readiness.READY) return "enforcement-state-unavailable"
        val checked = status.observedAt ?: return "enforcement-check-unavailable"
        if (trustedElapsedMs(checked, now) == null ||
            status.validUntilElapsedRealtimeMs?.let { now.elapsedRealtimeMs < it } != true) {
            return "enforcement-check-stale"
        }
        if (status.detectors.accessibilityConnected) return null
        if (status.detectors.pathB != ProtectionStatus.PathBState.ENFORCING) return "detector-not-live"
        if ("overlay-missing" in status.detectors.degradedReasons) return "overlay-missing"
        return null
    }
}
