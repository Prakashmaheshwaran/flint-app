package com.flint.peakfocus.core.data

import android.app.usage.UsageStatsManager
import android.content.Context
import com.flint.peakfocus.core.model.ForegroundUsage
import java.util.Calendar

/** Today's event-derived foreground time, including the ongoing session. Requires Usage Access. */
object UsageQuery {
    fun dailyForegroundMillis(
        context: Context,
        packageName: String,
        now: Long = System.currentTimeMillis(),
    ): Long {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        return dailyForegroundMillis(usm, packageName, now)
    }

    fun dailyForegroundMillis(usm: UsageStatsManager, packageName: String, now: Long): Long {
        val start = startOfDay(now)
        // UsageEvents has bounded platform retention. A week of carry-in covers a session
        // crossing midnight without inventing usage when the platform has no resume record.
        val events = UsageEventReader.read(usm, start - 7 * 24 * 60 * 60_000L, now)
        return ForegroundUsage.millis(packageName, events, start, now)
    }

    private fun startOfDay(now: Long): Long = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
