package com.flint.peakfocus.blocking.usagestats

import java.time.Instant
import java.time.ZoneId

/**
 * Cached daily-budget decision for Path B's one-second poll loop.
 *
 * UsageStats queries are too expensive to repeat every tick for minute-scale budgets. A verdict
 * is refreshed on package changes, after [requeryIntervalMs], or when the wall clock moves
 * backwards. A cached Allow never lives longer than the remaining foreground budget.
 */
class PathBTimeLimitGate(
    private val limitMinutesFor: (packageName: String) -> Int?,
    private val foregroundMillisToday: (packageName: String, nowEpochMs: Long) -> Long,
    private val requeryIntervalMs: Long = DEFAULT_REQUERY_INTERVAL_MS,
    private val dayFor: (Long) -> Long = { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay() },
) {
    private var cachedPackage: String? = null
    private var cachedAtEpochMs = Long.MIN_VALUE
    private var cachedSpent = false
    private var cachedLimitMinutes: Int? = null
    private var validForMs = 0L
    private var cachedDay: Long? = null
    private var lastObservedMs: Long? = null

    init { require(requeryIntervalMs > 0) }

    fun isSpent(packageName: String, nowEpochMs: Long): Boolean {
        val limitMinutes = limitMinutesFor(packageName)
        val day = dayFor(nowEpochMs)
        val stale = packageName != cachedPackage || limitMinutes != cachedLimitMinutes ||
            day != cachedDay || lastObservedMs?.let { nowEpochMs < it } == true ||
            nowEpochMs - cachedAtEpochMs >= validForMs ||
            nowEpochMs < cachedAtEpochMs
        if (stale) {
            val remaining = limitMinutes?.let {
                it * 60_000L - foregroundMillisToday(packageName, nowEpochMs)
            }
            cachedSpent = remaining != null && remaining <= 0L
            validForMs = if (remaining != null && remaining > 0L) {
                minOf(requeryIntervalMs, remaining)
            } else requeryIntervalMs
            cachedLimitMinutes = limitMinutes
            cachedDay = day
            cachedPackage = packageName
            cachedAtEpochMs = nowEpochMs
        }
        lastObservedMs = nowEpochMs
        return cachedSpent
    }

    fun invalidate() { cachedPackage = null }

    companion object {
        const val DEFAULT_REQUERY_INTERVAL_MS = 15_000L
    }
}
