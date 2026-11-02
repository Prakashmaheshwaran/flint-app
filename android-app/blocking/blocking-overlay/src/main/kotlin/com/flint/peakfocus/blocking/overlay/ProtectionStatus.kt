package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.core.model.AccessClockStamp
import com.flint.peakfocus.core.model.BreakLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/**
 * Read-only snapshot of Android protection for surfaces that only *display* it (home widget,
 * ongoing notification, Help/health screens). Not an authority: nothing here grants, denies or
 * extends protection, and consumers must not compute tier/pass policy themselves.
 *
 * Until enforcement publishes a snapshot (lane C: coordinator + detectors), [ProtectionStatusHub]
 * holds [ProtectionStatus.UNKNOWN]: readiness UNAVAILABLE and no causes — show "unavailable",
 * never "protected". Countdowns use [ActiveCause.logicalEndElapsedRealtimeMs] (same boot only,
 * per ADR-012 §9); the wall-clock end is a display estimate. Stored occurrence ends here
 * are anchored; legacy engine window enforcement remains wall-based until the separate
 * R-18 detector clock integration lands. A snapshot does not prove exact enforcement.
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
    /** Runtime provenance and the latest lookup health; sticky resolution alone is insufficient. */
    val safetyFloor: SafetyFloorStatus? = null,
    /** Persisted loss under bounded history retention; never read the enforcement ledger in UI. */
    val lifecycleEventsDropped: Long = 0,
) {
    /** Last actual policy check, not the time a widget or detector-health publisher read it. */
    val lastCheckedEpochMs: Long? get() = observedAt?.wallEpochMs

    enum class Readiness { LOADING, READY, UNAVAILABLE }

    enum class CauseKind { SESSION, TIMER, SCHEDULE, SLEEP, TIME_LIMIT, OPEN_LIMIT }

    data class SafetyFloorStatus(
        val requiredSources: Set<String>,
        val resolvedSources: Set<String>,
        val failedSources: Set<String>,
        val checkedAtElapsedRealtimeMs: Long?,
        val degraded: Boolean,
    )

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
        /** Exact materialized occurrence; a reusable rule ID is not session identity. */
        val occurrenceId: String? = null,
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
        private val DISPLAY_WHITESPACE = Regex("[\\p{Cntrl}\\s]+")

        /** Collapse whitespace/control characters and cap at [MAX_DISPLAY_NAME] for display surfaces. */
        fun sanitizeDisplayName(raw: String): String =
            raw.replace(DISPLAY_WHITESPACE, " ").trim().take(MAX_DISPLAY_NAME)
    }
}

/** Testable publisher; the process-wide public facade below owns its production clock/scope. */
internal class ProtectionStatusPublisher(
    private val nowElapsedMs: () -> Long,
    private val expiryScope: CoroutineScope,
) {
    private val state = MutableStateFlow(ProtectionStatus.UNKNOWN)
    private val lock = Any()
    private var policy = ProtectionStatus.UNKNOWN
    private var pathB = ProtectionStatus.PathBState.UNKNOWN
    private var pathBReasons: Set<String> = emptySet()
    private var pathBLease: Long? = null
    private var leaseGeneration = 0L
    private var leaseExpiry: Job? = null

    private val readonly = state.asStateFlow()
    val status: StateFlow<ProtectionStatus> = object : StateFlow<ProtectionStatus> by readonly {
        override val value: ProtectionStatus get() = synchronized(lock) { publishLocked(); state.value }
        override val replayCache: List<ProtectionStatus> get() = listOf(value)
    }

    /**
     * Coordinator-only policy publication. Path B mode is owned by its live service and
     * merged here; an unrelated policy refresh cannot erase service health or infer it
     * from permissions. Collection fields are frozen before crossing the read-only seam.
     */
    fun publish(status: ProtectionStatus) = synchronized(lock) {
        policy = status.copy(activeCauses = status.activeCauses.toList(),
            safetyFloor = status.safetyFloor?.let { it.copy(requiredSources = it.requiredSources.toSet(),
                resolvedSources = it.resolvedSources.toSet(), failedSources = it.failedSources.toSet()) },
            detectors = status.detectors.copy(degradedReasons = status.detectors.degradedReasons.toSet()))
        publishLocked()
    }

    /**
     * Service-lifecycle sink for Path B (blocking-usagestats). Report UNKNOWN during startup,
     * STANDBY/ENFORCING only after foreground startup succeeds, and OFF after stopping. Reasons
     * describe observed failures such as "usage-access-missing"; an empty set clears previous
     * Path B reasons. A lease also bounds snapshot validity, never advances the policy's
     * observation or lastCheckedEpochMs, and never authorizes enforcement. Renew a lease only
     * after confirmed evaluation success. Expiry reads UNKNOWN and emits to collectors even
     * if the service died without a lifecycle callback; null retains unleased compatibility.
     */
    fun publishPathBHealth(
        pathBState: ProtectionStatus.PathBState,
        degradedReasons: Set<String> = emptySet(),
        leaseUntilElapsedRealtimeMs: Long? = null,
    ) = synchronized(lock) {
        require(leaseUntilElapsedRealtimeMs == null || leaseUntilElapsedRealtimeMs >= 0)
        pathB = pathBState
        pathBReasons = degradedReasons.toSet()
        pathBLease = leaseUntilElapsedRealtimeMs
        leaseExpiry?.cancel()
        leaseGeneration++
        val generation = leaseGeneration
        leaseExpiry = leaseUntilElapsedRealtimeMs?.let { deadline -> expiryScope.launch {
            repeat(8) {
                val before = nowElapsedMs()
                val remaining = (deadline - before).coerceAtLeast(0L)
                if (remaining > 0L) delay(remaining)
                synchronized(lock) {
                    if (generation != leaseGeneration) return@launch
                    publishLocked()
                    if (nowElapsedMs() >= deadline) return@launch
                    // System elapsed time progresses across delay. A pinned/inconsistent
                    // injected clock must not leave an unbounded timer retry loop.
                    if (nowElapsedMs() <= before) return@launch
                }
            }
        } }
        publishLocked()
    }

    private fun publishLocked() {
        val expired = pathBLease?.let { nowElapsedMs() >= it } == true
        state.value = policy.copy(detectors = policy.detectors.copy(
            pathB = if (expired) ProtectionStatus.PathBState.UNKNOWN else pathB,
            degradedReasons = policy.detectors.degradedReasons + pathBReasons + if (expired) setOf("path-b-health-expired") else emptySet(),
        ), validUntilElapsedRealtimeMs = listOfNotNull(policy.validUntilElapsedRealtimeMs, pathBLease).minOrNull())
    }

    internal fun resetForTests() = synchronized(lock) {
        policy = ProtectionStatus.UNKNOWN
        pathB = ProtectionStatus.PathBState.UNKNOWN
        pathBReasons = emptySet()
        pathBLease = null
        leaseExpiry?.cancel()
        leaseExpiry = null
        leaseGeneration++
        publishLocked()
    }
}

/** Process-wide read-only status. This facade has no grant/deny/extend authority. */
object ProtectionStatusHub {
    private val publisher = ProtectionStatusPublisher(SystemClock::elapsedRealtime,
        CoroutineScope(SupervisorJob() + Dispatchers.Default))
    /**
     * Display surfaces read .value before rendering: it applies lease expiry synchronously.
     * A collector's first replay may precede the expiry job; a health view must not treat
     * that replay or a passed validUntilElapsedRealtimeMs as a newly checked policy.
     */
    val status: StateFlow<ProtectionStatus> = publisher.status

    /** Coordinator-only complete policy snapshot; preserves the service-owned Path B report. */
    fun publish(status: ProtectionStatus) = publisher.publish(status)

    /**
     * Path B service-lifecycle sink: UNKNOWN at startup, STANDBY/ENFORCING after foreground
     * startup succeeds, OFF on stop. Renew [leaseUntilElapsedRealtimeMs] only on confirmed
     * evaluation success. Expiry becomes UNKNOWN without changing A/policy observations;
     * snapshot validity is min(policy deadline, lease). Null keeps unleased compatibility.
     */
    fun publishPathBHealth(
        pathBState: ProtectionStatus.PathBState,
        degradedReasons: Set<String> = emptySet(),
        leaseUntilElapsedRealtimeMs: Long? = null,
    ) = publisher.publishPathBHealth(pathBState, degradedReasons, leaseUntilElapsedRealtimeMs)

    internal fun resetForTests() = publisher.resetForTests()
}
