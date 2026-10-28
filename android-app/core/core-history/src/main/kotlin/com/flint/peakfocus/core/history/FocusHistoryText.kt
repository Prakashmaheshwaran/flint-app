package com.flint.peakfocus.core.history

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
    fun today(summary: FocusSummary?): String = when (summary?.availability) {
        null -> "Loading focus history…"
        HistoryAvailability.UNAVAILABLE -> "Focus history unavailable"
        HistoryAvailability.EMPTY -> "No focus sessions yet"
        else -> summary.days.lastOrNull()?.focusedMs?.let(::duration) ?: "No data"
    }
    fun breaks(count: Int) = "$count ${if (count == 1) "break" else "breaks"}"
    fun opensStopped(count: Int) = "$count ${if (count == 1) "open" else "opens"} stopped"
    fun weekLabel(days: List<FocusDay>): String {
        val first = days.firstOrNull { it.focusedMs != null } ?: return "Last 7 days"
        return if (first.date > days.first().date) "Since ${first.date.format(DateTimeFormatter.ofPattern("EEE, MMM d"))}"
            else "Last 7 days"
    }
    fun note(summary: FocusSummary?, days: List<FocusDay> = summary?.days?.takeLast(1).orEmpty()): String? =
        listOfNotNull(
            "In progress".takeIf { days.any { it.inProgress } },
            "Some history couldn't be read".takeIf { summary?.availability == HistoryAvailability.DEGRADED },
        ).takeIf { it.isNotEmpty() }?.joinToString(" · ")
}
