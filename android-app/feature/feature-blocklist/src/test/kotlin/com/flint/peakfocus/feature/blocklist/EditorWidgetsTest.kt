package com.flint.peakfocus.feature.blocklist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the day strip's window-fraction math. */
class EditorWidgetsTest {

    @Test
    fun `same-day window is a single segment`() {
        assertEquals(
            listOf(540 / 1440f to 1020 / 1440f),
            dayStripSegments(540, 1020),
        )
    }

    @Test
    fun `wrap-midnight window splits at the day edge`() {
        assertEquals(
            listOf(1320 / 1440f to 1f, 0f to 420 / 1440f),
            dayStripSegments(1320, 420),
        )
    }

    @Test
    fun `midnight endpoints sit exactly on the track edges`() {
        assertEquals(listOf(0f to 720 / 1440f), dayStripSegments(0, 720))
    }

    @Test
    fun `zero-length window draws nothing - engine never matches it`() {
        assertTrue(dayStripSegments(600, 600).isEmpty())
    }

    @Test
    fun `out-of-range endpoints draw nothing`() {
        assertTrue(dayStripSegments(-1, 600).isEmpty())
        assertTrue(dayStripSegments(600, 1440).isEmpty())
    }
}
