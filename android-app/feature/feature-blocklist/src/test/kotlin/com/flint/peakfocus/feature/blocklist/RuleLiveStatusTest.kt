package com.flint.peakfocus.feature.blocklist

import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.BlockTargets
import com.flint.peakfocus.core.model.Schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the overview's minute-tick "Active now" derivation. Semantics must mirror
 * `BlockDecisionEngine.scheduleActive`: half-open windows, overnight wrap riding the start day.
 */
class RuleLiveStatusTest {

    private fun rule(schedule: Schedule?, enabled: Boolean = true) = BlockRule(
        id = "r1",
        name = "Rule",
        targets = BlockTargets(),
        schedule = schedule,
        enabled = enabled,
    )

    private val weekdayNine2Five = Schedule(IsoDays.WEEKDAYS, 9 * 60, 17 * 60)

    @Test
    fun `disabled rules are never live and name no edge`() {
        val status = ruleLiveStatus(rule(weekdayNine2Five, enabled = false), 1, 600)
        assertFalse(status.activeNow)
        assertNull(status.nextChange)
    }

    @Test
    fun `unscheduled enabled rule is always live with no edge`() {
        val status = ruleLiveStatus(rule(null), 3, 100)
        assertTrue(status.activeNow)
        assertNull(status.nextChange)
    }

    @Test
    fun `daytime window is live inside and announces its end`() {
        val status = ruleLiveStatus(rule(weekdayNine2Five), 1, 10 * 60)
        assertTrue(status.activeNow)
        assertEquals("Until 17:00", status.nextChange)
    }

    @Test
    fun `daytime window before start announces the start`() {
        val status = ruleLiveStatus(rule(weekdayNine2Five), 1, 8 * 60)
        assertFalse(status.activeNow)
        assertEquals("Starts 09:00", status.nextChange)
    }

    @Test
    fun `window end is exclusive - engine's half-open interval`() {
        assertFalse(ruleLiveStatus(rule(weekdayNine2Five), 1, 17 * 60).activeNow)
    }

    @Test
    fun `day gate keeps other days inactive`() {
        val saturday = 6
        val status = ruleLiveStatus(rule(weekdayNine2Five), saturday, 10 * 60)
        assertFalse(status.activeNow)
        assertEquals("Starts 09:00", status.nextChange)
    }

    @Test
    fun `empty day set means every day`() {
        val schedule = Schedule(emptySet(), 9 * 60, 17 * 60)
        assertTrue(ruleLiveStatus(rule(schedule), 7, 10 * 60).activeNow)
    }

    @Test
    fun `overnight head is live on the night's own day`() {
        val fridayNight = Schedule(setOf(5), 22 * 60, 6 * 60)
        val status = ruleLiveStatus(rule(fridayNight), 5, 22 * 60 + 30)
        assertTrue(status.activeNow)
        assertEquals("Until 06:00", status.nextChange)
    }

    @Test
    fun `overnight tail rides the previous day`() {
        val fridayNight = Schedule(setOf(5), 22 * 60, 6 * 60)
        // Saturday 02:00 belongs to Friday's night.
        assertTrue(ruleLiveStatus(rule(fridayNight), 6, 2 * 60).activeNow)
    }

    @Test
    fun `overnight tail is not live when the previous night was not chosen`() {
        val fridayNight = Schedule(setOf(5), 22 * 60, 6 * 60)
        // Friday 02:00 is Thursday's night — not chosen.
        val status = ruleLiveStatus(rule(fridayNight), 5, 2 * 60)
        assertFalse(status.activeNow)
        assertEquals("Starts 22:00", status.nextChange)
    }

    @Test
    fun `overnight tail wraps sunday back to monday`() {
        val sundayNight = Schedule(setOf(7), 23 * 60, 5 * 60)
        // Monday 01:00 belongs to Sunday's night.
        assertTrue(ruleLiveStatus(rule(sundayNight), 1, 60).activeNow)
    }

    @Test
    fun `overnight gap between end and start is inactive`() {
        val nightly = Schedule(emptySet(), 22 * 60, 6 * 60)
        assertFalse(ruleLiveStatus(rule(nightly), 3, 12 * 60).activeNow)
    }

    @Test
    fun `equal endpoints never activate and name no edge`() {
        val status = ruleLiveStatus(rule(Schedule(emptySet(), 600, 600)), 1, 600)
        assertFalse(status.activeNow)
        assertNull(status.nextChange)
    }

    @Test
    fun `out-of-range endpoints never activate`() {
        assertFalse(ruleLiveStatus(rule(Schedule(emptySet(), -1, 600)), 1, 300).activeNow)
        assertFalse(ruleLiveStatus(rule(Schedule(emptySet(), 600, 1440)), 1, 700).activeNow)
    }

    @Test
    fun `statuses map keys every rule by id`() {
        val rules = listOf(
            rule(null),
            rule(weekdayNine2Five).copy(id = "r2"),
        )
        val statuses = ruleLiveStatuses(rules, nowMillis = System.currentTimeMillis())
        assertEquals(setOf("r1", "r2"), statuses.keys)
    }
}
