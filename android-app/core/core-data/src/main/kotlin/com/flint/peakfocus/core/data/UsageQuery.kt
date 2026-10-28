package com.flint.peakfocus.core.data

import android.app.usage.UsageStatsManager
import android.content.Context
import com.flint.peakfocus.core.model.DailyUsageTimeline
import java.util.Calendar

/** Today's event-derived foreground time, including the ongoing session. Requires Usage Access. */
object UsageQuery {
    private var cachedManager: UsageStatsManager? = null
    private var timeline: DailyUsageTimeline? = null
    fun dailyForegroundMillis(
        context: Context,
        packageName: String,
        now: Long = System.currentTimeMillis(),
    ): Long {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        return dailyForegroundMillis(usm, packageName, now)
    }

    @Synchronized
    fun dailyForegroundMillis(usm: UsageStatsManager, packageName: String, now: Long): Long {
        if (cachedManager !== usm) {
            cachedManager = usm
            timeline = DailyUsageTimeline { start, end -> UsageEventReader.read(usm, start, end) }
        }
        return timeline!!.millis(packageName, startOfDay(now), now)
    }

    /** Called on explicit clock/permission re-evaluation, including timezone changes. */
    @Synchronized
    fun invalidate() { timeline = null; cachedManager = null }

    private fun startOfDay(now: Long): Long = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
