package com.flint.peakfocus.feature.stats

import org.junit.Assert.assertEquals
import org.junit.Test

class DurationFormatTest {

    private val minute = 60_000L
    private val hour = 3_600_000L

    @Test
    fun zeroAndNegativeReadAsZeroMinutes() {
        assertEquals("0m", DurationFormat.format(0L))
        assertEquals("0m", DurationFormat.format(-5_000L))
    }

    @Test
    fun subMinuteReadsAsUnderOneMinute() {
        assertEquals("<1m", DurationFormat.format(1_000L))
        assertEquals("<1m", DurationFormat.format(59_999L))
    }

    @Test
    fun wholeMinutesUnderAnHour() {
        assertEquals("1m", DurationFormat.format(minute))
        assertEquals("37m", DurationFormat.format(37 * minute))
        assertEquals("59m", DurationFormat.format(59 * minute))
    }

    @Test
    fun minutesRoundDownNeverUp() {
        assertEquals("2m", DurationFormat.format(2 * minute + 59_000L))
    }

    @Test
    fun exactHoursOmitMinutes() {
        assertEquals("1h", DurationFormat.format(hour))
        assertEquals("24h", DurationFormat.format(24 * hour))
    }

    @Test
    fun hoursAndMinutesCombine() {
        assertEquals("2h 14m", DurationFormat.format(2 * hour + 14 * minute))
        assertEquals("1h 1m", DurationFormat.format(hour + minute))
    }

    @Test
    fun justUnderAnHourStaysInMinutes() {
        assertEquals("59m", DurationFormat.format(hour - 1L))
    }
}
