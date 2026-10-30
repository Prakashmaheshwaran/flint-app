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
 * binder calls, which must stay off the accessibility event (main) thread:
 *
 *  - [protects] is a pure read of the cached set. When the cache is older than
 *    [refreshIntervalMs] it hands a refresh to [refreshInBackground] (one in flight at a time)
 *    and answers from the last known set meanwhile. An unresolved cold start allows
 *    unclassifiable packages until the background snapshot arrives.
 *  - [protectsFresh] defers a stale decision until a background snapshot arrives. A forced
 *    new mount (maxAgeMs=0) consumes one completed refresh receipt for that package; it never
 *    runs binder work on the caller. Snapshot completion wakes both detectors, including a
 *    stationary target that was conservatively allowed during cold start.
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
        val checking: Boolean = false,
    ) {
        /** Sticky provenance only; health consumers must also read degraded and checkedAtElapsedMs. */
        val hasResolvedEverySource: Boolean get() = resolvedSources.containsAll(requiredSources)
        val degraded: Boolean get() = checking || !hasResolvedEverySource || failedSources.any { it in requiredSources }
    }

    private val resolution = MutableStateFlow(RuntimeResolution(requiredRuntimeSources.toSet()))
    /** Read-only provenance; a failed/missing lookup is never counted as a resolved source. */
    val runtimeResolution = resolution.asStateFlow()
    private val lock = Any()
    private val refreshLock = Any()
    private val refreshing = AtomicBoolean(false)
    @Volatile private var bySource: Map<String, Set<String>> = emptyMap()
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
    private val pendingMounts = mutableSetOf<String>()
    private val completedMounts = mutableSetOf<String>()

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

    /** Floor check for a decision about to block: resolves inline when older than [maxAgeMs]. */
    fun protectsFresh(packageName: String, maxAgeMs: Long = freshIntervalMs): Boolean {
        if (SafetyFloor.isProtected(packageName, selfPackage)) return true
        // The explicit null executor is a synchronous fake/simple-host seam. Production
        // supplies a background executor; neither fresh checks nor forced mounts bypass it.
        if (refreshInBackground == null) {
            refreshIfOlderThan(if (runtimeResolution.value.degraded) minOf(maxAgeMs, UNRESOLVED_RETRY_MS) else maxAgeMs)
            return allowForSafety(packageName)
        }
        val age = if (runtimeResolution.value.degraded) minOf(maxAgeMs, UNRESOLVED_RETRY_MS) else maxAgeMs
        if (maxAgeMs == 0L) {
            val completed = synchronized(lock) {
                if (completedMounts.remove(packageName) && !isOlderThan(freshIntervalMs)) true
                else { pendingMounts += packageName; false }
            }
            if (!completed) { scheduleRefresh(); return true }
        } else if (isOlderThan(age)) {
            scheduleRefresh()
            return true // No stale mounted/future surface may cover a newly selected role.
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
                    var retryMount = false
                    synchronized(lock) {
                        if (request == refreshRequestId) {
                            refreshing.set(false)
                            submittedAtMs = null
                            taskStarted = false
                            resolution.value = resolution.value.copy(checking = false)
                            retryMount = pendingMounts.isNotEmpty()
                        }
                    }
                    // Re-evaluation may have requested a mount during completion delivery,
                    // while single-flight was still held. Do not leave that receipt stranded.
                    if (retryMount) scheduleRefresh()
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
                for ((source, packages) in resolved) {
                    if (source !in RuntimeSafetySurfaces.CANDIDATE_SOURCES && packages != null) merged[source] = packages
                }
                bySource = merged
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
                resolvedAtMs = nowElapsedMs()
                resolution.value = resolution.value.copy(
                    resolvedSources = merged.keys.toSet(),
                    failedSources = resolution.value.requiredSources.filterTo(HashSet()) { resolved[it] == null },
                    checkedAtElapsedMs = resolvedAtMs,
                    checking = false,
                )
                completedMounts.clear()
                completedMounts.addAll(pendingMounts)
                pendingMounts.clear()
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
}
