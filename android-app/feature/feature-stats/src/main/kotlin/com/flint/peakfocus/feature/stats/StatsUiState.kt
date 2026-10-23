package com.flint.peakfocus.feature.stats

import com.flint.peakfocus.core.model.UsageStat

/** One day in the 7-day comparison. Days are ordered oldest → today; the last one is today. */
data class DaySummary(
    /** Localized short weekday label, e.g. "Mon". */
    val label: String,
    val totalMillis: Long,
    val isToday: Boolean,
    /** Local midnight identifies the date even when new usage arrives or the week rolls over. */
    val startMillis: Long = 0L,
    /** Full localized date for the selected-day readout and accessibility. */
    val dateLabel: String = label,
    val topApps: List<UsageStat> = emptyList(),
)

/** Everything [StatsScreen] renders once Usage Access is granted. */
data class UsageReport(
    val days: List<DaySummary>,
    val todayTotalMillis: Long,
    val weekTotalMillis: Long,
    /** Today's heaviest apps, highest first (rendered as core-model [UsageStat] rows). */
    val topAppsToday: List<UsageStat>,
    val topAppsWeek: List<UsageStat> = emptyList(),
) {
    /** A date falling out of the available window returns to Today, never to a different date. */
    fun resolvePeriod(period: UsagePeriod): UsagePeriod = when (period) {
        is UsagePeriod.Day -> if (days.any { it.startMillis == period.startMillis }) period else UsagePeriod.Today
        else -> period
    }

    fun appInsights(requestedPeriod: UsagePeriod): AppInsights {
        val period = resolvePeriod(requestedPeriod)
        val day = when (period) {
            UsagePeriod.Today -> days.lastOrNull { it.isToday }
            is UsagePeriod.Day -> days.firstOrNull { it.startMillis == period.startMillis }
            UsagePeriod.Week -> null
        }
        return when (period) {
            UsagePeriod.Week -> AppInsights("Last 7 days", weekTotalMillis, topAppsWeek, "this period")
            UsagePeriod.Today -> AppInsights("Today", todayTotalMillis, topAppsToday, "today")
            is UsagePeriod.Day -> AppInsights(day!!.dateLabel, day.totalMillis, day.topApps, "this day")
        }
    }

    val dailyAverageMillis: Long
        get() = if (days.isEmpty()) 0L else weekTotalMillis / days.size

    /** Days already complete — everything but the partial, still-running today. */
    val completedDays: List<DaySummary>
        get() = days.filter { !it.isToday }

    /**
     * Average screen time on a *completed* day — the honest "typical day" baseline. Excludes the
     * partial today, which would otherwise drag the figure down (unlike [dailyAverageMillis],
     * which divides the whole week — today included — by the day count). Zero-usage days still
     * count, so a quiet day pulls the typical down. Zero when no full day has elapsed yet.
     */
    val typicalDayMillis: Long
        get() {
            val completed = completedDays
            return if (completed.isEmpty()) 0L else completed.sumOf { it.totalMillis } / completed.size
        }

    /**
     * The heaviest day in the window, or `null` when nothing was recorded. Ties resolve to the
     * earlier day (days are oldest → today), keeping the pick deterministic.
     */
    val busiestDay: DaySummary?
        get() = days.filter { it.totalMillis > 0L }.maxByOrNull { it.totalMillis }

    /**
     * How much of a typical day today already accounts for — e.g. `0.44` renders as "44% of a
     * typical day so far". `null` when there's no completed-day baseline yet, so the UI stays
     * honest rather than comparing today against nothing.
     */
    val todayShareOfTypical: Float?
        get() = typicalDayMillis.takeIf { it > 0L }?.let { todayTotalMillis.toFloat() / it }
}

/** Today follows the clock; a selected day remains anchored to its actual date on refresh. */
sealed interface UsagePeriod {
    data object Today : UsagePeriod
    data object Week : UsagePeriod
    data class Day(val startMillis: Long) : UsagePeriod
}

data class AppInsights(
    val label: String,
    val totalMillis: Long,
    val topApps: List<UsageStat>,
    val shareLabel: String,
)

/**
 * Fraction of today's total that one app accounts for — e.g. `0.31` renders as "31% of today".
 * `null` when today has no recorded time yet, keeping the caption honest (hidden, never faked)
 * like [UsageReport.todayShareOfTypical]. Clamped to 0..1: event bucketing can let a single
 * package nose past the day total by a rounding sliver, and >100% would read as a lie.
 */
fun shareOfToday(packageMillis: Long, todayTotalMillis: Long): Float? {
    return shareOfPeriod(packageMillis, todayTotalMillis)
}

/** Uses the complete period total, including apps outside the visible top-app ranking. */
fun shareOfPeriod(packageMillis: Long, periodTotalMillis: Long): Float? =
    periodTotalMillis.takeIf { it > 0L }?.let {
        (packageMillis.toFloat() / it).coerceIn(0f, 1f)
    }

/** UI state exposed by [StatsViewModel]. */
sealed interface StatsUiState {
    data object Loading : StatsUiState

    /** Usage Access is not granted — show the friendly prompt with the Settings hand-off. */
    data object PermissionRequired : StatsUiState

    data class Ready(val report: UsageReport) : StatsUiState
}
