package com.flint.peakfocus.feature.stats

import com.flint.peakfocus.core.model.UsageStat
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val TOP_APP_COUNT = 8

/** Maps the same event buckets into daily and whole-period rankings, without losing small apps. */
internal fun buildUsageReport(
    buckets: DayBuckets,
    appLabel: (String) -> String?,
    locale: Locale = Locale.getDefault(),
    timeZone: TimeZone = TimeZone.getDefault(),
): UsageReport {
    val weekdayFormat = SimpleDateFormat("EEE", locale).apply { this.timeZone = timeZone }
    val dateFormat = DateFormat.getDateInstance(DateFormat.FULL, locale).apply { this.timeZone = timeZone }
    val labels = mutableMapOf<String, String?>()
    fun topApps(totals: Map<String, Long>): List<UsageStat> =
        UsageAggregator.topApps(totals, TOP_APP_COUNT).map { (packageName, millis) ->
            if (!labels.containsKey(packageName)) labels[packageName] = appLabel(packageName)
            UsageStat(packageName, labels[packageName], millis)
        }

    val days = buckets.windows.mapIndexed { index, window ->
        val date = Date(window.startMillis)
        DaySummary(
            label = weekdayFormat.format(date),
            totalMillis = buckets.dayTotalMillis(index),
            isToday = index == buckets.windows.lastIndex,
            startMillis = window.startMillis,
            dateLabel = dateFormat.format(date),
            topApps = topApps(buckets.appTotalsForDay(index)),
        )
    }
    return UsageReport(
        days = days,
        todayTotalMillis = days.lastOrNull()?.totalMillis ?: 0L,
        weekTotalMillis = buckets.rangeTotalMillis(),
        topAppsToday = days.lastOrNull()?.topApps.orEmpty(),
        topAppsWeek = topApps(buckets.appTotalsForRange()),
    )
}
