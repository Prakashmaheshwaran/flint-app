package com.flint.peakfocus.feature.blocklist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure-JVM tests for the schedule editor's "HH:MM" ↔ minute-of-day conversion. */
class TimeTextTest {

    @Test
    fun `parses zero-padded and bare digits`() {
        assertEquals(570, parseMinuteOfDay("09:30"))
        assertEquals(570, parseMinuteOfDay("9:30"))
        assertEquals(0, parseMinuteOfDay("0:00"))
        assertEquals(1439, parseMinuteOfDay("23:59"))
        assertEquals(425, parseMinuteOfDay("7:5"))
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertEquals(600, parseMinuteOfDay(" 10:00 "))
    }

    @Test
    fun `rejects out-of-range times - midnight-end is written as 00-00`() {
        assertNull(parseMinuteOfDay("24:00"))
        assertNull(parseMinuteOfDay("25:10"))
        assertNull(parseMinuteOfDay("12:60"))
        assertNull(parseMinuteOfDay("-1:30"))
    }

    @Test
    fun `rejects malformed text`() {
        assertNull(parseMinuteOfDay(""))
        assertNull(parseMinuteOfDay("12"))
        assertNull(parseMinuteOfDay("12:"))
        assertNull(parseMinuteOfDay(":30"))
        assertNull(parseMinuteOfDay("ab:cd"))
        assertNull(parseMinuteOfDay("1:2:3"))
        assertNull(parseMinuteOfDay("123:45"))
        assertNull(parseMinuteOfDay("12:345"))
        assertNull(parseMinuteOfDay("12 : 30"))
    }

    @Test
    fun `formats zero-padded 24h text`() {
        assertEquals("09:30", formatMinuteOfDay(570))
        assertEquals("00:00", formatMinuteOfDay(0))
        assertEquals("23:59", formatMinuteOfDay(1439))
        assertEquals("07:05", formatMinuteOfDay(425))
    }

    @Test
    fun `format clamps out-of-range input`() {
        assertEquals("00:00", formatMinuteOfDay(-5))
        assertEquals("23:59", formatMinuteOfDay(2000))
    }

    @Test
    fun `format then parse round-trips every boundary`() {
        for (minute in listOf(0, 1, 59, 60, 540, 1330, 1439)) {
            assertEquals(minute, parseMinuteOfDay(formatMinuteOfDay(minute)))
        }
    }
}
