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
 *
 * Boot identity: the hub is process memory, so a snapshot read from [ProtectionStatusHub.status]
 * always belongs to the current boot (a reboot restarts the process and the hub at [UNKNOWN]). A
 * consumer that persists a snapshot (e.g. for a widget) must discard it unless it re-reads the
 * hub, and must stop trusting it after [validUntilElapsedRealtimeMs].
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
    /**
     * The snapshot is stale at or after this `elapsedRealtime()` (the next cause boundary, grant
     * or recovery end, or a freshness horizon); null for [UNKNOWN]. Enforcement republishes by then.
     */
    val validUntilElapsedRealtimeMs: Long?,
) {
    enum class Readiness { LOADING, READY, UNAVAILABLE }

    enum class CauseKind { SESSION, TIMER, SCHEDULE, SLEEP, TIME_LIMIT, OPEN_LIMIT }

    data class ActiveCause(
        val ruleId: String?,
        /** Sanitized user-facing name (rule/session name), at most [MAX_DISPLAY_NAME] chars. */
        val displayName: String,
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

    data class NextOccurrence(
        val ruleId: String,
        /** Sanitized user-facing name, at most [MAX_DISPLAY_NAME] chars. */
        val displayName: String,
        val kind: CauseKind,
        val startWallEstimateEpochMs: Long,
    )

    sealed interface PassStatus {
        data object Available : PassStatus
        data class CoolingDown(val nextEligibleEstimateEpochMs: Long, val clockUncertain: Boolean) : PassStatus
        data class RecoveryActive(val remainingMs: Long, val logicalEndElapsedRealtimeMs: Long) : PassStatus
        data object Unavailable : PassStatus
    }

    /**
     * Path B's *live* service state, published by the service itself from its own lifecycle
     * (foreground started / stopped) — never inferred from permissions or configuration.
     * STANDBY and ENFORCING both mean the foreground service and its ongoing notification are
     * live; OFF means positively stopped; UNKNOWN covers startup and detector handoff.
     */
    enum class PathBState { UNKNOWN, OFF, STANDBY, ENFORCING }

    data class DetectorHealth(
        /** Path A is bound and serving (service liveness, not the Settings grant bit). */
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
            detectors = DetectorHealth(accessibilityConnected = false, pathB = PathBState.UNKNOWN, degradedReasons = emptySet()),
            safetyFloorResolved = false,
            observedAt = null,
            validUntilElapsedRealtimeMs = null,
        )

        const val MAX_DISPLAY_NAME = 64

        /** Collapse whitespace/control characters and cap at [MAX_DISPLAY_NAME] for display surfaces. */
        fun sanitizeDisplayName(raw: String): String =
            raw.replace(Regex("[\\p{Cntrl}\\s]+"), " ").trim().take(MAX_DISPLAY_NAME)
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
