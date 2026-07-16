package com.flint.peakfocus.blocking.usagestats

/**
 * Cached daily-budget decision for Path B's one-second poll loop.
 *
 * UsageStats queries are too expensive to repeat every tick for minute-scale budgets. A verdict
 * is refreshed on package changes, after [requeryIntervalMs], or when the wall clock moves
 * backwards. Path A remains event-driven; Path B may therefore block at most one cache interval
 * late.
 */
class PathBTimeLimitGate(
    private val limitMinutesFor: (packageName: String) -> Int?,
    private val foregroundMillisToday: (packageName: String, nowEpochMs: Long) -> Long,
    private val requeryIntervalMs: Long = DEFAULT_REQUERY_INTERVAL_MS,
) {
    private var cachedPackage: String? = null
    private var cachedAtEpochMs = Long.MIN_VALUE
    private var cachedSpent = false

    fun isSpent(packageName: String, nowEpochMs: Long): Boolean {
        val stale = packageName != cachedPackage ||
            nowEpochMs - cachedAtEpochMs >= requeryIntervalMs ||
            nowEpochMs < cachedAtEpochMs
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
