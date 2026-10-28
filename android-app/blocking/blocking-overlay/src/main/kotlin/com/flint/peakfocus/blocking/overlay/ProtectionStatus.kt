package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.core.model.AccessClockStamp
import com.flint.peakfocus.core.model.BreakLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Read-only snapshot of Android protection for surfaces that only *display* it (home widget,
 * ongoing notification, Help/health screens). Not an authority: nothing here grants, denies or
 * extends protection, and consumers must not compute tier/pass policy themselves.
 *
 * Until enforcement publishes a snapshot (lane C: coordinator + detectors), [ProtectionStatusHub]
 * holds [ProtectionStatus.UNKNOWN]: readiness UNAVAILABLE and no causes — show "unavailable",
 * never "protected". Countdowns use [ActiveCause.logicalEndElapsedRealtimeMs] (same boot only,
 * per ADR-012 §9); the wall-clock end is a display estimate.
 */
data class ProtectionStatus(
    val readiness: Readiness,
    val activeCauses: List<ActiveCause>,
    val nextOccurrence: NextOccurrence?,
    val pass: PassStatus,
    val detectors: DetectorHealth,
    /** False until every runtime safety-floor source (default dialer/home, uninstaller) resolved once (§7). */
    val safetyFloorResolved: Boolean,
    /** When this snapshot was computed; null for [UNKNOWN]. */
    val observedAt: AccessClockStamp?,
) {
    enum class Readiness { LOADING, READY, UNAVAILABLE }

    enum class CauseKind { SESSION, TIMER, SCHEDULE, SLEEP, TIME_LIMIT, OPEN_LIMIT }

    data class ActiveCause(
        val ruleId: String?,
        val kind: CauseKind,
        val tier: BreakLevel,
        /** Remaining at [observedAt]; null when open-ended (legacy only). */
        val remainingMs: Long?,
        /** Same-boot logical end on `SystemClock.elapsedRealtime()`; null when open-ended or unknown. */
        val logicalEndElapsedRealtimeMs: Long?,
        val wallEndEstimateEpochMs: Long?,
        /** Reboot or clock discontinuity since the cause started: the wall estimate is uncertain. */
        val timeUncertain: Boolean,
    )

    data class NextOccurrence(val ruleId: String, val kind: CauseKind, val startWallEstimateEpochMs: Long)

    sealed interface PassStatus {
        data object Available : PassStatus
        data class CoolingDown(val nextEligibleEstimateEpochMs: Long, val clockUncertain: Boolean) : PassStatus
        data class RecoveryActive(val remainingMs: Long, val logicalEndElapsedRealtimeMs: Long) : PassStatus
        data object Unavailable : PassStatus
    }

    enum class PathBState { OFF, STANDBY, ENFORCING }

    data class DetectorHealth(
        val accessibilityConnected: Boolean,
        val pathB: PathBState,
        /** Short machine-readable reasons, e.g. "usage-access-missing", "overlay-missing". */
        val degradedReasons: Set<String>,
    )

    companion object {
        val UNKNOWN = ProtectionStatus(
            readiness = Readiness.UNAVAILABLE,
            activeCauses = emptyList(),
            nextOccurrence = null,
            pass = PassStatus.Unavailable,
            detectors = DetectorHealth(accessibilityConnected = false, pathB = PathBState.OFF, degradedReasons = emptySet()),
            safetyFloorResolved = false,
            observedAt = null,
        )
    }
}

/** Process-wide holder; enforcement publishes, display surfaces collect [status]. */
object ProtectionStatusHub {
    private val state = MutableStateFlow(ProtectionStatus.UNKNOWN)

    val status: StateFlow<ProtectionStatus> = state.asStateFlow()

    /** Enforcement-only (lane C). */
    fun publish(status: ProtectionStatus) {
        state.value = status
    }
}
