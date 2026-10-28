package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.core.model.SafetyFloor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cached safety-floor answers for the detectors' hot paths (audit R2). The static floor is a set
 * lookup; the runtime half (default home app, default/system dialer) needs PackageManager/Telecom
 * binder calls, which must stay off the accessibility event (main) thread:
 *
 *  - [protects] is a pure read of the cached set. When the cache is older than
 *    [refreshIntervalMs] it hands a refresh to [refreshInBackground] (one in flight at a time)
 *    and answers from the last known set meanwhile. Only the very first call resolves inline,
 *    so a cold start has no hole.
 *  - [protectsFresh] is for a decision that is about to *block* (rare): it resolves inline when
 *    the cache is older than [maxAgeMs], so a dialer or launcher the user just made default is
 *    not shielded from stale data. Pass 0 to force a resolve (a new shield mount).
 *
 * The resolver reports each source separately (null = that lookup failed). A failed source keeps
 * its last known packages, so a transient binder failure can never shrink the floor — e.g. drop a
 * custom default dialer while the home lookup still succeeds. Plain JVM, clock and executor
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
) {
    private val lock = Any()
    private val refreshing = AtomicBoolean(false)
    @Volatile private var bySource: Map<String, Set<String>> = emptyMap()
    @Volatile private var runtime: Set<String> = emptySet()
    @Volatile private var resolvedAtMs: Long? = null

    /** Floor check against the cached runtime set; schedules a background refresh when stale. */
    fun protects(packageName: String): Boolean {
        if (SafetyFloor.isProtected(packageName, selfPackage)) return true
        if (resolvedAtMs == null) {
            refreshIfOlderThan(0L) // cold start: one inline resolve, never an empty first answer
        } else if (isOlderThan(refreshIntervalMs)) {
            scheduleRefresh()
        }
        return packageName in runtime
    }

    /** Floor check for a decision about to block: resolves inline when older than [maxAgeMs]. */
    fun protectsFresh(packageName: String, maxAgeMs: Long = freshIntervalMs): Boolean {
        if (SafetyFloor.isProtected(packageName, selfPackage)) return true
        refreshIfOlderThan(maxAgeMs)
        return packageName in runtime
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
        if (!refreshing.compareAndSet(false, true)) return
        val submitted = runCatching {
            executor {
                try {
                    refreshIfOlderThan(0L)
                } finally {
                    refreshing.set(false)
                }
            }
        }
        if (submitted.isFailure) refreshing.set(false)
    }

    private fun refreshIfOlderThan(maxAgeMs: Long) {
        if (maxAgeMs > 0L && !isOlderThan(maxAgeMs)) return
        synchronized(lock) {
            if (maxAgeMs > 0L && !isOlderThan(maxAgeMs)) return
            val resolved = runCatching(resolveRuntime).getOrNull().orEmpty()
            // Per source: a successful lookup replaces that source; a failed (null) or missing
            // one keeps its last known packages.
            val merged = HashMap(bySource)
            for ((source, packages) in resolved) {
                if (packages != null) merged[source] = packages
            }
            bySource = merged
            runtime = merged.values.flatMapTo(HashSet()) { it }
            // Stamp even on failure so a broken resolver is retried at the interval, not on
            // every event; the previous sets stay in force meanwhile.
            resolvedAtMs = nowElapsedMs()
        }
    }

    companion object {
        const val DEFAULT_REFRESH_INTERVAL_MS: Long = 30_000L
        /** Also the coordinator's visible-shield refresh age, so re-renders never resolve inline more often. */
        const val DEFAULT_FRESH_INTERVAL_MS: Long = 5_000L
    }
}
