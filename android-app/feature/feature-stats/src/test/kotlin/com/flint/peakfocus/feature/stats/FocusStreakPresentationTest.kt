package com.flint.peakfocus.feature.stats

import com.flint.peakfocus.core.common.ui.StreakEmberMood
import com.flint.peakfocus.core.history.FocusDay
import com.flint.peakfocus.core.history.FocusSession
import com.flint.peakfocus.core.history.FocusSummary
import com.flint.peakfocus.core.history.HistoryAvailability
import com.flint.peakfocus.core.history.SessionOutcome
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusStreakPresentationTest {
    private val today = LocalDate.of(2026, 11, 7)
    private val locale = Locale.US

    @Test fun unavailableAndLoadingAreHidden() {
        assertEquals(FocusStreakUi.Hidden, focusStreakUi(null, locale))
        assertEquals(
            FocusStreakUi.Hidden,
            focusStreakUi(FocusSummary(HistoryAvailability.UNAVAILABLE, emptyList(), emptyList(), 0), locale),
        )
    }

    @Test fun emptyHistoryShowsEmptyState() {
        assertEquals(
            FocusStreakUi.Empty,
            focusStreakUi(FocusSummary(HistoryAvailability.EMPTY, emptyList(), week(null), 0), locale),
        )
    }

    @Test fun noCompletedSessionsShowsEmptyState() {
        val days = week(0).mapIndexed { index, day ->
            if (index == 6) day.copy(endedEarly = 1) else day
        }
        val summary = FocusSummary(
            HistoryAvailability.VALUE,
            listOf(session(SessionOutcome.ENDED_EARLY)),
            days,
            streak = 0,
        )
        assertEquals(FocusStreakUi.Empty, focusStreakUi(summary, locale))
    }

    @Test fun zeroStreakWithPastCompletionsIsInactiveVisible() {
        val days = week(0).toMutableList()
        days[2] = days[2].copy(completed = 1, focusedMs = 60_000)
        val ui = focusStreakUi(
            FocusSummary(
                HistoryAvailability.VALUE,
                listOf(session(SessionOutcome.COMPLETED)),
                days,
                streak = 0,
                bestRecordedStreak = 3,
            ),
            locale,
        ) as FocusStreakUi.Visible
        assertEquals(0, ui.streak)
        assertEquals(FocusStreakCopy.NO_ACTIVE, ui.streakCaption)
        assertFalse(ui.isActive)
        assertNull(ui.statusLine)
        assertFalse(ui.completedToday)
        assertEquals(StreakEmberMood.Dormant, ui.emberMood)
        assertEquals(3, ui.bestRecorded)
        assertEquals(FocusStreakCopy.bestRecorded(3), ui.bestCaption)
        assertEquals(FocusStreakCopy.RESTART, ui.footnote)
        assertEquals(1, ui.weekCompleted)
        assertEquals(FocusStreakDayKind.COMPLETED, ui.days[2].kind)
        assertTrue(ui.days[6].kind == FocusStreakDayKind.INCOMPLETE)
        assertTrue(ui.days[6].isToday)
    }

    @Test fun oneDayAndMultiDayCaptions() {
        val one = focusStreakUi(activeSummary(streak = 1, completedToday = true), locale) as FocusStreakUi.Visible
        assertEquals("1 day in a row", one.streakCaption)
        assertTrue(one.isActive)
        assertTrue(one.completedToday)
        assertEquals(FocusStreakCopy.COMPLETED_TODAY, one.statusLine)
        assertEquals(FocusStreakCopy.FOOTNOTE, one.footnote)
        assertEquals(StreakEmberMood.Active, one.emberMood)
        val many = focusStreakUi(activeSummary(streak = 4, completedToday = true), locale) as FocusStreakUi.Visible
        assertEquals("4 days in a row", many.streakCaption)
        assertEquals("4 completed focus days this week", many.weekCaption)
        assertEquals(FocusStreakCopy.bestRecorded(4), many.bestCaption)
    }

    @Test fun historyStartMarksEarlierDaysUnavailable() {
        val days = (0L..6L).map { offset ->
            val date = today.minusDays(6 - offset)
            if (offset < 3) FocusDay(date, null) else FocusDay(date, 0, completed = if (offset >= 5) 1 else 0)
        }
        val ui = focusStreakUi(
            FocusSummary(
                HistoryAvailability.VALUE,
                listOf(session(SessionOutcome.COMPLETED)),
                days,
                streak = 2,
                bestRecordedStreak = 2,
            ),
            locale,
        ) as FocusStreakUi.Visible
        assertEquals(FocusStreakDayKind.UNAVAILABLE, ui.days[0].kind)
        assertEquals(FocusStreakDayKind.UNAVAILABLE, ui.days[2].kind)
        assertEquals(FocusStreakDayKind.INCOMPLETE, ui.days[3].kind)
        assertEquals(FocusStreakDayKind.COMPLETED, ui.days[5].kind)
        assertTrue(ui.days[0].contentDescription.contains("no data"))
        assertTrue(ui.days[5].contentDescription.contains("completed focus day"))
    }

    @Test fun todayIncompleteYesterdayCompletedUsesAggregatorStreak() {
        val days = week(0).toMutableList()
        days[5] = days[5].copy(completed = 1, focusedMs = 120_000) // yesterday
        val ui = focusStreakUi(
            FocusSummary(
                HistoryAvailability.VALUE,
                listOf(session(SessionOutcome.COMPLETED)),
                days,
                streak = 1,
                bestRecordedStreak = 1,
            ),
            locale,
        ) as FocusStreakUi.Visible
        assertEquals(1, ui.streak)
        assertTrue(ui.isActive)
        assertFalse(ui.completedToday)
        assertEquals(FocusStreakCopy.NOT_COMPLETED_TODAY, ui.statusLine)
        assertEquals(StreakEmberMood.Warning, ui.emberMood)
        assertEquals(FocusStreakDayKind.COMPLETED, ui.days[5].kind)
        assertEquals(FocusStreakDayKind.INCOMPLETE, ui.days[6].kind)
        assertTrue(ui.days[6].isToday)
    }

    @Test fun emptyBestIsOmittedFromVisibleCard() {
        val days = week(0).toMutableList()
        days[6] = days[6].copy(completed = 1, focusedMs = 60_000)
        val ui = focusStreakUi(
            FocusSummary(
                HistoryAvailability.VALUE,
                listOf(session(SessionOutcome.COMPLETED)),
                days,
                streak = 1,
                bestRecordedStreak = 0,
            ),
            locale,
        ) as FocusStreakUi.Visible
        assertNull(ui.bestRecorded)
        assertNull(ui.bestCaption)
    }

    private fun week(focusedMs: Long?): List<FocusDay> =
        (0L..6L).map { FocusDay(today.minusDays(6 - it), focusedMs) }

    private fun activeSummary(streak: Int, completedToday: Boolean): FocusSummary {
        val days = week(0).toMutableList()
        val completedDays = if (completedToday) streak else streak
        for (i in 0 until completedDays) {
            val index = 6 - i - if (completedToday) 0 else 1
            if (index in days.indices) days[index] = days[index].copy(completed = 1, focusedMs = 60_000)
        }
        return FocusSummary(
            HistoryAvailability.VALUE,
            listOf(session(SessionOutcome.COMPLETED)),
            days,
            streak,
            bestRecordedStreak = streak,
        )
    }

    private fun session(outcome: SessionOutcome) = FocusSession(
        id = "s1",
        name = "Focus",
        start = 0,
        end = 60_000,
        plannedEnd = 60_000,
        outcome = outcome,
        inferred = false,
        intervals = emptyList(),
        breaks = null,
        opensIntercepted = null,
    )
}
