package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.AppRef
import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.BlockTargets
import com.flint.peakfocus.core.model.Schedule
import com.flint.peakfocus.core.model.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the platform→ISO weekday bridge that both enforcement services depend on. The values here
 * are `java.util.Calendar.DAY_OF_WEEK` constants (1 = Sunday … 7 = Saturday), spelled out so the
 * test documents the mapping without importing the framework class the pure engine avoids.
 */
class IsoWeekdayTest {

    private val sunday = 1
    private val monday = 2
    private val tuesday = 3
    private val wednesday = 4
    private val thursday = 5
    private val friday = 6
    private val saturday = 7

    @Test
    fun `maps every calendar day to its iso number`() {
        assertEquals(1, IsoWeekday.fromCalendar(monday))
        assertEquals(2, IsoWeekday.fromCalendar(tuesday))
        assertEquals(3, IsoWeekday.fromCalendar(wednesday))
        assertEquals(4, IsoWeekday.fromCalendar(thursday))
        assertEquals(5, IsoWeekday.fromCalendar(friday))
        assertEquals(6, IsoWeekday.fromCalendar(saturday))
        assertEquals(7, IsoWeekday.fromCalendar(sunday))
    }

    @Test
    fun `sunday and monday are the error-prone endpoints`() {
        // The whole reason the conversion exists: Calendar Sunday=1 is ISO 7, Calendar Monday=2 is
        // ISO 1. Getting this backwards is exactly the "blocks Sunday, skips Friday" bug.
        assertEquals(7, IsoWeekday.fromCalendar(sunday))
        assertEquals(1, IsoWeekday.fromCalendar(monday))
    }

    @Test
    fun `out-of-range platform values fail safe to unknown`() {
        listOf(0, 8, -1, Int.MIN_VALUE, Int.MAX_VALUE).forEach {
            assertEquals(IsoWeekday.UNKNOWN, IsoWeekday.fromCalendar(it))
        }
    }

    @Test
    fun `unknown disables the day gate instead of pinning a wrong day`() {
        // weekday <= 0 → the engine gates on the time window alone (blocks in-window every day),
        // the fail-safe direction for a focus blocker when the calendar can't be read.
        val schedule = Schedule(daysOfWeek = setOf(1), startMinuteOfDay = 540, endMinuteOfDay = 1020)
        assertTrue(BlockDecisionEngine().scheduleActive(schedule, 600, IsoWeekday.UNKNOWN))
    }

    @Test
    fun `a weekdays rule blocks friday and allows sunday when fed through the converter`() {
        // Regression lock for the exact failure the inline conversions guarded against: without it
        // a Mon–Fri rule would block Sunday (Calendar 1) and skip Friday (Calendar 6). Reverting
        // either service to pass the raw Calendar value flips both assertions.
        val engine = BlockDecisionEngine()
        val self = "com.flint.peakfocus"
        val weekdays = Schedule(
            daysOfWeek = setOf(1, 2, 3, 4, 5), // ISO Mon–Fri
            startMinuteOfDay = 540,
            endMinuteOfDay = 1020,
        )
        val rule = BlockRule(
            id = "work",
            name = "work",
            targets = BlockTargets(apps = setOf(AppRef("com.x"))),
            schedule = weekdays,
        )
        val friday10 = engine.decide("com.x", null, listOf(rule), self, 600, IsoWeekday.fromCalendar(friday))
        val sunday10 = engine.decide("com.x", null, listOf(rule), self, 600, IsoWeekday.fromCalendar(sunday))
        assertTrue(friday10 is Verdict.Block)
        assertEquals(Verdict.Allow, sunday10)
    }
}
