package com.flint.peakfocus.core.history

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class FocusHistoryTextTest {
    private val today = LocalDate.of(2026, 10, 28)
    private fun summary(availability: HistoryAvailability, ms: Long? = 0, running: Boolean = false) =
        FocusSummary(availability, emptyList(), listOf(FocusDay(today, ms, inProgress = running)), 0)

    @Test fun missingLoadingEmptyAndMeasuredZeroHaveDifferentCopy() {
        assertEquals("Loading focus history…", FocusHistoryText.today(null))
        assertEquals("Focus history unavailable", FocusHistoryText.today(summary(HistoryAvailability.UNAVAILABLE)))
        assertEquals("No focus sessions yet", FocusHistoryText.today(summary(HistoryAvailability.EMPTY)))
        assertEquals("No data", FocusHistoryText.today(summary(HistoryAvailability.VALUE, null)))
        assertEquals("0m", FocusHistoryText.today(summary(HistoryAvailability.VALUE)))
    }
    @Test fun shortRealSessionsNeverRoundToFabricatedZero() {
        assertEquals("<1m", FocusHistoryText.duration(1))
        assertEquals("<1m", FocusHistoryText.duration(59_999))
        assertEquals("1m", FocusHistoryText.duration(60_000))
        assertEquals("1h", FocusHistoryText.duration(3_600_000))
        assertEquals("1h 1m", FocusHistoryText.duration(3_660_000))
    }
    @Test fun degradedAndInProgressAreExplicit() {
        assertEquals("In progress", FocusHistoryText.note(summary(HistoryAvailability.VALUE, 1000, true)))
        assertEquals("Some history couldn't be read", FocusHistoryText.note(summary(HistoryAvailability.DEGRADED, 1000)))
        assertEquals("In progress · Some history couldn't be read", FocusHistoryText.note(summary(HistoryAvailability.DEGRADED, 1000, true)))
        assertNull(FocusHistoryText.note(summary(HistoryAvailability.VALUE, 1000, true), listOf(FocusDay(today.minusDays(1), 0))))
    }
    @Test fun measuredOptionalCountsUseSingularAndPlural() {
        assertEquals("1 break", FocusHistoryText.breaks(1)); assertEquals("2 breaks", FocusHistoryText.breaks(2))
        assertEquals("1 open stopped", FocusHistoryText.opensStopped(1)); assertEquals("0 opens stopped", FocusHistoryText.opensStopped(0))
    }
    @Test fun R17_unconfirmedSessionNotesAreCountedInTheSelectedRange() {
        val result = FocusSummary(HistoryAvailability.VALUE, emptyList(), listOf(FocusDay(today, 0, unconfirmed = 1)), 0)
        assertEquals("0m", FocusHistoryText.today(result))
        assertEquals("1 session couldn't be confirmed", FocusHistoryText.note(result))
        assertEquals("2 sessions couldn't be confirmed", FocusHistoryText.note(result, listOf(FocusDay(today, 0, unconfirmed = 2))))
        assertNull(FocusHistoryText.note(result, emptyList()))
    }
    @Test fun partialWeekLabelsOnlyTheAvailableWindow() {
        val days = (6L downTo 0L).map { FocusDay(today.minusDays(it), if (it > 0) null else 0) }
        assertEquals("Since Wed, Oct 28", FocusHistoryText.weekLabel(days))
        assertEquals("Last 7 days", FocusHistoryText.weekLabel(days.map { it.copy(focusedMs = 0) }))
    }
}
