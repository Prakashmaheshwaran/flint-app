package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.core.model.SafetyFloor
import com.flint.peakfocus.core.model.EnforcementSignals
import com.flint.peakfocus.core.common.RuntimeSafetySurfaces
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Cached safety-floor answers for the detectors' hot paths (audit R2). The static floor is a set
 * lookup; the runtime half (default home app, default/system dialer) needs PackageManager/Telecom
 * binder calls. Ordinary polling refreshes run off the accessibility event (main) thread;
 * the explicit new-mount check below retains its synchronous freshness guarantee:
 *
 *  - [protects] is a pure read of the cached set. When the cache is older than
 *    [refreshIntervalMs] it hands a refresh to [refreshInBackground] (one in flight at a time)
 *    and answers from the last known set meanwhile. An unresolved cold start allows
 *    unclassifiable packages until the background snapshot arrives.
 *  - [protectsFresh] keeps the cached decision during background refresh. Only maxAgeMs=0
 *    performs the pre-existing synchronous check before a new mount, never on an existing
 *    shield's poll/re-render. Snapshot completion wakes both detectors, including a stationary
 *    target that was conservatively allowed during cold start.
 *    That synchronous check can wait for an in-flight resolver's refreshLock; it is not a
 *    nonblocking API. The lock never covers cached membership reads.
 *
 * The resolver reports each source separately (null = that lookup failed). A failed source keeps
 * its last known packages, so a transient binder failure can never shrink the floor — e.g. drop a
 * custom default dialer while the home lookup still succeeds. While a required source is
 * unresolved, candidate calling/Home surfaces remain accessible. If candidate discovery also
 * fails, only positively classified ordinary launchers remain blockable; unclassifiable
 * packages stay accessible for phone safety. Publish degraded coverage until resolution recovers. This is permission
 * to leave access open, not proof that the package is a safety identity. Plain JVM, clock and executor
 * injected, unit-tested in `SafetyFloorGateTest`.
 */
class SafetyFloorGate(
    private val selfPackage: String,
    private val resolveRuntime: () -> Map<String, Set<String>?>,
    private val nowElapsedMs: () -> Long,
    /** Runs a refresh off the caller's thread; null = refresh inline (tests, simple hosts). */
    private val refreshInBackground: ((() -> Unit) -> Unit)? = null,
    private val refreshIntervalMs: Long = DEFAULT_REFRESH_INTERVAL_MS,
    private val freshIntervalMs: Long = DEFAULT_FRESH_INTERVAL_MS,
    requiredRuntimeSources: Set<String>,
) {
    data class RuntimeResolution(
        val requiredSources: Set<String>,
        val resolvedSources: Set<String> = emptySet(),
        val failedSources: Set<String> = emptySet(),
        val checkedAtElapsedMs: Long? = null,
        /** Diagnostic/test scheduling flag only; checking alone never means degraded health. */
        val checking: Boolean = false,
    ) {
        /** Sticky provenance only; health consumers must also read degraded and checkedAtElapsedMs. */
        val hasResolvedEverySource: Boolean get() = resolvedSources.containsAll(requiredSources)
        val degraded: Boolean get() = !hasResolvedEverySource || failedSources.any { it in requiredSources }
    }

    private val resolution = MutableStateFlow(RuntimeResolution(requiredRuntimeSources.toSet()))
    /** Read-only provenance; a failed/missing lookup is never counted as a resolved source. */
    val runtimeResolution = resolution.asStateFlow()
    private val lock = Any()
    private val refreshLock = Any()
    private val refreshing = AtomicBoolean(false)
    @Volatile private var bySource: Map<String, Set<String>> = emptyMap()
    private var successfulAtBySource: Map<String, Long> = emptyMap()
    @Volatile private var runtime: Set<String> = emptySet()
    @Volatile private var resolvedAtMs: Long? = null
    private var homeCandidates: Set<String> = emptySet()
    private var callingCandidates: Set<String> = emptySet()
    private var homeCandidatesUnavailable = true
    private var callingCandidatesUnavailable = true
    private var ordinaryLaunchers: Set<String> = emptySet()
    private var refreshRequestId = 0L
    private var submittedAtMs: Long? = null
    private var taskStarted = false

    init {
        if (refreshInBackground != null) scheduleRefresh()
    }

    /** Floor check against the cached runtime set; schedules a background refresh when stale. */
    fun protects(packageName: String): Boolean {
        if (SafetyFloor.isProtected(packageName, selfPackage)) return true
        if (resolvedAtMs == null) {
            scheduleRefresh()
        } else if (runtimeResolution.value.degraded && isOlderThan(UNRESOLVED_RETRY_MS)) {
            scheduleRefresh()
        } else if (isOlderThan(refreshIntervalMs)) {
            scheduleRefresh()
        }
        return allowForSafety(packageName)
    }

    /**
     * Display-only retained provenance for this target. Does not resolve, schedule a refresh,
     * or change health timestamps; enforcement owns cache refresh through [protects].
     * Reads only the short membership lock; binder resolution holds the separate refresh lock.
     * Conservative candidate exemptions are not proof of a runtime safety identity.
     */
    fun readIdentity(packageName: String): FloorIdentity? {
        if (packageName == selfPackage) return FloorIdentity(FloorIdentity.Kind.SELF)
        if (SafetyFloor.isProtected(packageName, selfPackage)) return FloorIdentity(FloorIdentity.Kind.STATIC_SYSTEM)
        return synchronized(lock) {
            val source = listOf("home", "default-dialer", "system-dialer", "uninstaller")
                .firstOrNull { packageName in bySource[it].orEmpty() } ?: return@synchronized null
            val kind = when (source) {
                "home" -> FloorIdentity.Kind.RUNTIME_HOME
                "uninstaller" -> FloorIdentity.Kind.RUNTIME_UNINSTALLER
                else -> FloorIdentity.Kind.RUNTIME_DIALER
            }
            val successfulAt = successfulAtBySource[source]
            val now = nowElapsedMs()
            FloorIdentity(kind, successfulAt, source in resolution.value.failedSources || successfulAt == null ||
                now < successfulAt || now - successfulAt >= refreshIntervalMs)
        }
    }

    /** Cached decision with background refresh; maxAgeMs=0 retains the one-per-new-mount check. */
    fun protectsFresh(packageName: String, maxAgeMs: Long = freshIntervalMs): Boolean {
        if (SafetyFloor.isProtected(packageName, selfPackage)) return true
        // showBlocked requests zero only for a NEW mount attempt. Retain its existing fresh
        // role guarantee; an opaque asynchronous mount request is a separate integration task.
        if (maxAgeMs == 0L) {
            refreshIfOlderThan(0L)
            return allowForSafety(packageName)
        }
        // Explicit null is the synchronous fake/simple-host seam.
        if (refreshInBackground == null) {
            refreshIfOlderThan(if (runtimeResolution.value.degraded) minOf(maxAgeMs, UNRESOLVED_RETRY_MS) else maxAgeMs)
            return allowForSafety(packageName)
        }
        val age = if (runtimeResolution.value.degraded) minOf(maxAgeMs, UNRESOLVED_RETRY_MS) else maxAgeMs
        if (isOlderThan(age)) {
            scheduleRefresh()
            return allowForSafety(packageName)
        }
        return allowForSafety(packageName)
    }

    /** Never combine a refreshed set with the previous successful resolution metadata. */
    private fun allowForSafety(packageName: String): Boolean = synchronized(lock) {
        val provenance = resolution.value
        fun unresolved(source: String) = source in provenance.requiredSources &&
            (source !in provenance.resolvedSources || source in provenance.failedSources)
        val homeMissing = unresolved(RuntimeSafetySurfaces.SOURCE_HOME)
        val callingMissing = unresolved(RuntimeSafetySurfaces.SOURCE_DEFAULT_DIALER)
        packageName in runtime || (homeMissing && packageName in homeCandidates) ||
            (callingMissing && packageName in callingCandidates) ||
            (((homeMissing && homeCandidatesUnavailable) || (callingMissing && callingCandidatesUnavailable)) &&
                packageName !in ordinaryLaunchers)
    }

    /** Ask for a background refresh if the cache is older than [maxAgeMs] (e.g. while a shield is up). */
    fun refreshSoonIfOlderThan(maxAgeMs: Long) {
        if (resolvedAtMs == null || isOlderThan(maxAgeMs)) scheduleRefresh()
    }

    private fun isOlderThan(maxAgeMs: Long): Boolean {
        val at = resolvedAtMs ?: return true
        val now = nowElapsedMs()
        return now < at || now - at >= maxAgeMs
    }

    private fun scheduleRefresh() {
        val executor = refreshInBackground
        if (executor == null) {
            refreshIfOlderThan(0L)
            return
        }
        val request = synchronized(lock) {
            val at = submittedAtMs
            val now = nowElapsedMs()
            // A cancelled/rejected executor may never enter the callback. Expire only an
            // unstarted submission; a slow running binder never spawns competing refreshes.
            if (refreshing.get() && !taskStarted && at != null && (now < at || now - at >= SUBMISSION_TIMEOUT_MS)) {
                refreshing.set(false)
            }
            if (!refreshing.compareAndSet(false, true)) return
            refreshRequestId++
            submittedAtMs = now
            taskStarted = false
            resolution.value = resolution.value.copy(checking = true)
            refreshRequestId
        }
        val submitted = runCatching {
            executor {
                val current = synchronized(lock) {
                    (request == refreshRequestId).also { if (it) taskStarted = true }
                }
                if (!current) return@executor
                try {
                    refreshIfOlderThan(0L)
                    EnforcementSignals.detectors.requestEvaluation()
                } finally {
                    synchronized(lock) {
                        if (request == refreshRequestId) {
                            refreshing.set(false)
                            submittedAtMs = null
                            taskStarted = false
                            resolution.value = resolution.value.copy(checking = false)
                        }
                    }
                }
            }
        }
        if (submitted.isFailure) synchronized(lock) {
            if (request == refreshRequestId) {
                refreshing.set(false)
                submittedAtMs = null
                resolution.value = resolution.value.copy(checking = false)
            }
        }
    }

    private fun refreshIfOlderThan(maxAgeMs: Long) {
        if (maxAgeMs > 0L && !isOlderThan(maxAgeMs)) return
        synchronized(refreshLock) {
            if (maxAgeMs > 0L && !isOlderThan(maxAgeMs)) return
            val resolved = runCatching(resolveRuntime).getOrNull().orEmpty()
            synchronized(lock) {
                // Per source: a successful lookup replaces that source; a failed (null) or missing
                // one keeps its last known packages. Binder work never holds this membership lock.
                val merged = HashMap(bySource)
                val successTimes = HashMap(successfulAtBySource)
                val checkedAt = nowElapsedMs()
                for ((source, packages) in resolved) {
                    if (source !in RuntimeSafetySurfaces.CANDIDATE_SOURCES && packages != null) {
                        merged[source] = packages
                        successTimes[source] = checkedAt
                    }
                }
                bySource = merged
                successfulAtBySource = successTimes
                runtime = merged.values.flatMapTo(HashSet()) { it }
                val home = resolved[RuntimeSafetySurfaces.SOURCE_CANDIDATE_HOME]
                val calling = resolved[RuntimeSafetySurfaces.SOURCE_CANDIDATE_CALLING]
                homeCandidatesUnavailable = home == null
                callingCandidatesUnavailable = calling == null
                if (home != null) homeCandidates = home
                if (calling != null) callingCandidates = calling
                // Positive ordinary classifications must never survive a failed new snapshot.
                ordinaryLaunchers = resolved[RuntimeSafetySurfaces.SOURCE_ORDINARY_LAUNCHERS].orEmpty()
                // Stamp even on failure so a broken resolver is retried at the interval, not on
                // every event; the previous sets stay in force meanwhile.
                resolvedAtMs = checkedAt
                resolution.value = resolution.value.copy(
                    resolvedSources = merged.keys.toSet(),
                    failedSources = resolution.value.requiredSources.filterTo(HashSet()) { resolved[it] == null },
                    checkedAtElapsedMs = resolvedAtMs,
                    checking = false,
                )
            }
        }
    }

    companion object {
        const val DEFAULT_REFRESH_INTERVAL_MS: Long = 30_000L
        /** Also the coordinator's visible-shield refresh age, so re-renders never resolve inline more often. */
        const val DEFAULT_FRESH_INTERVAL_MS: Long = 5_000L
        /** Unresolved coverage is retried on the next evaluation after this bounded backoff. */
        const val UNRESOLVED_RETRY_MS: Long = 1_000L
        const val SUBMISSION_TIMEOUT_MS: Long = 5_000L
    }

    // Last member: register only a fully constructed gate for read-only detail queries.
    init { TargetFloorIdentities.register(this) }
}
