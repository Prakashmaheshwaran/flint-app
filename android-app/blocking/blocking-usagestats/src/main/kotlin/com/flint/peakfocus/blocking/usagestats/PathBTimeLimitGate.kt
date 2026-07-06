package com.flint.peakfocus.blocking.usagestats

/**
 * "Is this app's daily time budget spent right now?" for the Path B poll loop — the missing
 * caller that finally puts [DailyLimitTracker] into enforcement (Path A has enforced Time
 * Limits via the same stores since the MVP; Path B only polled rules).
 *
 * The poll loop ticks every second, but `UsageStatsManager.queryUsageStats` is too heavy to
 * hit at that rate for a budget that moves by minutes — so verdicts are cached per package
 * and re-queried at most every [requeryIntervalMs] (or immediately on a package change or a
 * clock that moved backwards). Worst case a spent budget blocks [requeryIntervalMs] late —
 * acceptable for the documented-degraded fallback path; Path A stays event-driven and exact.
 *
 * Dependencies are plain functions so the decision + caching logic is JVM-testable without
 * Android: wire `LimitStore::limitMinutes` and `DailyLimitTracker::foregroundMillisToday`.
 */
class PathBTimeLimitGate(
    private val limitMinutesFor: (packageName: String) -> Int?,
    private val foregroundMillisToday: (packageName: String, nowEpochMs: Long) -> Long,
    private val requeryIntervalMs: Long = DEFAULT_REQUERY_INTERVAL_MS,
) {

    private var cachedPackage: String? = null
    private var cachedAtEpochMs = Long.MIN_VALUE
    private var cachedSpent = false

    /** True when [packageName]'s daily budget exists and today's foreground time has reached it. */
    fun isSpent(packageName: String, nowEpochMs: Long): Boolean {
        val stale = packageName != cachedPackage ||
            nowEpochMs - cachedAtEpochMs >= requeryIntervalMs ||
            nowEpochMs < cachedAtEpochMs // clock went backwards: don't trust the cache window
        if (stale) {
            val limitMinutes = limitMinutesFor(packageName)
            cachedSpent = limitMinutes != null &&
                foregroundMillisToday(packageName, nowEpochMs) >= limitMinutes * 60_000L
            cachedPackage = packageName
            cachedAtEpochMs = nowEpochMs
        }
        return cachedSpent
    }

    companion object {
        const val DEFAULT_REQUERY_INTERVAL_MS = 15_000L
    }
}
