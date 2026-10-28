package com.flint.peakfocus.blocking.usagestats

import android.app.usage.UsageStatsManager
import android.content.Context
import com.flint.peakfocus.core.data.UsageQuery

/**
 * Shared conservative Time Limit accounting. Open-session events supplement aggregate daily
 * buckets, while aggregate/observed totals protect against missing or truncated event history.
 */
class DailyLimitTracker(private val usm: UsageStatsManager) {

    constructor(context: Context) : this(
        context.applicationContext.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager,
    )

    /** Total foreground time today (ms) for [packageName]. */
    fun foregroundMillisToday(packageName: String, now: Long): Long =
        UsageQuery.dailyForegroundMillis(usm, packageName, now)
}
