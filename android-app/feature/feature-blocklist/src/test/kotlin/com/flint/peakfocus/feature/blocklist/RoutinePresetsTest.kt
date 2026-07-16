package com.flint.peakfocus.feature.blocklist

import com.flint.peakfocus.core.model.AppRef
import com.flint.peakfocus.core.model.BlockTargets
import com.flint.peakfocus.core.model.BreakLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The preset library is pure data feeding the (already-tested) draft pipeline — verify the seam. */
class RoutinePresetsTest {

    @Test
    fun everyPresetProducesAValidDraftOnceTargetsArePicked() {
        for (preset in ROUTINE_PRESETS) {
            val draft = draftFrom(preset).copy(apps = setOf(AppRef("com.example.app")))
            assertEquals(
                "preset '${preset.name}' must validate cleanly",
                emptyList<RuleDraftError>(),
                validate(draft),
            )
        }
    }

    @Test
    fun presetDraftsStillRequireTargets() {
        // Presets never guess packages — the NO_TARGETS gate must stay in the user's path.
        for (preset in ROUTINE_PRESETS) {
            assertTrue(validate(draftFrom(preset)).contains(RuleDraftError.NO_TARGETS))
        }
    }

    @Test
    fun presetFieldsCarryThroughToTheDraft() {
        val laser = draftFrom(ROUTINE_PRESETS.first { it.name == "Laser Focus" })
        assertTrue(laser.scheduled)
        assertEquals(IsoDays.WEEKDAYS, laser.days)
        assertEquals(9 * 60, laser.startMinuteOfDay)
        assertEquals(12 * 60, laser.endMinuteOfDay)
        assertEquals(BreakLevel.HARDER, laser.breakLevel)

        val gym = draftFrom(ROUTINE_PRESETS.first { it.name == "Gym Time" })
        assertEquals(setOf(1, 3, 5), gym.days)
        assertEquals(17 * 60 + 30, gym.startMinuteOfDay)
        assertEquals(19 * 60, gym.endMinuteOfDay)
    }

    @Test
    fun exactFivePresetContractKeepsNamesOrderAndKeyWindows() {
        assertEquals(
            listOf("Laser Focus", "Rise and Shine", "Reading Time", "Gym Time", "Weekend Limit"),
            ROUTINE_PRESETS.map { it.name },
        )

        val rise = ROUTINE_PRESETS[1]
        assertEquals(emptySet<Int>(), rise.schedule!!.daysOfWeek)
        assertEquals(6 * 60, rise.schedule.startMinuteOfDay)
        assertEquals(9 * 60, rise.schedule.endMinuteOfDay)

        val weekend = ROUTINE_PRESETS[4]
        assertEquals(IsoDays.WEEKEND, weekend.schedule!!.daysOfWeek)
    }

    @Test
    fun everyPresetRoundTripsThroughTheSavedRuleModel() {
        val app = AppRef("com.example.app", "Example")
        for ((index, preset) in ROUTINE_PRESETS.withIndex()) {
            val rule = draftFrom(preset)
                .copy(apps = setOf(app))
                .toRule(id = "preset-$index")

            assertEquals("preset-$index", rule.id)
            assertEquals(preset.name, rule.name)
            assertEquals(
                BlockTargets(apps = setOf(app), allowListMode = preset.allowListMode),
                rule.targets,
            )
            assertEquals(preset.schedule, rule.schedule)
            assertEquals(preset.breakLevel, rule.breakLevel)
            assertTrue(rule.enabled)
        }
    }

    @Test
    fun presetNamesAndDescriptionsAreNonBlankAndUnique() {
        assertEquals(5, ROUTINE_PRESETS.size)
        assertTrue(ROUTINE_PRESETS.all { it.name.isNotBlank() && it.description.isNotBlank() })
        assertEquals(ROUTINE_PRESETS.size, ROUTINE_PRESETS.map { it.name }.toSet().size)
        assertTrue(ROUTINE_PRESETS.none { it.breakLevel == BreakLevel.HARDCORE })
    }
}
