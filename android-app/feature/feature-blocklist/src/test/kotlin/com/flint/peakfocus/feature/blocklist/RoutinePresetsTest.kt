package com.flint.peakfocus.feature.blocklist

import com.flint.peakfocus.core.model.AppRef
import com.flint.peakfocus.core.model.BreakLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val work = draftFrom(ROUTINE_PRESETS.first { it.name == "Work hours" })
        assertTrue(work.scheduled)
        assertEquals(IsoDays.WEEKDAYS, work.days)
        assertEquals(9 * 60, work.startMinuteOfDay)
        assertEquals(17 * 60, work.endMinuteOfDay)
        assertEquals(BreakLevel.HARDER, work.breakLevel)

        val detox = draftFrom(ROUTINE_PRESETS.first { it.name == "Social detox" })
        assertFalse(detox.scheduled) // always-on manual rule, not a 00:00–23:59 window
        assertEquals(BreakLevel.HARDCORE, detox.breakLevel)
    }

    @Test
    fun presetNamesAndDescriptionsAreNonBlankAndUnique() {
        assertTrue(ROUTINE_PRESETS.all { it.name.isNotBlank() && it.description.isNotBlank() })
        assertEquals(ROUTINE_PRESETS.size, ROUTINE_PRESETS.map { it.name }.toSet().size)
    }
}
