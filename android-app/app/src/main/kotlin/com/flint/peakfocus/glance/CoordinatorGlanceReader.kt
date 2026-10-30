package com.flint.peakfocus.glance

import com.flint.peakfocus.blocking.overlay.ProtectionStatus as CoordinatorStatus
import com.flint.peakfocus.blocking.overlay.ProtectionStatusHub
import com.flint.peakfocus.core.model.BreakLevel
import com.flint.peakfocus.feature.settings.BlockingHealthUi
import kotlinx.coroutines.flow.map

/**
 * Only C's in-process/current-boot hub is read; no protection snapshot is persisted by K.
 * C's coordinator owns policy observations; its service owns Path B leases. Pass availability
 * is independent of protection readiness and never reconstructed from legacy preferences.
 */
class CoordinatorGlanceReader : GlanceProtectionReader {
    override val changes = ProtectionStatusHub.status.map { Unit }
    override suspend fun read() = coordinatorGlanceReading(ProtectionStatusHub.status.value)
}

/** Input must come directly from C's process-local hub (or a test fixture), never from disk. */
internal fun coordinatorGlanceReading(status: CoordinatorStatus): ProtectionReading {
    val checked = status.observedAt
    if (checked == null ||
        status.validUntilElapsedRealtimeMs == null) return ProtectionReading()
    val pathBForegroundActive = when (status.detectors.pathB) {
        CoordinatorStatus.PathBState.STANDBY, CoordinatorStatus.PathBState.ENFORCING -> true
        CoordinatorStatus.PathBState.OFF -> false
        CoordinatorStatus.PathBState.UNKNOWN -> null
    }
    val detector = when {
        pathBForegroundActive == true -> GlanceDetector.PATH_B_FOREGROUND
        status.detectors.accessibilityConnected && pathBForegroundActive == false -> GlanceDetector.PATH_A_CONFIRMED
        else -> GlanceDetector.UNKNOWN
    }
    val base = ProtectionReading(
        checkedElapsedMs = checked.elapsedRealtimeMs,
        checkedEpochMs = checked.wallEpochMs,
        validUntilElapsedMs = status.validUntilElapsedRealtimeMs,
        // C explicitly guarantees the Hub resets at process/reboot. K never restores a cache.
        sameBootVerified = true,
        detector = detector,
        pass = when (val pass = status.pass) {
            CoordinatorStatus.PassStatus.Unavailable -> GlancePass.Unavailable
            CoordinatorStatus.PassStatus.Available -> GlancePass.Available
            is CoordinatorStatus.PassStatus.CoolingDown -> GlancePass.CoolingDown(pass.nextEligibleEstimateEpochMs, pass.clockUncertain)
            is CoordinatorStatus.PassStatus.RecoveryActive -> GlancePass.Recovery
        },
        next = status.nextOccurrence?.let { NextGlanceSession(displayName(it.displayName, it.kind), it.startWallEstimateEpochMs) },
        healthNotes = if (status.detectors.accessibilityConnected) contextualReasons
            .filter { it in status.detectors.degradedReasons }.mapNotNull(BlockingHealthUi::shortReasonNote)
            else emptyList(),
    )
    if (status.readiness != CoordinatorStatus.Readiness.READY) return base
    val pass = status.pass
    if (pass is CoordinatorStatus.PassStatus.RecoveryActive) return base.copy(
        status = ProtectionStatus.PASS_RECOVERY, recoveryEndElapsedMs = pass.logicalEndElapsedRealtimeMs,
    )
    val floor = status.safetyFloor
    val floorHealthy = status.safetyFloorResolved && floor != null && !floor.degraded &&
        floor.failedSources.isEmpty() && floor.resolvedSources.containsAll(floor.requiredSources) &&
        floor.checkedAtElapsedRealtimeMs?.let { it in 0..checked.elapsedRealtimeMs } == true
    // Contextual reasons do not disprove live primary protection. The raw service-owned
    // usage-access-missing (and every other fault) still takes the conservative branch.
    val faults = status.detectors.degradedReasons - contextualReasons - "pass-contract-unavailable"
    val disconnectedContext = !status.detectors.accessibilityConnected &&
        status.detectors.degradedReasons.any { it in contextualReasons }
    if (!floorHealthy || faults.isNotEmpty() || disconnectedContext ||
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
        cause = ActiveGlanceCause(selected.occurrenceId, displayName(selected.displayName, selected.kind),
            when (selected.tier) {
                BreakLevel.EASY -> GlanceTier.EASY
                BreakLevel.HARDER -> GlanceTier.HARDER
                BreakLevel.HARDCORE -> GlanceTier.HARDCORE
            }, selected.logicalEndElapsedRealtimeMs, kind))
}

private val focusKinds = setOf(CoordinatorStatus.CauseKind.SESSION, CoordinatorStatus.CauseKind.TIMER)
private val contextualReasons = listOf("path-b-backup-unavailable", "time-limits-need-usage-access")
private fun displayName(name: String, kind: CoordinatorStatus.CauseKind) =
    CoordinatorStatus.sanitizeDisplayName(name).ifBlank { causeName(kind) }
private fun causeName(kind: CoordinatorStatus.CauseKind) = when (kind) {
    CoordinatorStatus.CauseKind.SESSION -> "Focus session"
    CoordinatorStatus.CauseKind.TIMER -> "Timer"
    CoordinatorStatus.CauseKind.SCHEDULE -> "Schedule"
    CoordinatorStatus.CauseKind.SLEEP -> "Sleep"
    CoordinatorStatus.CauseKind.TIME_LIMIT -> "Time Limit"
    CoordinatorStatus.CauseKind.OPEN_LIMIT -> "Open Limit"
}
