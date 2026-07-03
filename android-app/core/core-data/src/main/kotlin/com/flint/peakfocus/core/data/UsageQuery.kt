package com.flint.peakfocus.core.data

import android.app.usage.UsageStatsManager
import android.content.Context
import java.util.Calendar

/** Today's per-app foreground time, from UsageStatsManager. Requires the Usage Access grant. */
object UsageQuery {
    fun dailyForegroundMillis(
        context: Context,
        packageName: String,
        now: Long = System.currentTimeMillis(),
    ): Long {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        return usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startOfDay(now), now)
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
