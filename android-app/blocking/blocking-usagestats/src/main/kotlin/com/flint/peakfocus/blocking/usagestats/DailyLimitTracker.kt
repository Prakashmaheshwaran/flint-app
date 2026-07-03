package com.flint.peakfocus.blocking.usagestats

import android.app.usage.UsageStatsManager
import android.content.Context
import java.util.Calendar

/**
 * Aggregate daily foreground time per package — the canonical source for Time Limits and the
 * stats screen. Buckets are slightly coarse/delayed: fine for daily limits, not for sub-second
 * enforcement (that is the detector's job).
 */
class DailyLimitTracker(private val usm: UsageStatsManager) {

    constructor(context: Context) : this(
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager,
    )

    /** Total foreground time today (ms) for [packageName]. */
    fun foregroundMillisToday(packageName: String, now: Long): Long {
        val start = startOfDay(now)
        return usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, now)
            .filter { it.packageName == packageName }
            .sumOf { it.totalTimeInForeground }
    }

    private fun startOfDay(now: Long): Long = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
