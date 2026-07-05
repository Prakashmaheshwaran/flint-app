package com.flint.peakfocus.feature.blocklist

import com.flint.peakfocus.core.model.AppRef
import com.flint.peakfocus.core.model.BreakLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the preset routine library: catalog invariants (every preset must be
 * one app-pick away from a saveable rule), the documented spec windows, and the
 * preset → [RuleDraft] mapping the overview chips rely on.
 */
class RoutinePresetsTest {

    // ---- Catalog invariants ----

    @Test
    fun `catalog ships the five inventory presets in order`() {
        assertEquals(
            listOf("Laser Focus", "Rise and Shine", "Reading Time", "Gym Time", "Weekend Limit"),
            RoutinePresets.ALL.map { it.name },
        )
    }

    @Test
    fun `ids are unique and stable`() {
        val ids = RoutinePresets.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(
            listOf("laser-focus", "rise-and-shine", "reading-time", "gym-time", "weekend-limit"),
            ids,
        )
    }

    @Test
    fun `names and taglines are user-presentable`() {
        RoutinePresets.ALL.forEach { preset ->
            assertFalse("blank name for ${preset.id}", preset.name.isBlank())
            assertFalse("blank tagline for ${preset.id}", preset.tagline.isBlank())
        }
    }

    @Test
    fun `days respect the ISO contract`() {
        RoutinePresets.ALL.forEach { preset ->
            assertTrue(
                "days out of 1..7 for ${preset.id}",
                preset.days.all { it in 1..7 },
            )
        }
    }

    @Test
    fun `windows are valid and never the never-active equal-endpoints shape`() {
        RoutinePresets.ALL.forEach { preset ->
            assertTrue(
                "start out of range for ${preset.id}",
                preset.startMinuteOfDay in MINUTE_OF_DAY_RANGE,
            )
            assertTrue(
                "end out of range for ${preset.id}",
                preset.endMinuteOfDay in MINUTE_OF_DAY_RANGE,
            )
            assertTrue(
                "equal endpoints (never matches) for ${preset.id}",
                preset.startMinuteOfDay != preset.endMinuteOfDay,
            )
        }
    }

    @Test
    fun `no preset defaults to HARDCORE`() {
        // A one-tap template must not arm a cannot-stop-early block the user didn't
        // consciously choose — the catalog's documented lockout-foot-gun rule.
        RoutinePresets.ALL.forEach { preset ->
            assertTrue(
                "${preset.id} defaults to HARDCORE",
                preset.breakLevel != BreakLevel.HARDCORE,
            )
        }
    }

    @Test
    fun `rise and shine keeps the documented 6 to 9 AM window, every day`() {
        val preset = RoutinePresets.byId("rise-and-shine")!!
        assertEquals(6 * 60, preset.startMinuteOfDay)
        assertEquals(9 * 60, preset.endMinuteOfDay)
        assertTrue("should be every day (empty set)", preset.days.isEmpty())
    }

    @Test
    fun `laser focus is weekdays, weekend limit is the weekend`() {
        assertEquals(IsoDays.WEEKDAYS, RoutinePresets.byId("laser-focus")!!.days)
        assertEquals(IsoDays.WEEKEND, RoutinePresets.byId("weekend-limit")!!.days)
    }

    @Test
    fun `byId resolves every catalog entry and rejects unknowns`() {
        RoutinePresets.ALL.forEach { preset ->
            assertEquals(preset, RoutinePresets.byId(preset.id))
        }
        assertNull(RoutinePresets.byId("doomscroll-forever"))
        assertNull(RoutinePresets.byId(""))
    }

    // ---- Preset → draft mapping ----

    @Test
    fun `presetDraft carries the routine shape and nothing else`() {
        val preset = RoutinePresets.byId("gym-time")!!
        val draft = presetDraft(preset)

        assertNull(draft.id)
        assertEquals(preset.name, draft.name)
        assertTrue(draft.scheduled)
        assertEquals(preset.days, draft.days)
        assertEquals(preset.startMinuteOfDay, draft.startMinuteOfDay)
        assertEquals(preset.endMinuteOfDay, draft.endMinuteOfDay)
        assertEquals(preset.breakLevel, draft.breakLevel)
        assertTrue(draft.enabled)
        // Targets stay empty by design — the user picks their own apps/sites.
        assertTrue(draft.apps.isEmpty())
        assertTrue(draft.domains.isEmpty())
        assertFalse(draft.allowListMode)
    }

    @Test
    fun `every preset is exactly one app-pick away from saveable`() {
        RoutinePresets.ALL.forEach { preset ->
            val draft = presetDraft(preset)
            // The only thing standing between a tapped preset and Save is choosing targets —
            // never a name, time, or window problem the user would have to debug.
            assertEquals(
                "unexpected validation errors for ${preset.id}",
                listOf(RuleDraftError.NO_TARGETS),
                validate(draft),
            )
            val withApp = draft.copy(
                apps = setOf(AppRef("com.instagram.android", "Instagram")),
            )
            assertTrue(
                "still unsaveable after picking an app: ${preset.id} -> ${validate(withApp)}",
                validate(withApp).isEmpty(),
            )
        }
    }

    @Test
    fun `saved preset rule round-trips into the schedule the card promised`() {
        RoutinePresets.ALL.forEach { preset ->
            val rule = presetDraft(preset)
                .copy(apps = setOf(AppRef("com.zhiliaoapp.musically", "TikTok")))
                .toRule(id = "test-${preset.id}")

            val schedule = rule.schedule
            assertTrue("preset rule must be scheduled: ${preset.id}", schedule != null)
            assertEquals(preset.days, schedule!!.daysOfWeek)
            assertEquals(preset.startMinuteOfDay, schedule.startMinuteOfDay)
            assertEquals(preset.endMinuteOfDay, schedule.endMinuteOfDay)
            assertEquals(preset.breakLevel, rule.breakLevel)
            assertEquals(preset.name, rule.name)
            assertTrue(rule.enabled)
        }
    }
}
