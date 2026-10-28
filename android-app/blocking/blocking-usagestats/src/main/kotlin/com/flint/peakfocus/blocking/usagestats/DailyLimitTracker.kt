package com.flint.peakfocus.blocking.usagestats

import android.app.usage.UsageStatsManager
import android.content.Context
import com.flint.peakfocus.core.data.UsageQuery

/**
 * Shared event-derived Time Limit accounting. Counts the still-open foreground session;
 * aggregate UsageStats buckets need not have flushed that session yet.
 */
class DailyLimitTracker(private val usm: UsageStatsManager) {

    constructor(context: Context) : this(
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager,
    )

    /** Total foreground time today (ms) for [packageName]. */
    fun foregroundMillisToday(packageName: String, now: Long): Long =
        UsageQuery.dailyForegroundMillis(usm, packageName, now)
}
