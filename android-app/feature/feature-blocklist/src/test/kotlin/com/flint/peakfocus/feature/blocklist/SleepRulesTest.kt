package com.flint.peakfocus.feature.blocklist

import com.flint.peakfocus.core.model.AppRef
import com.flint.peakfocus.core.model.BreakLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure coverage of the sleep-config ↔ rules materialization. The engine side needs no new
 * tests — sleep rules are ordinary allow-list schedule rules, whose semantics
 * BlockDecisionEngineTest already pins down (overnight windows included).
 */
class SleepRulesTest {

    private val config = SleepConfig(
        enabled = true,
        nights = setOf(1, 2, 3, 4, 5),
        bedMinute = 22 * 60 + 30,
        wakeMinute = 7 * 60,
        assist = SleepAssist.FULL_ASSIST,
        allowedApps = setOf(AppRef("com.android.deskclock", "Clock")),
        morningEnabled = true,
        morningEndMinute = 8 * 60,
    )

    @Test
    fun roundTripsThroughTheMaterializedRules() {
        assertEquals(config, sleepConfigFrom(config.toRules()))
        val windowOnly = config.copy(morningEnabled = false)
        assertEquals(windowOnly, sleepConfigFrom(windowOnly.toRules()))
        val disabled = config.copy(enabled = false, assist = SleepAssist.WIND_DOWN)
        assertEquals(disabled, sleepConfigFrom(disabled.toRules()))
    }

    @Test
    fun windowRuleIsAnOvernightAllowListOnTheChosenNights() {
        val window = config.toRules().first { it.id == SLEEP_WINDOW_RULE_ID }
        assertTrue(window.targets.allowListMode)
        assertEquals(setOf(AppRef("com.android.deskclock", "Clock")), window.targets.apps)
        assertEquals(setOf(1, 2, 3, 4, 5), window.schedule?.daysOfWeek)
        assertEquals(22 * 60 + 30, window.schedule?.startMinuteOfDay)
        assertEquals(7 * 60, window.schedule?.endMinuteOfDay) // bed > wake: engine wraps it
    }

    @Test
    fun morningRuleCoversTheMorningsAfterEachNight() {
        val morning = config.toRules().first { it.id == SLEEP_MORNING_RULE_ID }
        assertEquals(setOf(2, 3, 4, 5, 6), morning.schedule?.daysOfWeek)
        assertEquals(7 * 60, morning.schedule?.startMinuteOfDay)
        assertEquals(8 * 60, morning.schedule?.endMinuteOfDay)
    }

    @Test
    fun sundayNightWrapsToMondayMorning() {
        assertEquals(setOf(1), morningsAfter(setOf(7)))
        assertEquals(setOf(1, 6, 7), morningsAfter(setOf(5, 6, 7)))
    }

    @Test
    fun assistLevelMapsToTierAndNeverToHardcore() {
        val windDown = config.copy(assist = SleepAssist.WIND_DOWN).toRules()
        val fullAssist = config.copy(assist = SleepAssist.FULL_ASSIST).toRules()
        assertTrue(windDown.all { it.breakLevel == BreakLevel.EASY })
        assertTrue(fullAssist.all { it.breakLevel == BreakLevel.HARDER })
    }

    @Test
    fun disabledConfigStillMaterializesDisabledRulesSoNothingIsLost() {
        val rules = config.copy(enabled = false).toRules()
        assertEquals(2, rules.size)
        assertTrue(rules.none { it.enabled })
    }

    @Test
    fun morningOffMaterializesOnlyTheWindowRule() {
        val rules = config.copy(morningEnabled = false).toRules()
        assertEquals(listOf(SLEEP_WINDOW_RULE_ID), rules.map { it.id })
    }

    @Test
    fun parsingReturnsNullWithoutAWindowRule() {
        assertNull(sleepConfigFrom(emptyList()))
        val morningOnly = config.toRules().filter { it.id == SLEEP_MORNING_RULE_ID }
        assertNull(sleepConfigFrom(morningOnly))
    }

    // MARK: validation

    @Test
    fun validRangesPass() {
        assertTrue(validate(config).isEmpty())
        assertTrue(validate(config.copy(morningEnabled = false)).isEmpty())
    }

    @Test
    fun equalBedAndWakeIsRejected() {
        val bad = config.copy(bedMinute = 420, wakeMinute = 420)
        assertTrue(SleepConfigError.ZERO_LENGTH_WINDOW in validate(bad))
    }

    @Test
    fun morningMustEndAfterWake() {
        val atWake = config.copy(morningEndMinute = config.wakeMinute)
        val beforeWake = config.copy(morningEndMinute = config.wakeMinute - 30)
        assertTrue(SleepConfigError.MORNING_NOT_AFTER_WAKE in validate(atWake))
        assertTrue(SleepConfigError.MORNING_NOT_AFTER_WAKE in validate(beforeWake))
        // Irrelevant while morning assist is off.
        assertTrue(validate(atWake.copy(morningEnabled = false)).isEmpty())
    }

    @Test
    fun outOfRangeTimesAreRejected() {
        assertTrue(SleepConfigError.INVALID_TIME in validate(config.copy(bedMinute = -1)))
        assertTrue(SleepConfigError.INVALID_TIME in validate(config.copy(morningEndMinute = 1440)))
    }

    @Test
    fun corruptedWindowWithoutAllowListModeParsesAsNotSetUp() {
        // A block-list-shaped sleep-window rule (codec fallback / foreign writer) would be
        // inverted enforcement — the parse must refuse it rather than render a healthy card.
        val corrupted = config.copy(enabled = true).toRules().map { rule ->
            rule.copy(targets = rule.targets.copy(allowListMode = false))
        }
        assertNull(sleepConfigFrom(corrupted))
    }

    // MARK: post-midnight bedtimes (bed < wake — the window is a same-day slice after 00:00)

    @Test
    fun postMidnightBedtimeShiftsTheWindowToTheMorningDay() {
        // "Friday night" with a 00:30 bedtime actually happens in Saturday's small hours
        // (ISO 6) — the engine treats bed < wake as a same-day window, so the day gate
        // must shift, and Morning Assist sits on that same morning, not a day later.
        val night5 = config.copy(
            nights = setOf(5),
            bedMinute = 30,
            wakeMinute = 7 * 60,
            morningEnabled = true,
            morningEndMinute = 8 * 60,
        )
        val rules = night5.toRules()
        assertEquals(setOf(6), rules.first { it.id == SLEEP_WINDOW_RULE_ID }.schedule?.daysOfWeek)
        assertEquals(setOf(6), rules.first { it.id == SLEEP_MORNING_RULE_ID }.schedule?.daysOfWeek)
    }

    @Test
    fun postMidnightConfigRoundTripsIncludingTheSundayWrap() {
        val sundayNight = config.copy(
            nights = setOf(7),
            bedMinute = 60,
            wakeMinute = 6 * 60,
            morningEnabled = false,
        )
        assertEquals(sundayNight, sleepConfigFrom(sundayNight.toRules()))
    }

    // MARK: summary

    @Test
    fun summaryNamesTheCommonNightSetsAndTheWindow() {
        assertEquals("Weeknights · 22:30 – 07:00 · morning to 08:00", sleepSummary(config))
        assertEquals(
            "Every night · 22:30 – 07:00",
            sleepSummary(config.copy(nights = emptySet(), morningEnabled = false)),
        )
        assertEquals(
            "Weekend nights · 22:30 – 07:00",
            sleepSummary(config.copy(nights = setOf(6, 7), morningEnabled = false)),
        )
        assertEquals(
            "Mon Wed · 22:30 – 07:00",
            sleepSummary(config.copy(nights = setOf(3, 1), morningEnabled = false)),
        )
    }
}
