package com.flint.peakfocus.feature.blocklist

import com.flint.peakfocus.blocking.engine.trustedElapsedMs
import com.flint.peakfocus.blocking.overlay.ProtectionStatus
import com.flint.peakfocus.blocking.overlay.SafetyFloorGate
import com.flint.peakfocus.core.model.AccessClockStamp

/** Permission bits are prerequisites, never evidence that a detector is serving. */
internal data class TimerPermissions(
    val accessibilityEnabled: Boolean = false,
    val usageAccess: Boolean = false,
)

internal sealed interface TimerArmingState {
    data object Idle : TimerArmingState
    data class Applying(val ruleId: String) : TimerArmingState
    data class Armed(val ruleId: String, val checkedAt: AccessClockStamp) : TimerArmingState
    data class Degraded(val ruleId: String?, val reason: String, val committed: Boolean) : TimerArmingState
}

internal data class TimerArmAttempt(
    val ruleId: String,
    /** Null for a restored Timer; a new attempt needs an observation after its durable save. */
    val committedAt: AccessClockStamp?,
    val waitingSince: AccessClockStamp,
    val hadArmed: Boolean = false,
    val applyFailure: String? = null,
)

internal const val TIMER_ARM_CONFIRMATION_MS = 6_000L

/** Display/start preflight only. The serialized editor remains the mutation authority. */
internal fun timerPrerequisiteFailure(status: ProtectionStatus, permissions: TimerPermissions,
                                     livePathA: Boolean, now: AccessClockStamp): String? {
    val canUseA = permissions.accessibilityEnabled && livePathA && status.detectors.accessibilityConnected
    // Path B has a full-screen Activity fallback when overlay access is absent.
    val canUseB = permissions.usageAccess && status.detectors.pathB == ProtectionStatus.PathBState.ENFORCING
    if (!canUseA && !canUseB) return if (!permissions.accessibilityEnabled && !permissions.usageAccess)
        "Turn on Accessibility or grant Usage Access."
    else "Protection service is not connected. Retry or check setup."
    if (status.readiness != ProtectionStatus.Readiness.READY) return "Protection state is unavailable. Retry or check setup."
    val observed = status.observedAt
    if (observed == null || trustedElapsedMs(observed, now) == null ||
        status.validUntilElapsedRealtimeMs?.let { now.elapsedRealtimeMs < it } != true)
        return "The protection check is stale or unavailable. Retry to check again."
    val floor = status.safetyFloor
    val checked = floor?.checkedAtElapsedRealtimeMs
    if (!status.safetyFloorResolved || floor == null || floor.degraded || floor.failedSources.isNotEmpty() ||
        floor.requiredSources.isEmpty() || !floor.resolvedSources.containsAll(floor.requiredSources) ||
        checked == null || now.elapsedRealtimeMs < checked || now.elapsedRealtimeMs - checked >= SafetyFloorGate.DEFAULT_REFRESH_INTERVAL_MS)
        return "Calling or Home safety could not be checked. Retry before starting protection."
    if ((status.pass as? ProtectionStatus.PassStatus.RecoveryActive)?.let { it.logicalEndElapsedRealtimeMs > now.elapsedRealtimeMs } == true)
        return "Resume protection first."
    return null
}

/** A stored row cannot become Armed without a fresh check naming that exact Timer. */
internal fun timerArmingState(attempt: TimerArmAttempt, status: ProtectionStatus,
                             permissions: TimerPermissions, livePathA: Boolean,
                             now: AccessClockStamp): TimerArmingState {
    timerPrerequisiteFailure(status, permissions, livePathA, now)?.let {
        return TimerArmingState.Degraded(attempt.ruleId, it, committed = true)
    }
    val observed = checkNotNull(status.observedAt)
    val afterCommit = attempt.committedAt?.let { trustedElapsedMs(it, observed) != null } ?: true
    val exactCause = status.activeCauses.any {
        it.ruleId == attempt.ruleId && it.kind in setOf(ProtectionStatus.CauseKind.TIMER, ProtectionStatus.CauseKind.SESSION) &&
            it.remainingMs?.let { remaining -> remaining > 0 } == true &&
            it.logicalEndElapsedRealtimeMs?.let { end -> end > now.elapsedRealtimeMs } == true
    }
    if (afterCommit && exactCause) return TimerArmingState.Armed(attempt.ruleId, observed)
    attempt.applyFailure?.let { return TimerArmingState.Degraded(attempt.ruleId, it, committed = true) }
    val waited = trustedElapsedMs(attempt.waitingSince, now)
    return if (!attempt.hadArmed && waited != null && waited < TIMER_ARM_CONFIRMATION_MS)
        TimerArmingState.Applying(attempt.ruleId)
    else TimerArmingState.Degraded(attempt.ruleId,
        "The protection service has not confirmed this Timer. Its saved end time is unchanged. Retry or check setup.", committed = true)
}
