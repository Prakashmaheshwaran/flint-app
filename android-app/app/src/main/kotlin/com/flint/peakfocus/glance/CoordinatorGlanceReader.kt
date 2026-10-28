package com.flint.peakfocus.glance

import com.flint.peakfocus.blocking.overlay.ProtectionStatus as CoordinatorStatus
import com.flint.peakfocus.blocking.overlay.ProtectionStatusHub
import com.flint.peakfocus.core.model.BreakLevel
import kotlinx.coroutines.flow.map

/** Only C's public snapshot is read. Missing boot/actual-FGS evidence stays unavailable. */
class CoordinatorGlanceReader(
    private val currentBootSessionId: () -> String? = { null },
    private val pathBForegroundActive: () -> Boolean? = { null },
) : GlanceProtectionReader {
    override val changes = ProtectionStatusHub.status.map { Unit }
    override suspend fun read() = coordinatorGlanceReading(
        ProtectionStatusHub.status.value, currentBootSessionId(), pathBForegroundActive(),
    )
}

internal fun coordinatorGlanceReading(status: CoordinatorStatus, currentBootId: String?,
                                     pathBForegroundActive: Boolean?): ProtectionReading {
    val checked = status.observedAt
    if (status.readiness != CoordinatorStatus.Readiness.READY || checked == null ||
        currentBootId.isNullOrBlank() || currentBootId != checked.bootSessionId ||
        status.pass == CoordinatorStatus.PassStatus.Unavailable) return ProtectionReading()
    val detector = when {
        pathBForegroundActive == true -> GlanceDetector.PATH_B_FOREGROUND
        status.detectors.accessibilityConnected && pathBForegroundActive == false -> GlanceDetector.PATH_A_CONFIRMED
        else -> GlanceDetector.UNKNOWN
    }
    val base = ProtectionReading(
        checkedElapsedMs = checked.elapsedRealtimeMs,
        validUntilElapsedMs = checked.elapsedRealtimeMs + GlanceDisplayMapper.MAX_AGE_MS,
        sameBootVerified = true,
        detector = detector,
        next = status.nextOccurrence?.let { NextGlanceSession(causeName(it.kind), it.startWallEstimateEpochMs) },
    )
    val pass = status.pass
    if (pass is CoordinatorStatus.PassStatus.RecoveryActive) return base.copy(
        status = ProtectionStatus.PASS_RECOVERY, recoveryEndElapsedMs = pass.logicalEndElapsedRealtimeMs,
    )
    if (!status.safetyFloorResolved || status.detectors.degradedReasons.isNotEmpty() ||
        (!status.detectors.accessibilityConnected && pathBForegroundActive != true && status.activeCauses.isNotEmpty())) {
        return base.copy(status = ProtectionStatus.DEGRADED)
    }
    // Display a focus cause first, without computing/arbitrating protection or tier policy.
    val selected = status.activeCauses.firstOrNull { it.kind in focusKinds } ?: status.activeCauses.firstOrNull()
        ?: return base.copy(status = ProtectionStatus.INACTIVE)
    val kind = when (selected.kind) {
        CoordinatorStatus.CauseKind.SESSION, CoordinatorStatus.CauseKind.TIMER -> GlanceCauseKind.FOCUS_SESSION
        CoordinatorStatus.CauseKind.SCHEDULE -> GlanceCauseKind.SCHEDULE
        CoordinatorStatus.CauseKind.SLEEP -> GlanceCauseKind.SLEEP
        CoordinatorStatus.CauseKind.TIME_LIMIT -> GlanceCauseKind.TIME_LIMIT
        CoordinatorStatus.CauseKind.OPEN_LIMIT -> GlanceCauseKind.OPEN_LIMIT
    }
    return base.copy(status = ProtectionStatus.ACTIVE,
        timeUncertain = status.activeCauses.any { it.timeUncertain },
        cause = ActiveGlanceCause(selected.ruleId.orEmpty(), causeName(selected.kind),
            when (selected.tier) {
                BreakLevel.EASY -> GlanceTier.EASY
                BreakLevel.HARDER -> GlanceTier.HARDER
                BreakLevel.HARDCORE -> GlanceTier.HARDCORE
            }, selected.logicalEndElapsedRealtimeMs, kind))
}

private val focusKinds = setOf(CoordinatorStatus.CauseKind.SESSION, CoordinatorStatus.CauseKind.TIMER)
private fun causeName(kind: CoordinatorStatus.CauseKind) = when (kind) {
    CoordinatorStatus.CauseKind.SESSION -> "Focus session"
    CoordinatorStatus.CauseKind.TIMER -> "Timer"
    CoordinatorStatus.CauseKind.SCHEDULE -> "Schedule"
    CoordinatorStatus.CauseKind.SLEEP -> "Sleep"
    CoordinatorStatus.CauseKind.TIME_LIMIT -> "Time Limit"
    CoordinatorStatus.CauseKind.OPEN_LIMIT -> "Open Limit"
}
