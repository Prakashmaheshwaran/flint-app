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
        // R-30: a reported mount outcome is an observation of the surface that actually reached
        // the user, so it outranks a bound detector. Path A being connected says a service is
        // alive; it does not say that this shield attached. Order the reasons by severity so a
        // delivered-nothing mount is never masked by a fallback-only one.
        val reasons = status.detectors.degradedReasons
        if (ProtectionStatus.REASON_BLOCK_SURFACE_MOUNT_FAILED in reasons) {
            return ProtectionStatus.REASON_BLOCK_SURFACE_MOUNT_FAILED
        }
        if (ProtectionStatus.REASON_BLOCK_SURFACE_FALLBACK_ONLY in reasons) {
            return ProtectionStatus.REASON_BLOCK_SURFACE_FALLBACK_ONLY
        }
        if (status.detectors.accessibilityConnected) return null
        if (status.detectors.pathB != ProtectionStatus.PathBState.ENFORCING) return "detector-not-live"
        if ("overlay-missing" in status.detectors.degradedReasons) return "overlay-missing"
        return null
    }
}
