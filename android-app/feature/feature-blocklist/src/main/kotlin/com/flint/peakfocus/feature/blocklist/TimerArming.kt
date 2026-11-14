package com.flint.peakfocus.feature.blocklist

import com.flint.peakfocus.blocking.engine.trustedElapsedMs
import com.flint.peakfocus.blocking.overlay.ProtectionStatus
import com.flint.peakfocus.core.model.AccessClockStamp

/** Permission bits are prerequisites, never evidence that a detector is serving. */
internal data class TimerPermissions(
    val accessibilityEnabled: Boolean = false,
    val usageAccess: Boolean = false,
    /**
     * SYSTEM_ALERT_WINDOW grant. Path B's only reliable block surface is the overlay
     * window; without it the Activity fallback is denied by background-activity-launch
     * limits whenever the user is in another app, so Path B alone proves nothing.
     */
    val overlayGranted: Boolean = false,
)

internal sealed interface TimerArmingState {
    data object Idle : TimerArmingState
    data class Applying(val ruleId: String) : TimerArmingState
    data class Checking(val ruleId: String) : TimerArmingState
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
internal const val TIMER_STATUS_GRACE_MS = 2_000L

/** Display/start preflight only. The serialized editor remains the mutation authority. */
internal fun timerPrerequisiteFailure(status: ProtectionStatus, permissions: TimerPermissions,
                                     livePathA: Boolean, now: AccessClockStamp): String? =
    timerInfrastructureFailure(status, permissions, livePathA, now) ?: timerSnapshotFailure(status, now)

/**
 * How old a safety-floor resolution the *display* gate accepts. Enforcement mounts still
 * use their own zero-age/5s checks; this only stops the planner's readiness indicator
 * flapping when no evaluation has re-resolved the floor within the hot 5s window.
 */
internal const val TIMER_FLOOR_FRESH_MS: Long = 60_000L

private fun timerInfrastructureFailure(status: ProtectionStatus, permissions: TimerPermissions,
                                       livePathA: Boolean, now: AccessClockStamp): String? {
    val canUseA = permissions.accessibilityEnabled && livePathA && status.detectors.accessibilityConnected
    // Path B qualifies only with a real block surface: the overlay window. A running
    // poll loop alone is detection, not enforcement - its Activity fallback is denied
    // by background-activity-launch limits whenever the user is in another app.
    val canUseB = permissions.usageAccess && status.detectors.pathB == ProtectionStatus.PathBState.ENFORCING &&
        permissions.overlayGranted
    if (!canUseA && !canUseB) {
        if (!permissions.accessibilityEnabled && !permissions.usageAccess)
            return "Turn on Accessibility or grant Usage Access."
        if (permissions.usageAccess && status.detectors.pathB == ProtectionStatus.PathBState.ENFORCING &&
            !permissions.overlayGranted)
            return "Flint can see app opens but cannot block yet. Allow Display over other apps, or turn on the Flint Accessibility service."
        return "Protection service is not connected. Retry or check setup."
    }
    if (status.readiness != ProtectionStatus.Readiness.READY) return "Protection state is unavailable. Retry or check setup."
    val floor = status.safetyFloor
    val checked = floor?.checkedAtElapsedRealtimeMs
    // Enforcement mounts do their own zero-age checks; the planner only needs a
    // recently resolved floor, not one refreshed inside the 5s enforcement window.
    if (!status.safetyFloorResolved || floor == null || floor.degraded || floor.failedSources.isNotEmpty() ||
        floor.requiredSources.isEmpty() || !floor.resolvedSources.containsAll(floor.requiredSources) ||
        checked == null || now.elapsedRealtimeMs < checked || now.elapsedRealtimeMs - checked >= TIMER_FLOOR_FRESH_MS)
        return "Calling or Home safety could not be checked. Retry before starting protection."
    if ((status.pass as? ProtectionStatus.PassStatus.RecoveryActive)?.let { it.logicalEndElapsedRealtimeMs > now.elapsedRealtimeMs } == true)
        return "Resume protection first."
    return null
}

private fun timerSnapshotFailure(status: ProtectionStatus, now: AccessClockStamp): String? =
    if (status.observedAt?.let { trustedElapsedMs(it, now) } == null ||
        status.validUntilElapsedRealtimeMs?.let { now.elapsedRealtimeMs < it } != true)
        "The protection check is stale or unavailable. Retry to check again." else null

/**
 * R-30: the latest real shield mount did not deliver a primary surface. Deliberately absent
 * from the start preflight: refusing a new Timer never adds protection, and its mount is still
 * attempted. It only withholds Armed until a later primary mount succeeds.
 */
private fun timerSurfaceFailure(status: ProtectionStatus): String? = with(status.detectors.degradedReasons) {
    when {
        ProtectionStatus.REASON_BLOCK_SURFACE_MOUNT_FAILED in this ->
            "Flint couldn't show its block screen. Its saved end time is unchanged. Open a blocked app to retry, or check setup."
        ProtectionStatus.REASON_BLOCK_SURFACE_FALLBACK_ONLY in this ->
            "Flint couldn't draw its block screen over apps, so blocking may not appear. Its saved end time is unchanged. Check setup."
        else -> null
    }
}

/** A stored row cannot become Armed without a fresh check naming that exact Timer. */
internal fun timerArmingState(attempt: TimerArmAttempt, status: ProtectionStatus,
                             permissions: TimerPermissions, livePathA: Boolean,
                             now: AccessClockStamp): TimerArmingState {
    timerInfrastructureFailure(status, permissions, livePathA, now)?.let {
        return TimerArmingState.Degraded(attempt.ruleId, it, committed = true)
    }
    timerSnapshotFailure(status, now)?.let { reason ->
        attempt.applyFailure?.let { return TimerArmingState.Degraded(attempt.ruleId, it, committed = true) }
        val until = status.validUntilElapsedRealtimeMs
        val withinGrace = status.observedAt?.let { trustedElapsedMs(it, now) } != null && until != null &&
            now.elapsedRealtimeMs >= until && now.elapsedRealtimeMs - until <= TIMER_STATUS_GRACE_MS
        return if (withinGrace) TimerArmingState.Checking(attempt.ruleId)
            else TimerArmingState.Degraded(attempt.ruleId, reason, committed = true)
    }
    timerSurfaceFailure(status)?.let { return TimerArmingState.Degraded(attempt.ruleId, it, committed = true) }
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
