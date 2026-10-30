package com.flint.peakfocus.feature.blocklist

import com.flint.peakfocus.core.model.BreakLevel
import org.junit.Assert.*
import org.junit.Test

class SleepPresentationTest {
    @Test fun `saved tier takes precedence over assist label`() {
        val config = SleepConfig(breakLevel = BreakLevel.HARDCORE)
        assertEquals(R.string.sleep_tier_hardcore, sleepPresentation(config).tier)
        assertEquals(R.string.sleep_off_hardcore, sleepPresentation(config).offExplanation)
        assertEquals(config, sleepConfigFrom(config.toRules()))
        assertNull(config.assist)
    }

    @Test fun `each tier explains its own off gate`() {
        val expected = listOf(
            BreakLevel.EASY to R.string.sleep_off_easy,
            BreakLevel.HARDER to R.string.sleep_off_harder,
            BreakLevel.HARDCORE to R.string.sleep_off_hardcore,
        )
        expected.forEach { (tier, text) ->
            assertEquals(text, sleepPresentation(SleepConfig(breakLevel = tier)).offExplanation)
        }
    }

    @Test fun `empty nights means every night and custom nights retain individual labels`() {
        assertEquals(R.string.sleep_every_night, sleepPresentation(SleepConfig(nights = emptySet())).nights)
        assertEquals(R.string.sleep_weeknights, sleepPresentation(SleepConfig()).nights)
        assertNull(sleepPresentation(SleepConfig(nights = setOf(1, 3))).nights)
    }

    @Test fun `narrow or large text stacks time fields`() {
        assertTrue(sleepTimeFieldsStack(320f, 1f))
        assertTrue(sleepTimeFieldsStack(420f, 2f))
        assertFalse(sleepTimeFieldsStack(420f, 1f))
    }

    @Test fun PC_82_strongerSavedMorningTierDoesNotAdvertiseEasyOffRights() {
        val config = SleepConfig(morningEnabled = true, breakLevel = BreakLevel.EASY, morningBreakLevel = BreakLevel.HARDCORE)
        assertEquals(R.string.sleep_tier_easy, sleepPresentation(config).tier)
        assertEquals(R.string.sleep_off_hardcore, sleepPresentation(config).offExplanation)
    }
}
