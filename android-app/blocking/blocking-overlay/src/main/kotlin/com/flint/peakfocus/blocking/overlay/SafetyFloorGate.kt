package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.core.model.SafetyFloor

/**
 * Cached safety-floor answers for the detectors' hot paths (audit R2). The static floor is a set
 * lookup; the runtime half (default/system dialer, home apps) needs PackageManager/Telecom
 * binder calls, so it is resolved at most every [refreshIntervalMs] — except that a decision
 * that is about to *block* asks [protectsFresh], which re-resolves when the cache is older than
 * [freshIntervalMs]. A dialer or launcher the user just made default is therefore never shielded
 * on stale data, and the binder cost is paid only on the rare block path.
 *
 * A resolver failure keeps the last known runtime set (never shrinks the floor to nothing).
 * Plain JVM, clock injected, unit-tested in `SafetyFloorGateTest`.
 */
class SafetyFloorGate(
    private val selfPackage: String,
    private val resolveRuntime: () -> Set<String>,
    private val nowElapsedMs: () -> Long,
    private val refreshIntervalMs: Long = DEFAULT_REFRESH_INTERVAL_MS,
    private val freshIntervalMs: Long = DEFAULT_FRESH_INTERVAL_MS,
) {
    private val lock = Any()
    @Volatile private var runtime: Set<String> = emptySet()
    @Volatile private var resolvedAtMs: Long? = null

    /** Floor check against the cached runtime set (refreshed when older than the interval). */
    fun protects(packageName: String): Boolean {
        if (SafetyFloor.isProtected(packageName, selfPackage)) return true
        return packageName in runtimeOlderThan(refreshIntervalMs)
    }

    /** Floor check for a decision that is about to block: re-resolves stale runtime data. */
    fun protectsFresh(packageName: String): Boolean {
        if (SafetyFloor.isProtected(packageName, selfPackage)) return true
        return packageName in runtimeOlderThan(freshIntervalMs)
    }

    /** The current runtime set (for pickers/diagnostics), refreshed if older than the interval. */
    fun runtimeSurfaces(): Set<String> = runtimeOlderThan(refreshIntervalMs)

    private fun runtimeOlderThan(maxAgeMs: Long): Set<String> {
        val now = nowElapsedMs()
        val at = resolvedAtMs
        if (at != null && now >= at && now - at < maxAgeMs) return runtime
        synchronized(lock) {
            val again = resolvedAtMs
            if (again != null && now >= again && now - again < maxAgeMs) return runtime
            runCatching(resolveRuntime).onSuccess { runtime = it }
            // Stamp even on failure so a broken resolver is retried at the interval, not on
            // every event; the previous (possibly empty) set stays in force meanwhile.
            resolvedAtMs = now
            return runtime
        }
    }

    companion object {
        const val DEFAULT_REFRESH_INTERVAL_MS: Long = 30_000L
        const val DEFAULT_FRESH_INTERVAL_MS: Long = 2_000L
    }
}
