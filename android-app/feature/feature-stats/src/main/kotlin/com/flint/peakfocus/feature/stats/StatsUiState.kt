package com.flint.peakfocus.feature.stats

import com.flint.peakfocus.core.model.UsageStat

/** One day in the 7-day comparison. Days are ordered oldest → today; the last one is today. */
data class DaySummary(
    /** Localized short weekday label, e.g. "Mon". */
    val label: String,
    val totalMillis: Long,
    val isToday: Boolean,
)

/** Everything [StatsScreen] renders once Usage Access is granted. */
data class UsageReport(
    val days: List<DaySummary>,
    val todayTotalMillis: Long,
    val weekTotalMillis: Long,
    /** Today's heaviest apps, highest first (rendered as core-model [UsageStat] rows). */
    val topAppsToday: List<UsageStat>,
) {
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

/** UI state exposed by [StatsViewModel]. */
sealed interface StatsUiState {
    data object Loading : StatsUiState

    /** Usage Access is not granted — show the friendly prompt with the Settings hand-off. */
    data object PermissionRequired : StatsUiState

    data class Ready(val report: UsageReport) : StatsUiState
}
