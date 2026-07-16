package com.flint.peakfocus.feature.blocklist

import com.flint.peakfocus.core.model.AppRef
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
    fun presetNamesAndDescriptionsAreNonBlankAndUnique() {
        assertEquals(5, ROUTINE_PRESETS.size)
        assertTrue(ROUTINE_PRESETS.all { it.name.isNotBlank() && it.description.isNotBlank() })
        assertEquals(ROUTINE_PRESETS.size, ROUTINE_PRESETS.map { it.name }.toSet().size)
        assertTrue(ROUTINE_PRESETS.none { it.breakLevel == BreakLevel.HARDCORE })
    }
}
