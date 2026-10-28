package com.flint.peakfocus.core.data

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import com.flint.peakfocus.core.model.DailyUsageTimeline
import com.flint.peakfocus.core.model.ConservativeDailyUsage
import com.flint.peakfocus.core.model.UsageAccessProbe
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
    @Volatile private var resetAccessRequested = false
    private var cachedOps: AppOpsManager? = null
    private var accessProbe: UsageAccessProbe? = null

    /** Path A cannot infer Usage Access from Accessibility. Null means the budget is unmeasured. */
    @Synchronized
    fun dailyForegroundMillisIfGranted(context: Context, packageName: String, now: Long): Long? {
        val app = context.applicationContext
        val ops = app.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        if (cachedOps !== ops) {
            cachedOps = ops
            accessProbe = UsageAccessProbe {
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName)
                } else {
                    @Suppress("DEPRECATION")
                    ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName)
                }
                mode == AppOpsManager.MODE_ALLOWED
            }
        }
        if (resetAccessRequested) { accessProbe!!.invalidate(); resetAccessRequested = false }
        return accessProbe!!.measure(now) { dailyForegroundMillis(app, packageName, now) }
    }

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
        val eventUsage = runCatching { timeline!!.millis(packageName, start, now) }
            .onFailure { Log.w("FlintUsage", "Event usage unavailable; retaining aggregate/high-water usage", it) }
            .getOrNull()
        val aggregateUsage = runCatching {
            val buckets = checkNotNull(usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, now))
            buckets.filter { it.packageName == packageName }.sumOf { it.totalTimeInForeground.coerceAtLeast(0L) }
        }.onFailure { Log.w("FlintUsage", "Aggregate usage unavailable; retaining event/high-water usage", it) }.getOrNull()
        return conservative.observe(packageName, now, zone, eventUsage, aggregateUsage)
    }

    /** Rebuild event observations, but never erase the conservative quota floor on clock/zone changes. */
    fun invalidate() { resetRequested = true; resetAccessRequested = true }

}
