package com.flint.peakfocus.feature.blocklist

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Today badge is a single TalkBack node. Its label must state the count once and must
 * never produce the previously shipped "1 days" or leave the zero state ambiguous.
 */
class StreakBadgeLabelTest {

    @Test
    fun `a single day is singular`() {
        assertEquals("Focus streak, 1 day", streakBadgeLabel(1))
    }

    @Test
    fun `multiple days are plural`() {
        assertEquals("Focus streak, 2 days", streakBadgeLabel(2))
        assertEquals("Focus streak, 30 days", streakBadgeLabel(30))
    }

    @Test
    fun `no active streak is stated explicitly`() {
        assertEquals("No active focus streak", streakBadgeLabel(0))
        assertEquals("No active focus streak", streakBadgeLabel(-1))
    }

    @Test
    fun `every count uses the exact singular or plural label`() {
        for (streak in 0..400) {
            val expected = when {
                streak <= 0 -> "No active focus streak"
                streak == 1 -> "Focus streak, 1 day"
                else -> "Focus streak, $streak days"
            }
            assertEquals("streak $streak produced the wrong label", expected, streakBadgeLabel(streak))
        }
    }

    @Test
    fun `unreadable history uses a neutral badge label`() {
        // A null summary and an UNAVAILABLE store both mean "not read", not "zero streak".
        assertEquals("Focus streak", streakBadgeDescription(historyKnown = false, streak = 0))
        assertEquals("Focus streak", streakBadgeDescription(historyKnown = false, streak = 12))
    }

    @Test
    fun `readable history uses the count label`() {
        assertEquals("No active focus streak", streakBadgeDescription(historyKnown = true, streak = 0))
        assertEquals("Focus streak, 1 day", streakBadgeDescription(historyKnown = true, streak = 1))
        assertEquals("Focus streak, 12 days", streakBadgeDescription(historyKnown = true, streak = 12))
    }
}
