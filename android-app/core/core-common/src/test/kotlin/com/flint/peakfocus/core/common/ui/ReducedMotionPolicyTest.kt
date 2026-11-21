package com.flint.peakfocus.core.common.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the shared Reduce Motion policy. Every `rememberReducedMotion()` consumer in the app
 * inherits this threshold, so a change here is an app-wide motion change, not a flame-only one.
 */
class ReducedMotionPolicyTest {

    @Test
    fun `removed animations count as reduced`() {
        assertTrue(isReducedMotionScale(0f))
        assertTrue(isReducedMotionScale(-1f))
    }

    @Test
    fun `partial animator scales count as reduced`() {
        // The behaviour this policy was aligned for: 0.5x and 0.75x are "less motion".
        assertTrue(isReducedMotionScale(0.5f))
        assertTrue(isReducedMotionScale(0.75f))
        assertTrue(isReducedMotionScale(0.999f))
    }

    @Test
    fun `full and faster animator scales keep motion`() {
        assertFalse(isReducedMotionScale(1f))
        assertFalse(isReducedMotionScale(1.5f))
        assertFalse(isReducedMotionScale(2f))
    }
}
