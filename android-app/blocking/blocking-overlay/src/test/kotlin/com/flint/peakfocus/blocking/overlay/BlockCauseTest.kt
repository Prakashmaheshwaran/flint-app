package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.BlockTargets
import com.flint.peakfocus.core.model.BreakLevel
import com.flint.peakfocus.core.model.BreakRequestState
import com.flint.peakfocus.core.model.BreakSessionState
import com.flint.peakfocus.core.model.OpenLimit
import com.flint.peakfocus.core.model.Schedule
import com.flint.peakfocus.feature.blockscreen.BlockScreenReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure decision→BlockScreenState glue: reason/tier mapping, block-window
 * ends, and the HARDER cooldown projection. No Android, no clocks — fixed epochs throughout.
 */
class BlockCauseTest {

    private val hour = 3_600_000L
    private val baseMidnight = 100L * DAY_MS // an arbitrary local midnight (offset 0)

    private fun rule(
        level: BreakLevel = BreakLevel.EASY,
        schedule: Schedule? = null,
    ) = BlockRule(
        id = "r1",
        name = "Rule",
        targets = BlockTargets(),
        schedule = schedule,
        breakLevel = level,
    )

    // MARK: ruleBlockCause

    @Test
    fun `manual easy rule maps to manual session, open-ended`() {
        val cause = ruleBlockCause(rule(BreakLevel.EASY), baseMidnight + 12 * hour, 0L)
        assertEquals(BlockScreenReason.MANUAL_SESSION, cause.reason)
        assertEquals(BreakLevel.EASY, cause.breakLevel)
        assertNull(cause.endsAtEpochMs)
        assertFalse(cause.spendsOpenOnBreak)
    }

    @Test
    fun `manual hardcore rule is deep focus`() {
        val cause = ruleBlockCause(rule(BreakLevel.HARDCORE), baseMidnight, 0L)
        assertEquals(BlockScreenReason.DEEP_FOCUS, cause.reason)
        assertEquals(BreakLevel.HARDCORE, cause.breakLevel)
        assertNull(cause.endsAtEpochMs)
    }

    @Test
    fun `scheduled rule maps to schedule and ends at window end`() {
        val schedule = Schedule(startMinuteOfDay = 10 * 60, endMinuteOfDay = 17 * 60)
        val now = baseMidnight + 12 * hour // 12:00 local
        val cause = ruleBlockCause(rule(BreakLevel.HARDER, schedule), now, 0L)
        assertEquals(BlockScreenReason.SCHEDULE, cause.reason)
        assertEquals(BreakLevel.HARDER, cause.breakLevel)
        assertEquals(now + 5 * hour, cause.endsAtEpochMs) // 17:00
    }

    @Test
    fun `scheduled hardcore rule stays schedule reason with hardcore tier`() {
        val schedule = Schedule(startMinuteOfDay = 0, endMinuteOfDay = 23 * 60)
        val cause = ruleBlockCause(rule(BreakLevel.HARDCORE, schedule), baseMidnight + hour, 0L)
        assertEquals(BlockScreenReason.SCHEDULE, cause.reason)
        assertEquals(BreakLevel.HARDCORE, cause.breakLevel)
    }

    @Test
    fun `missing rule degrades to easy manual session, never drops enforcement data`() {
        val cause = ruleBlockCause(null, baseMidnight, 0L)
        assertEquals(BlockScreenReason.MANUAL_SESSION, cause.reason)
        assertEquals(BreakLevel.EASY, cause.breakLevel)
        assertNull(cause.endsAtEpochMs)
    }

    // MARK: schedule end math

    @Test
    fun `overnight window late evening ends next morning`() {
        val schedule = Schedule(startMinuteOfDay = 22 * 60, endMinuteOfDay = 6 * 60)
        val now = baseMidnight + 23 * hour // 23:00
        assertEquals(7 * hour, millisUntilScheduleEnd(schedule, now, 0L))
    }

    @Test
    fun `overnight window early morning ends the same morning`() {
        val schedule = Schedule(startMinuteOfDay = 22 * 60, endMinuteOfDay = 6 * 60)
        val now = baseMidnight + 5 * hour // 05:00
        assertEquals(1 * hour, millisUntilScheduleEnd(schedule, now, 0L))
    }

    // MARK: midnight math (limits reset)

    @Test
    fun `open limit blocks until next local midnight`() {
        val now = baseMidnight + 15 * hour // 15:00 local
        val cause = openLimitBlockCause(OpenLimit("com.x", 3, BreakLevel.HARDER), now, 0L)
        assertEquals(BlockScreenReason.OPEN_LIMIT, cause.reason)
        assertEquals(BreakLevel.HARDER, cause.breakLevel) // tier comes from the limit
        assertEquals(now + 9 * hour, cause.endsAtEpochMs)
        assertTrue(cause.spendsOpenOnBreak)
    }

    @Test
    fun `time limit uses the supplied default tier and midnight end`() {
        val now = baseMidnight + 20 * hour
        val cause = timeLimitBlockCause(BreakLevel.HARDCORE, now, 0L)
        assertEquals(BlockScreenReason.TIME_LIMIT, cause.reason)
        assertEquals(BreakLevel.HARDCORE, cause.breakLevel)
        assertEquals(now + 4 * hour, cause.endsAtEpochMs)
        assertFalse(cause.spendsOpenOnBreak)
    }

    @Test
    fun `midnight math respects the utc offset`() {
        // 23:00 UTC at +02:00 is 01:00 local → 23h until local midnight.
        val now = baseMidnight + 23 * hour
        assertEquals(23 * hour, millisUntilNextLocalMidnight(now, 2 * hour))
    }

    // MARK: blockScreenState projection

    private val cause = BlockCause(BlockScreenReason.SCHEDULE, BreakLevel.HARDER, endsAtEpochMs = 1_000_000L)

    @Test
    fun `state carries identity, reason, tier and remaining`() {
        val state = blockScreenState("com.x", "X", cause, BreakSessionState(), nowEpochMs = 400_000L)
        assertEquals("com.x", state.packageName)
        assertEquals("X", state.appLabel)
        assertEquals(BlockScreenReason.SCHEDULE, state.reason)
        assertEquals(BreakLevel.HARDER, state.breakLevel)
        assertEquals(600_000L, state.remainingMillis)
        assertNull(state.breakWaitRemainingMillis)
    }

    @Test
    fun `remaining clamps at zero and stays null when open-ended`() {
        val past = blockScreenState("com.x", null, cause, BreakSessionState(), nowEpochMs = 2_000_000L)
        assertEquals(0L, past.remainingMillis)
        val openEnded = cause.copy(endsAtEpochMs = null)
        assertNull(blockScreenState("com.x", null, openEnded, BreakSessionState(), 0L).remainingMillis)
    }

    @Test
    fun `pending harder request projects the cooldown until it elapses`() {
        val session = BreakSessionState(
            breaksTaken = 1,
            pending = BreakRequestState(requestedAtEpochMs = 100_000L, effectiveAtEpochMs = 160_000L),
        )
        val waiting = blockScreenState("com.x", null, cause, session, nowEpochMs = 130_000L)
        assertEquals(30_000L, waiting.breakWaitRemainingMillis)

        val elapsed = blockScreenState("com.x", null, cause, session, nowEpochMs = 160_000L)
        assertNull(elapsed.breakWaitRemainingMillis) // hold-to-request comes back
    }

    @Test
    fun `cooldown projection only applies to the harder tier`() {
        val session = BreakSessionState(
            pending = BreakRequestState(requestedAtEpochMs = 0L, effectiveAtEpochMs = 999_000L),
        )
        val easy = cause.copy(breakLevel = BreakLevel.EASY)
        val hardcore = cause.copy(breakLevel = BreakLevel.HARDCORE)
        assertNull(blockScreenState("com.x", null, easy, session, 10L).breakWaitRemainingMillis)
        assertNull(blockScreenState("com.x", null, hardcore, session, 10L).breakWaitRemainingMillis)
    }
}
