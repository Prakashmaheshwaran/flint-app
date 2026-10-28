package com.flint.peakfocus.core.common.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FlintProgressFractionTest {
    @Test
    fun `preserves valid progress including endpoints`() {
        listOf(0f, 0.25f, 0.5f, 1f).forEach {
            assertEquals(it, flintProgressFraction(it), 0f)
        }
    }

    @Test
    fun `clamps out of range and infinite progress`() {
        assertEquals(0f, flintProgressFraction(-0.1f), 0f)
        assertEquals(1f, flintProgressFraction(1.1f), 0f)
        assertEquals(0f, flintProgressFraction(Float.NEGATIVE_INFINITY), 0f)
        assertEquals(1f, flintProgressFraction(Float.POSITIVE_INFINITY), 0f)
    }

    @Test
    fun `unknown numeric progress is safe for semantics and layout`() {
        assertEquals(0f, flintProgressFraction(Float.NaN), 0f)
    }
}
