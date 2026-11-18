package com.flint.peakfocus.core.common.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The streak mood rule has exactly one owner. It is consumed by the Insights card and by
 * the Today badge, so a silent change here would move the flame's state on two screens.
 */
class EmberMoodRuleTest {

    @Test
    fun `no active streak is dormant`() {
        assertEquals(StreakEmberMood.Dormant, emberMoodFor(streak = 0, completedToday = true))
        assertEquals(StreakEmberMood.Dormant, emberMoodFor(streak = 0, completedToday = false))
        assertEquals(StreakEmberMood.Dormant, emberMoodFor(streak = -3, completedToday = true))
    }

    @Test
    fun `alive streak completed today is active`() {
        assertEquals(StreakEmberMood.Active, emberMoodFor(streak = 1, completedToday = true))
        assertEquals(StreakEmberMood.Active, emberMoodFor(streak = 365, completedToday = true))
    }

    @Test
    fun `alive streak not yet completed today is warning`() {
        assertEquals(StreakEmberMood.Warning, emberMoodFor(streak = 1, completedToday = false))
        assertEquals(StreakEmberMood.Warning, emberMoodFor(streak = 12, completedToday = false))
    }

    @Test
    fun `warning never appears without an active streak`() {
        for (streak in 0..3) {
            if (streak == 0) {
                assertEquals(StreakEmberMood.Dormant, emberMoodFor(streak, completedToday = false))
            }
        }
    }
}
