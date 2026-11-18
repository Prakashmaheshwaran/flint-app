package com.flint.peakfocus.core.common.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the clock the shipped streak flame actually runs on.
 *
 * The renderer advances one atlas frame every [STREAK_FRAME_MS] and wraps on the frame
 * count, so these functions are the whole timing surface: a regression here is a visible
 * stutter or a frozen flame, and nothing else in the suite would catch it.
 */
class FlameFrameClockTest {

    @Test
    fun `frame index advances once per 60 ms`() {
        assertEquals(0, flameFrameIndex(0, 45))
        assertEquals(0, flameFrameIndex(59, 45))
        assertEquals(1, flameFrameIndex(60, 45))
        assertEquals(2, flameFrameIndex(120, 45))
    }

    @Test
    fun `frame index wraps at the end of the atlas without holding a frame`() {
        assertEquals(44, flameFrameIndex(2_640, 45))
        assertEquals(44, flameFrameIndex(2_699, 45))
        // 2.7 s is exactly one loop: the seam must land on frame 0, not on frame 44 again.
        assertEquals(0, flameFrameIndex(2_700, 45))
        assertEquals(1, flameFrameIndex(2_760, 45))
    }

    @Test
    fun `frame index is safe when the atlas is missing`() {
        assertEquals(0, flameFrameIndex(0, 0))
        assertEquals(0, flameFrameIndex(9_999, 0))
        assertEquals(0, flameFrameIndex(9_999, -1))
    }

    @Test
    fun `elapsed time starts at the paused anchor`() {
        val anchor = 1_234L
        assertEquals(anchor, flameElapsedMs(anchor, 10_000_000_000L, 10_000_000_000L))
    }

    @Test
    fun `resuming continues the frozen phase instead of restarting`() {
        val anchor = 1_200L
        val resumed = flameElapsedMs(anchor, 5_000_000_000L, 5_016_666_667L)
        assertEquals(1_216L, resumed)
        // 1.2 s into the loop stays 1.2 s: the flame never snaps back to frame 0.
        assertEquals(20, flameFrameIndex(resumed, 45))
    }

    @Test
    fun `flame animates only when it is visible and allowed`() {
        assertTrue(animates(animate = true))
        assertFalse(animates(animate = false))
        assertFalse(animates(reducedMotion = true))
        assertFalse(animates(windowFocused = false))
        assertFalse(animates(onScreen = false))
    }

    @Test
    fun `dormant flame stays still unless the host forces motion`() {
        assertFalse(animates(mood = StreakEmberMood.Dormant))
        assertTrue(animates(mood = StreakEmberMood.Dormant, forceAnimate = true))
        assertTrue(animates(mood = StreakEmberMood.Active))
        assertTrue(animates(mood = StreakEmberMood.Warning))
    }

    @Test
    fun `mood alpha matches the shipped container alphas`() {
        assertEquals(1f, flameMoodAlpha(StreakEmberMood.Active))
        assertEquals(0.96f, flameMoodAlpha(StreakEmberMood.Warning))
        assertEquals(0.92f, flameMoodAlpha(StreakEmberMood.Dormant))
    }

    @Test
    fun `atlas frame duration is 60 ms`() {
        assertEquals(60L, STREAK_FRAME_MS)
    }

    private fun animates(
        animate: Boolean = true,
        reducedMotion: Boolean = false,
        windowFocused: Boolean = true,
        onScreen: Boolean = true,
        mood: StreakEmberMood = StreakEmberMood.Active,
        forceAnimate: Boolean = false,
    ): Boolean = flameShouldAnimate(
        animate = animate,
        reducedMotion = reducedMotion,
        windowFocused = windowFocused,
        onScreen = onScreen,
        mood = mood,
        forceAnimate = forceAnimate,
    )
}
