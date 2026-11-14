package com.flint.peakfocus.feature.stats

import com.flint.peakfocus.core.common.ui.StreakEmberMood
import com.flint.peakfocus.core.history.FocusDay
import com.flint.peakfocus.core.history.FocusSummary
import com.flint.peakfocus.core.history.HistoryAvailability
import com.flint.peakfocus.core.history.SessionOutcome
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

internal enum class FocusStreakDayKind { UNAVAILABLE, COMPLETED, INCOMPLETE }

internal data class FocusStreakDayUi(
    val date: LocalDate,
    val weekdayLabel: String,
    val contentDescription: String,
    val kind: FocusStreakDayKind,
    val isToday: Boolean,
)

internal sealed class FocusStreakUi {
    data object Hidden : FocusStreakUi()
    data object Empty : FocusStreakUi()
    data class Visible(
        val streak: Int,
        val streakCaption: String,
        val bestRecorded: Int?,
        val bestCaption: String?,
        val weekCompleted: Int,
        val weekCaption: String,
        val days: List<FocusStreakDayUi>,
        val footnote: String,
        val isActive: Boolean,
        /** Neutral status under the caption: completed today, or not yet today while still active. */
        val statusLine: String?,
        val completedToday: Boolean,
        /**
         * Cinematic ember mood derived from existing lifecycle only:
         * Active = streak alive & today completed; Warning = streak alive & today not yet;
         * Dormant = no active streak.
         */
        val emberMood: StreakEmberMood,
    ) : FocusStreakUi()
}

internal object FocusStreakCopy {
    const val TITLE = "Focus streak"
    /** Under-number label — streak sheet format (Opal-like composition, Flint copy). */
    const val DAY_STREAK = "Day streak"
    /** Sheet subtitle under the day-streak label. */
    const val SHEET_SUBTITLE = "Days in a row you focused with Flint"
    const val FOOTNOTE = "One completed focus session makes a day count. Days use this device's current time zone."
    const val EMPTY = "Your first focus session starts your streak."
    const val NO_ACTIVE = "No active streak"
    const val RESTART = "A new streak starts with a completed focus session."
    const val COMPLETED_TODAY = "Completed today"
    const val NOT_COMPLETED_TODAY = "Not completed today"
    const val BEST_LABEL = "Best recorded streak"
    const val WEEK_STRIP = "Focus days this week"

    fun daysInARow(streak: Int): String =
        if (streak == 1) "1 day in a row" else "$streak days in a row"

    fun weekCompleted(count: Int): String =
        if (count == 1) "1 completed focus day this week"
        else "$count completed focus days this week"

    fun bestRecorded(count: Int): String =
        if (count == 1) "Best recorded streak · 1 day"
        else "Best recorded streak · $count days"
}

/**
 * Pure Insights mapping for the Focus Streak card.
 * Uses [FocusSummary.streak], [FocusSummary.bestRecordedStreak] and [FocusDay.completed].
 */
internal fun focusStreakUi(
    summary: FocusSummary?,
    locale: Locale = Locale.getDefault(),
): FocusStreakUi {
    if (summary == null || summary.availability == HistoryAvailability.UNAVAILABLE) {
        return FocusStreakUi.Hidden
    }
    if (summary.availability == HistoryAvailability.EMPTY) return FocusStreakUi.Empty
    val anyCompleted = summary.sessions.any { it.outcome == SessionOutcome.COMPLETED } ||
        summary.days.any { it.completed > 0 }
    if (!anyCompleted) return FocusStreakUi.Empty

    val narrow = DateTimeFormatter.ofPattern("EEEEE", locale)
    val dateLabel = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    val today = summary.days.lastOrNull()?.date
    val days = summary.days.map { day ->
        val kind = when {
            day.focusedMs == null -> FocusStreakDayKind.UNAVAILABLE
            day.completed > 0 -> FocusStreakDayKind.COMPLETED
            else -> FocusStreakDayKind.INCOMPLETE
        }
        val isToday = day.date == today
        val status = when (kind) {
            FocusStreakDayKind.UNAVAILABLE -> "no data"
            FocusStreakDayKind.COMPLETED -> "completed focus day"
            FocusStreakDayKind.INCOMPLETE -> "no completed focus session"
        }
        val todaySuffix = if (isToday) ", today" else ""
        FocusStreakDayUi(
            date = day.date,
            weekdayLabel = day.date.format(narrow),
            contentDescription = "${day.date.format(dateLabel)}, $status$todaySuffix",
            kind = kind,
            isToday = isToday,
        )
    }
    val weekCompleted = days.count { it.kind == FocusStreakDayKind.COMPLETED }
    val best = summary.bestRecordedStreak.takeIf { it > 0 }
    val active = summary.streak > 0
    val completedToday = days.lastOrNull()?.kind == FocusStreakDayKind.COMPLETED
    val statusLine = when {
        !active -> null
        completedToday -> FocusStreakCopy.COMPLETED_TODAY
        else -> FocusStreakCopy.NOT_COMPLETED_TODAY
    }
    val emberMood = when {
        !active -> StreakEmberMood.Dormant
        completedToday -> StreakEmberMood.Active
        else -> StreakEmberMood.Warning
    }
    return FocusStreakUi.Visible(
        streak = summary.streak,
        streakCaption = if (active) FocusStreakCopy.daysInARow(summary.streak) else FocusStreakCopy.NO_ACTIVE,
        bestRecorded = best,
        bestCaption = best?.let(FocusStreakCopy::bestRecorded),
        weekCompleted = weekCompleted,
        weekCaption = FocusStreakCopy.weekCompleted(weekCompleted),
        days = days,
        footnote = if (active) FocusStreakCopy.FOOTNOTE else FocusStreakCopy.RESTART,
        isActive = active,
        statusLine = statusLine,
        completedToday = completedToday,
        emberMood = emberMood,
    )
}
