package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.EmergencyPassState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyPassPolicyTest {

    private val policy = EmergencyPassPolicy()

    /** Noon (local) on a given epoch-day, as epoch millis with zero UTC offset. */
    private fun noonOf(epochDay: Long): Long = epochDay * DAY_MS + 12 * HOUR_MS

    @Test
    fun weekMathAnchorsOnIsoMondays() {
        // 2026-01-05 is a Monday, epoch-day 20458 — it starts its own week.
        assertEquals(20458L, policy.epochDay(noonOf(20458L)))
        assertEquals(20458L, policy.weekStartEpochDay(noonOf(20458L)))
        // 2026-07-03 is a Friday, epoch-day 20637; its week began Monday 2026-06-29 (20633).
        assertEquals(20633L, policy.weekStartEpochDay(noonOf(20637L)))
        // Sunday of that same week (20639) still belongs to it; next Monday starts a new one.
        assertEquals(20633L, policy.weekStartEpochDay(noonOf(20639L)))
        assertEquals(20640L, policy.weekStartEpochDay(noonOf(20640L)))
    }

    @Test
    fun epochDayFloorsCorrectlyAroundTheEpoch() {
        assertEquals(0L, policy.epochDay(0L))
        assertEquals(0L, policy.epochDay(DAY_MS - 1L))
        assertEquals(-1L, policy.epochDay(-1L)) // floor, not truncation
    }

    @Test
    fun utcOffsetMovesTheDayBoundary() {
        val justBeforeUtcMidnight = 20458L * DAY_MS - 1L
        assertEquals(20457L, policy.epochDay(justBeforeUtcMidnight))
        assertEquals(20458L, policy.epochDay(justBeforeUtcMidnight, utcOffsetMs = HOUR_MS))
    }

    @Test
    fun freshDefaultStateHasThePassAvailable() {
        assertTrue(policy.isAvailable(EmergencyPassState(), noonOf(20637L)))
    }

    @Test
    fun consumeSpendsThisWeeksPass() {
        val now = noonOf(20637L) // Friday
        val spent = policy.consume(EmergencyPassState(), now)
        assertNotNull(spent)
        assertEquals(20633L, spent!!.weekStartEpochDay)
        assertEquals(now, spent.usedAtEpochMs)
        assertFalse(policy.isAvailable(spent, now))
    }

    @Test
    fun onlyOnePassPerWeek() {
        val friday = noonOf(20637L)
        val spent = policy.consume(EmergencyPassState(), friday)!!
        assertNull(policy.consume(spent, friday))                 // same moment
        assertNull(policy.consume(spent, noonOf(20639L)))         // Sunday, same ISO week
        assertFalse(policy.isAvailable(spent, noonOf(20639L)))
    }

    @Test
    fun passResetsOnTheNextIsoWeek() {
        val spent = policy.consume(EmergencyPassState(), noonOf(20637L))!! // used Friday
        val nextMonday = noonOf(20640L)
        assertTrue(policy.isAvailable(spent, nextMonday))
        val respent = policy.consume(spent, nextMonday)
        assertNotNull(respent)
        assertEquals(20640L, respent!!.weekStartEpochDay)
        assertEquals(nextMonday, respent.usedAtEpochMs)
    }

    @Test
    fun refreshedKeepsCurrentWeekStateUntouched() {
        val now = noonOf(20637L)
        val spent = policy.consume(EmergencyPassState(), now)!!
        assertEquals(spent, policy.refreshed(spent, noonOf(20639L))) // still the same week
    }

    @Test
    fun refreshedResetsStaleOrUninitializedState() {
        val fresh = policy.refreshed(EmergencyPassState(), noonOf(20637L))
        assertEquals(EmergencyPassState(20633L, null), fresh)
        val lastWeek = EmergencyPassState(weekStartEpochDay = 20626L, usedAtEpochMs = noonOf(20630L))
        assertEquals(EmergencyPassState(20633L, null), policy.refreshed(lastWeek, noonOf(20637L)))
    }

    @Test
    fun clockSetBackAWeekDoesNotRegrantASpentPass() {
        // Forward-only refresh (time-change guard, always-on half — see ClockChangeGuard).
        val spent = policy.consume(EmergencyPassState(), noonOf(20637L))!! // used Friday
        val lastWeek = noonOf(20630L) // the clock now lies a week back
        assertEquals(spent, policy.refreshed(spent, lastWeek)) // kept, nothing to persist
        assertFalse(policy.isAvailable(spent, lastWeek))
        assertNull(policy.consume(spent, lastWeek))
    }

    private companion object {
        const val DAY_MS = 86_400_000L
        const val HOUR_MS = 3_600_000L
    }
}
