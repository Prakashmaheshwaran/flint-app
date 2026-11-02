package com.flint.peakfocus.core.history

import android.content.res.Resources
import java.time.format.DateTimeFormatter

object FocusHistoryText {
    const val DEFINITION = "Timer focus sessions are counted; schedules and limits aren't counted."
    fun duration(milliseconds: Long): String {
        val minutes = milliseconds.coerceAtLeast(0) / 60_000
        return when {
            milliseconds in 1..59_999 -> "<1m"
            minutes < 60 -> "${minutes}m"
            minutes % 60 == 0L -> "${minutes / 60}h"
            else -> "${minutes / 60}h ${minutes % 60}m"
        }
    }
    fun today(summary: FocusSummary?, resources: Resources? = null): String = when (summary?.availability) {
        null -> "Loading focus history…"
        HistoryAvailability.UNAVAILABLE -> "Focus history unavailable"
        HistoryAvailability.EMPTY -> "No focus sessions yet"
        else -> if (summary.lifecycleEventsMissing && summary.sessions.isEmpty())
            resources?.getString(R.string.focus_history_no_recorded_sessions) ?: "No recorded focus sessions"
            else summary.days.lastOrNull()?.focusedMs?.let(::duration) ?: "No data"
    }
    fun breaks(count: Int) = "$count ${if (count == 1) "break" else "breaks"}"
    fun opensStopped(count: Int) = "$count ${if (count == 1) "open" else "opens"} stopped"
    fun weekLabel(days: List<FocusDay>): String {
        val first = days.firstOrNull { it.focusedMs != null } ?: return "Last 7 days"
        return if (first.date > days.first().date) "Since ${first.date.format(DateTimeFormatter.ofPattern("EEE, MMM d"))}"
            else "Last 7 days"
    }
    fun note(summary: FocusSummary?, days: List<FocusDay> = summary?.days?.takeLast(1).orEmpty(),
             resources: Resources? = null): String? =
        listOfNotNull(
            "In progress".takeIf { days.any { it.inProgress } },
            days.sumOf { it.unconfirmed }.takeIf { it > 0 }?.let { count ->
                resources?.getQuantityString(R.plurals.focus_history_unconfirmed, count, count)
                    ?: "$count ${if (count == 1) "session" else "sessions"} couldn't be confirmed"
            },
            if (summary?.lifecycleEventsMissing == true)
                resources?.getString(R.string.focus_history_missing_events) ?: "Some focus events may be missing"
            else null,
            "Some history couldn't be read".takeIf {
                summary?.availability == HistoryAvailability.DEGRADED && !summary.lifecycleEventsMissing
            },
        ).takeIf { it.isNotEmpty() }?.joinToString(" · ")
}
