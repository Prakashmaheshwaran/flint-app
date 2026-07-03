package com.flint.peakfocus.feature.stats

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import com.flint.peakfocus.core.model.UsageStat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val DAY_COUNT = 7
private const val TOP_APP_COUNT = 8

/**
 * Reads the last [DAY_COUNT] local days of foreground usage from
 * [UsageStatsManager.queryEvents] and folds them through the pure [UsageAggregator].
 *
 * Why events rather than `queryUsageStats(INTERVAL_DAILY, …)`: daily `UsageStats` buckets can
 * straddle local midnight (their boundaries are system-chosen), which skews a day-by-day
 * comparison. Event-level bucketing splits sessions at true local midnights. The single-package
 * "today" helpers (`core-data.UsageQuery`, `blocking-usagestats.DailyLimitTracker`) remain the
 * canonical source for *limit enforcement*; this class only feeds the report UI.
 *
 * Honest caveats:
 * - Requires the Usage Access grant ([com.flint.peakfocus.permissions.UsageAccess]); without it
 *   `queryEvents` silently returns nothing. The ViewModel checks the grant first.
 * - Some OEMs retain usage events for only a few days; the oldest bars can then read low/zero
 *   even though the phone was used. (Stock Android retains events well past 7 days.)
 * - Flint's own package is excluded so the block screen/overlay doesn't count against the user.
 */
class UsageStatsSource(private val context: Context) {

    /** Blocking; call from a background dispatcher. */
    fun loadReport(nowMillis: Long = System.currentTimeMillis()): UsageReport {
        val windows = dayWindows(nowMillis)
        val events = readEvents(windows.first().startMillis, nowMillis)
        val buckets = UsageAggregator.bucketIntoDays(events, windows, nowMillis)

        val todayIndex = windows.lastIndex
        val dayFormat = SimpleDateFormat("EEE", Locale.getDefault())
        val days = windows.mapIndexed { i, window ->
            DaySummary(
                label = dayFormat.format(Date(window.startMillis)),
                totalMillis = buckets.dayTotalMillis(i),
                isToday = i == todayIndex,
            )
        }
        val topAppsToday = UsageAggregator.topApps(buckets.appTotalsForDay(todayIndex), TOP_APP_COUNT)
            .map { (packageName, millis) ->
                UsageStat(
                    packageName = packageName,
                    label = appLabel(packageName),
                    foregroundMillis = millis,
                )
            }
        return UsageReport(
            days = days,
            todayTotalMillis = buckets.dayTotalMillis(todayIndex),
            weekTotalMillis = buckets.rangeTotalMillis(),
            topAppsToday = topAppsToday,
        )
    }

    private fun readEvents(startMillis: Long, endMillis: Long): List<ForegroundEvent> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val usageEvents = usm.queryEvents(startMillis, endMillis)
        val event = UsageEvents.Event()
        val result = ArrayList<ForegroundEvent>()
        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            val packageName = event.packageName ?: continue
            if (packageName == context.packageName) continue // don't count Flint's own UI/overlay
            // ACTIVITY_RESUMED/ACTIVITY_PAUSED (API 29 names) are numeric aliases of the legacy
            // MOVE_TO_FOREGROUND/MOVE_TO_BACKGROUND (values 1/2, since API 21) — compile-time
            // inlined constants, safe down to minSdk 23. Same pattern as blocking-usagestats'
            // UsagePoller.
            val transition = when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> ForegroundTransition.RESUMED
                UsageEvents.Event.ACTIVITY_PAUSED -> ForegroundTransition.PAUSED
                else -> continue
            }
            result.add(ForegroundEvent(packageName, event.timeStamp, transition))
        }
        return result
    }

    /**
     * The last [DAY_COUNT] local-day windows, oldest first, ending with today. Boundaries are
     * true local midnights computed with [Calendar] (same convention as core-data's UsageQuery);
     * walking day-by-day keeps them correct across DST shifts.
     */
    private fun dayWindows(nowMillis: Long): List<DayWindow> {
        val todayStart = startOfDay(nowMillis)
        val starts = LongArray(DAY_COUNT)
        val calendar = Calendar.getInstance().apply { timeInMillis = todayStart }
        for (i in DAY_COUNT - 1 downTo 0) {
            starts[i] = calendar.timeInMillis
            calendar.add(Calendar.DAY_OF_YEAR, -1)
        }
        val todayEnd = Calendar.getInstance().apply {
            timeInMillis = todayStart
            add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        return List(DAY_COUNT) { i ->
            DayWindow(
                startMillis = starts[i],
                endMillis = if (i < DAY_COUNT - 1) starts[i + 1] else todayEnd,
            )
        }
    }

    private fun startOfDay(nowMillis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = nowMillis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Human app label, or null if the app was uninstalled since (UI falls back to the package). */
    private fun appLabel(packageName: String): String? = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }
}
