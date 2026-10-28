package com.flint.peakfocus.core.data

import android.app.usage.UsageStatsManager
import android.content.Context
import com.flint.peakfocus.core.model.DailyUsageTimeline
import com.flint.peakfocus.core.model.ConservativeDailyUsage
import java.time.ZoneId

/**
 * Conservative daily usage: max(open-session event reconstruction, aggregate daily buckets,
 * already observed usage). Call on IO: initial carry-in reads up to seven days of events.
 * Device-zone midnight is temporary until C4d persists quotaZone; zone changes do not lower
 * the process's already measured quota before the next midnight in the new zone.
 */
object UsageQuery {
    private var cachedManager: UsageStatsManager? = null
    private var timeline: DailyUsageTimeline? = null
    private val conservative = ConservativeDailyUsage()
    @Volatile private var resetRequested = false
    fun dailyForegroundMillis(
        context: Context,
        packageName: String,
        now: Long = System.currentTimeMillis(),
    ): Long {
        val usm = context.applicationContext.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        return dailyForegroundMillis(usm, packageName, now)
    }

    @Synchronized
    fun dailyForegroundMillis(usm: UsageStatsManager, packageName: String, now: Long): Long {
        if (resetRequested || cachedManager !== usm) {
            resetRequested = false
            cachedManager = usm
            timeline = DailyUsageTimeline { start, end -> UsageEventReader.read(usm, start, end) }
        }
        val zone = ZoneId.systemDefault()
        val start = DailyUsageTimeline.startOfDay(now, zone)
        val eventUsage = runCatching { timeline!!.millis(packageName, start, now) }.getOrNull()
        val aggregateUsage = runCatching {
            val buckets = checkNotNull(usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, now))
            buckets.filter { it.packageName == packageName }.sumOf { it.totalTimeInForeground.coerceAtLeast(0L) }
        }.getOrNull()
        return conservative.observe(packageName, now, zone, eventUsage, aggregateUsage)
    }

    /** Rebuild event observations, but never erase the conservative quota floor on clock/zone changes. */
    fun invalidate() { resetRequested = true }

}
