package com.flint.peakfocus.core.history

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
    fun note(summary: FocusSummary?): String? = when {
        summary?.availability == HistoryAvailability.DEGRADED -> "Some history couldn't be read"
        summary?.days?.lastOrNull()?.inProgress == true -> "In progress"
        else -> null
    }
}
