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
}

/** UI state exposed by [StatsViewModel]. */
sealed interface StatsUiState {
    data object Loading : StatsUiState

    /** Usage Access is not granted — show the friendly prompt with the Settings hand-off. */
    data object PermissionRequired : StatsUiState

    data class Ready(val report: UsageReport) : StatsUiState
}
