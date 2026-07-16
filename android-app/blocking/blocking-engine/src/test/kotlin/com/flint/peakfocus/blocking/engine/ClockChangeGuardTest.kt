package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.BlockTargets
import com.flint.peakfocus.core.model.BreakRequestState
import com.flint.peakfocus.core.model.BreakSessionState
import com.flint.peakfocus.core.model.EmergencyPassState
import com.flint.peakfocus.core.model.OpenCountState
import com.flint.peakfocus.core.model.OpenLimit
import com.flint.peakfocus.core.model.OpenLimitDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage of the shift-driven half of the time-change guard. The always-on
 * backward guards live in OpenLimitPolicy/EmergencyPassPolicy and are covered in their
 * own test classes; the receiver that measures shifts is framework code, not tested here.
 */
class ClockChangeGuardTest {

    private val guard = ClockChangeGuard()
    private val insta = "com.instagram.android"
    private val today = 20637L // Fri 2026-07-03
    private val weekStart = 20633L // Mon 2026-06-29, the ISO week containing `today`

    /** Noon (local) on a given epoch-day, as epoch millis with zero UTC offset. */
    private fun noonOf(epochDay: Long): Long = epochDay * DAY_MS + 12 * HOUR_MS

    /** Today's quota fully consumed: 3 opens of [insta] recorded. */
    private fun consumed() = OpenCountState(epochDay = today, opensByPackage = mapOf(insta to 3))

    private fun shift(oldEpochMs: Long, newEpochMs: Long, oldOffset: Long = 0L, newOffset: Long = 0L) =
        ClockShift(oldEpochMs, newEpochMs, oldOffset, newOffset)

    // MARK: open counts — backward jump

    @Test
    fun backwardJumpLeavesConsumedOpensUntouched() {
        val state = consumed()
        val backADay = shift(oldEpochMs = noonOf(today), newEpochMs = noonOf(today - 1))
        assertEquals(state, guard.guardedOpenCounts(state, backADay))
    }

    // MARK: open counts — forward jump

    @Test
    fun forwardJumpCarriesConsumedOpensInsteadOfResetting() {
        val jumped = shift(oldEpochMs = noonOf(today), newEpochMs = noonOf(today + 1))
        val guarded = guard.guardedOpenCounts(consumed(), jumped)
        assertEquals(today + 1, guarded.epochDay)
        assertEquals(mapOf(insta to 3), guarded.opensByPackage)
        // …so the quota stays exhausted on the manipulated "new day":
        val decision = OpenLimitPolicy().decide(
            insta, guarded, listOf(OpenLimit(insta, dailyOpens = 3)), today + 1,
        )
        assertTrue(decision is OpenLimitDecision.Blocked)
    }

    @Test
    fun sameDayNudgeIsANoOp() {
        val state = consumed()
        val nudge = shift(oldEpochMs = noonOf(today), newEpochMs = noonOf(today) + 5 * 60_000L)
        assertEquals(state, guard.guardedOpenCounts(state, nudge))
    }

    // MARK: open counts — day-boundary edge

    @Test
    fun twoMinuteNudgeAcrossMidnightStillCarriesTheCounts() {
        // 23:59 → clock set 2 minutes ahead → 00:01 of the next day. TIME_SET never fires
        // for genuine midnight passage, so any set-forward day advance is conservative.
        val beforeMidnight = (today + 1) * DAY_MS - 60_000L
        val nudged = shift(oldEpochMs = beforeMidnight, newEpochMs = beforeMidnight + 120_000L)
        val guarded = guard.guardedOpenCounts(consumed(), nudged)
        assertEquals(today + 1, guarded.epochDay)
        assertEquals(3, guarded.opensFor(insta))
    }

    // MARK: open counts — timezone hops

    @Test
    fun eastwardTimezoneHopDoesNotGrantAFreshDay() {
        // 23:00 local at UTC+0; hopping to UTC+3 makes it 02:00 "tomorrow" — same instant.
        val lateEvening = today * DAY_MS + 23 * HOUR_MS
        val hop = shift(lateEvening, lateEvening, oldOffset = 0L, newOffset = 3 * HOUR_MS)
        val guarded = guard.guardedOpenCounts(consumed(), hop)
        assertEquals(today + 1, guarded.epochDay)
        assertEquals(mapOf(insta to 3), guarded.opensByPackage)
    }

    @Test
    fun westwardTimezoneHopLeavesTheStateAlone() {
        // 00:30 local at UTC+0; hopping to UTC-2 lands at 22:30 "yesterday" — a backward day.
        val state = consumed()
        val justAfterMidnight = today * DAY_MS + 30 * 60_000L
        val hop = shift(justAfterMidnight, justAfterMidnight, oldOffset = 0L, newOffset = -2 * HOUR_MS)
        assertEquals(state, guard.guardedOpenCounts(state, hop))
    }

    @Test
    fun uninitializedStatesPassThroughUntouched() {
        val jumped = shift(noonOf(today), noonOf(today + 7))
        assertEquals(OpenCountState(), guard.guardedOpenCounts(OpenCountState(), jumped))
        assertEquals(EmergencyPassState(), guard.guardedEmergencyPass(EmergencyPassState(), jumped))
    }

    // MARK: emergency pass

    @Test
    fun forwardWeekJumpKeepsASpentPassSpent() {
        val spentAt = noonOf(today)
        val spent = EmergencyPassState(weekStartEpochDay = weekStart, usedAtEpochMs = spentAt)
        val jumped = shift(oldEpochMs = spentAt, newEpochMs = noonOf(today + 7))
        val guarded = guard.guardedEmergencyPass(spent, jumped)
        assertEquals(weekStart + 7, guarded.weekStartEpochDay)
        assertEquals(spentAt, guarded.usedAtEpochMs)
        // …and the policy agrees: no pass available in the manipulated "new week".
        assertFalse(EmergencyPassPolicy().isAvailable(guarded, noonOf(today + 7)))
    }

    @Test
    fun backwardWeekJumpLeavesThePassStateAlone() {
        val spent = EmergencyPassState(weekStartEpochDay = weekStart, usedAtEpochMs = noonOf(today))
        val back = shift(oldEpochMs = noonOf(today), newEpochMs = noonOf(today - 7))
        assertEquals(spent, guard.guardedEmergencyPass(spent, back))
    }

    @Test
    fun unspentPassIsRekeyedButStaysAvailable() {
        val unspent = EmergencyPassState(weekStartEpochDay = weekStart, usedAtEpochMs = null)
        val jumped = shift(noonOf(today), noonOf(today + 7))
        val guarded = guard.guardedEmergencyPass(unspent, jumped)
        assertEquals(weekStart + 7, guarded.weekStartEpochDay)
        assertTrue(EmergencyPassPolicy().isAvailable(guarded, noonOf(today + 7)))
    }

    // MARK: HARDER break friction

    @Test
    fun forwardJumpCannotFastForwardAPendingHarderWait() {
        val requestedAt = noonOf(today)
        val pending = BreakRequestState(requestedAt, requestedAt + 5 * 60_000L) // 5-min wait
        val session = BreakSessionState(breaksTaken = 1, pending = pending)
        val oldNow = requestedAt + 60_000L // 1 min in: 4 min remain
        val newNow = oldNow + 2 * HOUR_MS // clock set 2 h ahead — wait would have "elapsed"
        val guarded = guard.guardedBreakSession(session, shift(oldNow, newNow))
        val shifted = guarded.pending!!
        assertFalse(shifted.isElapsed(newNow))
        // The remaining real wait is invariant under the jump.
        assertEquals(pending.effectiveAtEpochMs - oldNow, shifted.effectiveAtEpochMs - newNow)
        assertEquals(1, guarded.breaksTaken)
    }

    @Test
    fun backwardJumpDoesNotStretchAPendingHarderWait() {
        val requestedAt = noonOf(today)
        val pending = BreakRequestState(requestedAt, requestedAt + 5 * 60_000L)
        val session = BreakSessionState(pending = pending)
        val oldNow = requestedAt + 60_000L
        val newNow = oldNow - 3 * HOUR_MS
        val shifted = guard.guardedBreakSession(session, shift(oldNow, newNow)).pending!!
        assertEquals(pending.effectiveAtEpochMs - oldNow, shifted.effectiveAtEpochMs - newNow)
    }

    @Test
    fun sessionsWithoutAPendingRequestAreUntouched() {
        val session = BreakSessionState(breaksTaken = 2, pending = null)
        val jumped = shift(noonOf(today), noonOf(today + 1))
        assertEquals(session, guard.guardedBreakSession(session, jumped))
    }

    @Test
    fun pureTimezoneHopNeverMovesEpochBasedBreakTimestamps() {
        val requestedAt = noonOf(today)
        val session = BreakSessionState(pending = BreakRequestState(requestedAt, requestedAt + 60_000L))
        val hop = shift(requestedAt, requestedAt, oldOffset = 0L, newOffset = 3 * HOUR_MS)
        assertEquals(session, guard.guardedBreakSession(session, hop))
    }

    // MARK: shift measurement (ClockShift factories)

    @Test
    fun fromAnchorProjectsTheOldTimeViaElapsedRealtime() {
        // Anchored at wall 1_000_000 / elapsed 50_000; 90 s of real time pass; the clock now
        // reads one hour more than it should — that hour is the injected jump.
        val measured = ClockShift.fromAnchor(
            anchorWallEpochMs = 1_000_000L,
            anchorElapsedRealtimeMs = 50_000L,
            nowWallEpochMs = 1_000_000L + 90_000L + HOUR_MS,
            nowElapsedRealtimeMs = 140_000L,
            oldUtcOffsetMs = 0L,
            newUtcOffsetMs = 0L,
        )
        assertEquals(1_090_000L, measured.oldEpochMs)
        assertEquals(HOUR_MS, measured.wallDeltaMs)
    }

    @Test
    fun fromAnchorAcrossARebootReportsNoMeasurableJump() {
        // elapsedRealtime restarted (now 5_000 < anchor 999_999): the anchor is unusable —
        // fall back to "zone/day key only", never an invented wall delta.
        val measured = ClockShift.fromAnchor(
            anchorWallEpochMs = 1_000_000L,
            anchorElapsedRealtimeMs = 999_999L,
            nowWallEpochMs = 2_000_000L,
            nowElapsedRealtimeMs = 5_000L,
            oldUtcOffsetMs = HOUR_MS,
            newUtcOffsetMs = 0L,
        )
        assertEquals(0L, measured.wallDeltaMs)
        assertEquals(2_000_000L, measured.newEpochMs)
        assertEquals(HOUR_MS, measured.oldUtcOffsetMs) // offsets still carried for the day math
    }

    // MARK: one-shot session rules — expiry is duration-anchored across clock edits

    @Test
    fun sessionExpiryMovesWithTheInjectedJumpInBothDirections() {
        val session = BlockRule(
            id = "session-1",
            name = "Focus session",
            targets = BlockTargets(),
            expiresAtEpochMs = 1_000_000L + HOUR_MS,
        )
        // Set forward 3h: the session keeps its remaining hour instead of ending instantly.
        val forward = guard.guardedSessionRules(listOf(session), shift(1_000_000L, 1_000_000L + 3 * HOUR_MS))
        assertEquals(1_000_000L + 4 * HOUR_MS, forward.single().expiresAtEpochMs)
        // Set back 2h: the session is not stretched by the setback.
        val backward = guard.guardedSessionRules(listOf(session), shift(1_000_000L, 1_000_000L - 2 * HOUR_MS))
        assertEquals(1_000_000L - HOUR_MS, backward.single().expiresAtEpochMs)
    }

    @Test
    fun permanentRulesAndZeroDeltaShiftsAreUntouched() {
        val permanent = BlockRule(id = "r1", name = "Rule", targets = BlockTargets())
        val session = permanent.copy(id = "session-1", expiresAtEpochMs = 5_000L)
        // Permanent rules never shift; pure zone hops / unmeasured shifts change nothing.
        assertEquals(emptyList<BlockRule>(), guard.guardedSessionRules(listOf(permanent), shift(0L, HOUR_MS)))
        assertEquals(
            emptyList<BlockRule>(),
            guard.guardedSessionRules(listOf(session, permanent), shift(1_000L, 1_000L, 0L, HOUR_MS)),
        )
    }

    private companion object {
        const val DAY_MS = 86_400_000L
        const val HOUR_MS = 3_600_000L
    }
}
