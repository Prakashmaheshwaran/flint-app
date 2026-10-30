package com.flint.peakfocus.feature.blocklist

import com.flint.peakfocus.blocking.overlay.ProtectionStatus
import com.flint.peakfocus.core.model.AccessClockStamp
import com.flint.peakfocus.core.model.BreakLevel

internal fun readyTimerStatus(now: AccessClockStamp, ids: List<String> = emptyList()) = ProtectionStatus.UNKNOWN.copy(
    readiness = ProtectionStatus.Readiness.READY,
    observedAt = now,
    validUntilElapsedRealtimeMs = now.elapsedRealtimeMs + 2_000,
    safetyFloorResolved = true,
    safetyFloor = ProtectionStatus.SafetyFloorStatus(setOf("dialer", "home"), setOf("dialer", "home"), emptySet(), now.elapsedRealtimeMs, false),
    detectors = ProtectionStatus.DetectorHealth(true, ProtectionStatus.PathBState.OFF, emptySet()),
    activeCauses = ids.map { ProtectionStatus.ActiveCause(it, "Timer", ProtectionStatus.CauseKind.TIMER, BreakLevel.EASY,
        300_000, now.elapsedRealtimeMs + 300_000, now.wallEpochMs + 300_000, false) },
)
