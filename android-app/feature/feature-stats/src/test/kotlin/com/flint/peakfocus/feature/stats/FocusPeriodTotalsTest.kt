package com.flint.peakfocus.feature.stats

import com.flint.peakfocus.core.history.FocusDay
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class FocusPeriodTotalsTest {
    private val day = LocalDate.of(2026, 10, 28)
    @Test fun missingDayDoesNotBecomeZero() {
        assertEquals("No data", focusPeriodTotals(listOf(FocusDay(day, null))).focused)
        assertEquals("0m", focusPeriodTotals(listOf(FocusDay(day, 0))).focused)
    }
    @Test fun weekAggregatesFocusAndOutcomesSeparately() {
        val result = focusPeriodTotals(listOf(FocusDay(day.minusDays(1), 60_000, 1), FocusDay(day, 120_000, 0, 1)))
        assertEquals("3m", result.focused)
        assertEquals(1, result.completed)
        assertEquals(1, result.endedEarly)
    }
}
