package com.flint.peakfocus.feature.stats

import com.flint.peakfocus.core.common.ui.StreakEmberMood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The Insights hero is announced as one group. The description must carry the streak and
 * the motivation exactly once, and must not repeat the card title that is already a heading.
 */
class StreakCardDescriptionTest {

    @Test
    fun `active streak reads the caption and the active motivation`() {
        assertEquals(
            "1 day. One completed day at a time.",
            streakCardDescription(streakCaption = "1 day", mood = StreakEmberMood.Active, streak = 1),
        )
        assertEquals(
            "6 days. One completed day at a time.",
            streakCardDescription(streakCaption = "6 days", mood = StreakEmberMood.Active, streak = 6),
        )
    }

    @Test
    fun `active long streak uses the long motivation`() {
        assertEquals(
            "7 days. Quiet consistency. Return tomorrow.",
            streakCardDescription(streakCaption = "7 days", mood = StreakEmberMood.Active, streak = 7),
        )
    }

    @Test
    fun `at risk keeps the cooling copy with no warning graphic`() {
        val description = streakCardDescription(
            streakCaption = "3 days",
            mood = StreakEmberMood.Warning,
            streak = 3,
        )
        assertEquals("3 days. Your streak still holds. Finish a focus session today.", description)
        assertFalse("warning copy must not add an exclamation mark", description.contains("!"))
        assertFalse("warning copy must not announce a danger label", description.contains("danger", ignoreCase = true))
    }

    @Test
    fun `dormant describes the first-day invitation`() {
        assertEquals(
            "No active streak. A completed focus session lights the first day.",
            streakCardDescription(streakCaption = "No active streak", mood = StreakEmberMood.Dormant, streak = 0),
        )
    }

    @Test
    fun `description never duplicates the card title`() {
        val moods = listOf(StreakEmberMood.Active, StreakEmberMood.Warning, StreakEmberMood.Dormant)
        for (mood in moods) {
            val description = streakCardDescription("4 days", mood, 4)
            assertFalse(
                "the group description must not repeat the 'Focus streak' heading",
                description.contains("Focus streak", ignoreCase = true),
            )
            assertEquals(
                "the caption must appear exactly once",
                description.indexOf("4 days"),
                description.lastIndexOf("4 days"),
            )
            assertFalse("description must not be blank", description.isBlank())
        }
    }
}
