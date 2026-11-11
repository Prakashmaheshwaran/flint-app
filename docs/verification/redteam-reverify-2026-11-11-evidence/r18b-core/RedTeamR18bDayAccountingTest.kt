package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.AccessClockStamp
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

/**
 * R-18b adversarial re-verification of the daily Time Limit day/reset authority (ADR-012 §9,
 * PC-15/PC-54/PC-55/PC-56/PC-57/PC-58/PC-60). Every test tries to release an exhausted budget
 * (or mint a period) through wall time, the device zone, a reboot or a zero/absent OS sample.
 *
 * All zones and clocks are pinned; nothing depends on the host time zone.
 */
class RedTeamR18bDayAccountingTest {
    private val base = Instant.parse("2026-10-28T23:00:00Z").toEpochMilli()
    private val hour = 3_600_000L
    private val day = 86_400_000L

    /** A same-boot stamp: [elapsed] ms after the boot reference, wall clock free to be edited. */
    private fun boot(elapsed: Long = 0, wall: Long = base + elapsed) = AccessClockStamp(wall, 10_000 + elapsed, "boot")

    /** A different boot: no cross-boot elapsed exists. */
    private fun reboot(elapsed: Long, wall: Long) = AccessClockStamp(wall, 5 + elapsed, "boot-2")

    private fun initial(zone: String = "UTC", at: AccessClockStamp = boot()) =
        TimeLimitDayAccounting.initial(at, zone)

    private fun sample(ms: Long, now: AccessClockStamp, window: Long = base - 23 * hour) =
        TimeLimitUsageSample(window, ms, now)

    private fun record(state: TimeLimitDayState, sample: TimeLimitUsageSample?, now: AccessClockStamp) =
        TimeLimitDayAccounting.record(state, state.periodId, sample, now)

    private fun liveReset(state: TimeLimitDayState, now: AccessClockStamp): Long =
        OpenCreditPolicy().remainingToResetMs(state.reset, now)

    /** The original R-18b reproduction: positive-budget exhaustion plus a forged midnight. */
    @Test fun R18b_zeroSampleForTheNewRawWallDayCannotRefillAnExhaustedPositiveBudget() {
        val spent = record(initial(), sample(60_000, boot()), boot()).state
        assertEquals(0L, TimeLimitDayAccounting.remainingMs(spent, 1))
        // Wall moves two raw days ahead; only one second of monotonic time elapsed. The OS reports
        // nothing for the newly queried raw-day window.
        val jumped = boot(1_000, base + 48 * hour)
        val result = record(spent, sample(0, jumped, window = base + 47 * hour), jumped)
        assertFalse("a wall leap is not a trusted reset", result.rolled)
        assertEquals(spent.periodId, result.state.periodId)
        assertEquals(60_000L, result.state.usedMs)
        assertEquals(0L, TimeLimitDayAccounting.remainingMs(result.state, 1))
        assertEquals(hour - 1_000, liveReset(result.state, jumped))
        assertTrue(result.state.timeUncertain)
    }

    /** No sequence of wall edits inside one boot may advance the period or lower the floor. */
    @Test fun R18b_wallTimeAloneNeverRollsThePeriodOrLowersTheFloor() {
        val spent = record(initial(), sample(60_000, boot()), boot()).state
        val deltas = listOf(-30 * day, -2 * day, -hour, -1L, 1L, hour, 12 * hour, day, 2 * day, 30 * day)
        for (delta in deltas) {
            val jumped = boot(1_000, base + delta)
            val window = (base + delta - hour).coerceAtLeast(0)
            val next = record(spent, sample(0, jumped, window), jumped).state
            assertEquals("wall delta $delta", spent.periodId, next.periodId)
            assertEquals("wall delta $delta", 60_000L, next.usedMs)
            assertEquals("wall delta $delta", 0L, TimeLimitDayAccounting.remainingMs(next, 1))
        }
    }

    /** A zero OS total, a shrinking OS total and a new raw bucket never refund the durable floor. */
    @Test fun R18b_osSumDroppingToZeroNeverLowersTheDurableFloor() {
        var state = record(initial(), sample(60_000, boot()), boot()).state
        // Same raw bucket shrinking to zero and staying there.
        state = record(state, sample(0, boot(1_000)), boot(1_000)).state
        assertEquals(60_000L, state.usedMs)
        state = record(state, sample(0, boot(2_000)), boot(2_000)).state
        assertEquals(60_000L, state.usedMs)
        // A changed raw bucket (the OS re-bucketed after a clock change) is another floor, not a refill.
        val jumped = boot(3_000, base + 48 * hour)
        state = record(state, sample(0, jumped, window = base + 47 * hour), jumped).state
        assertEquals(60_000L, state.usedMs)
        assertEquals(0L, TimeLimitDayAccounting.remainingMs(state, 1))
    }

    /** An observed (independent) floor above the OS sum survives the OS sum collapsing to zero. */
    @Test fun R18b_observedFloorAboveTheOsSumSurvivesAnOsDropToZero() {
        var state = TimeLimitDayAccounting.observe(TimeLimitDayAccounting.alignObserved(initial(), 0), 60_000)
        assertEquals(60_000L, state.usedMs)
        val jumped = boot(1_000, base + 48 * hour)
        state = record(state, sample(0, jumped, window = base + 47 * hour), jumped).state
        assertEquals(60_000L, state.usedMs)
        assertEquals(60_000L, state.observedUsedMs)
        assertEquals(0L, TimeLimitDayAccounting.remainingMs(state, 1))
    }

    /** Reboot: no cross-boot elapsed exists, so the saved floor and the saved remaining-to-reset hold. */
    @Test fun R18b_rebootThenWallLeapKeepsTheSavedFloorAndTheSavedResetBound() {
        val spent = record(initial(), sample(60_000, boot()), boot()).state
        val atBoot = reboot(0, base + 7 * day)
        val resumed = TimeLimitDayAccounting.reconcile(spent, atBoot)
        assertFalse(resumed.rolled)
        assertEquals(spent.periodId, resumed.state.periodId)
        assertEquals(60_000L, resumed.state.usedMs)
        assertEquals(0L, TimeLimitDayAccounting.remainingMs(resumed.state, 1))
        // The §9 fallback resumes exactly the saved remainder: it never refills and never expires early.
        assertEquals(hour, resumed.state.reset.remainingToResetMs)
        assertTrue(resumed.state.timeUncertain)
        val zero = record(resumed.state,
            sample(0, atBoot, window = base + 7 * day - 23 * hour), atBoot).state
        assertEquals(60_000L, zero.usedMs)
        // Just inside the saved remainder: still exhausted, still one period.
        val nearly = reboot(hour - 1, base + 8 * day - 1_000)
        assertFalse(TimeLimitDayAccounting.reconcile(zero, nearly).rolled)
        assertEquals(60_000L, TimeLimitDayAccounting.reconcile(zero, nearly).state.usedMs)
        assertEquals(0L, TimeLimitDayAccounting.remainingMs(TimeLimitDayAccounting.reconcile(zero, nearly).state, 1))
        // Exactly the saved remainder: one reset, never replayed.
        val boundary = reboot(hour, base + 8 * day)
        assertTrue(TimeLimitDayAccounting.reconcile(zero, boundary).rolled)
        assertEquals(spent.periodId + 1, TimeLimitDayAccounting.reconcile(zero, boundary).state.periodId)
        assertFalse(TimeLimitDayAccounting.reconcile(zero,
            reboot(hour + 1, base + 9 * day)).state.periodId > spent.periodId + 1)
    }

    /** PC-56: a device-zone change is display only; the saved zone and its boundary own the reset. */
    @Test fun R18b_deviceZoneChangeCannotMoveTheSavedZoneOrMintAPeriod() {
        val spent = record(initial(), sample(60_000, boot()), boot()).state
        // Wherever the device travels, reconcile() has no device-zone input at all.
        val next = TimeLimitDayAccounting.reconcile(spent, boot(1_000, base + 13 * hour)).state
        assertEquals("UTC", next.zoneId)
        assertEquals(spent.periodId, next.periodId)
        assertEquals(60_000L, next.usedMs)
        assertEquals(hour - 1_000, liveReset(next, boot(1_000, base + 13 * hour)))
        assertEquals("travel must not become a reset", 0L, TimeLimitDayAccounting.remainingMs(next, 1))
        // An eastward or westward saved-zone edit still waits for the existing trusted boundary.
        for (travel in listOf("Asia/Tokyo", "Pacific/Auckland", "America/Los_Angeles", "GMT+14:00")) {
            val pending = TimeLimitDayAccounting.reconcile(spent, boot(1_000, base + 13 * hour), travel)
            assertFalse("$travel", pending.rolled)
            assertEquals("$travel", "UTC", pending.state.zoneId)
            assertEquals("$travel", 60_000L, pending.state.usedMs)
            assertEquals("$travel", 0L, TimeLimitDayAccounting.remainingMs(pending.state, 1))
        }
    }

    /** A saved-zone edit at the trusted boundary selects the next midnight in the new zone, once. */
    @Test fun R18b_savedZoneEditAppliesAtTheTrustedBoundaryAndRollsExactlyOnce() {
        val spent = record(initial(), sample(60_000, boot()), boot()).state
        val boundary = boot(hour)
        val rolled = TimeLimitDayAccounting.reconcile(spent, boundary, "Asia/Tokyo")
        assertTrue(rolled.rolled)
        assertEquals("Asia/Tokyo", rolled.state.zoneId)
        assertEquals(spent.periodId + 1, rolled.state.periodId)
        assertEquals(0L, rolled.state.usedMs)
        assertEquals(15 * hour, liveReset(rolled.state, boundary))
        val tick = boot(hour + 1)
        assertFalse(TimeLimitDayAccounting.reconcile(rolled.state, tick, "Asia/Tokyo").rolled)
        assertEquals(15 * hour - 1, liveReset(TimeLimitDayAccounting.reconcile(rolled.state, tick, "Asia/Tokyo").state, tick))
    }

    /** PC-57/§9: a 25-hour fall-back day is not treated as a 24-hour day. */
    @Test fun R18b_dstFoldDayDoesNotReleaseTheLimitAnHourEarly() {
        // America/New_York 2026-11-01: local midnight is 04:00Z; the next local midnight is 05:00Z (25 h).
        val foldDay = AccessClockStamp(Instant.parse("2026-11-01T04:00:00Z").toEpochMilli(), 1_000, "boot")
        val start = TimeLimitDayAccounting.initial(foldDay, "America/New_York")
        assertEquals(25 * hour, start.reset.remainingToResetMs)
        val spent = record(start, sample(60_000, foldDay, window = foldDay.wallEpochMs - hour), foldDay).state
        // A raw 24-hour day plus a wall leap across the fold must not roll it.
        val raw24 = AccessClockStamp(foldDay.wallEpochMs + 24 * hour, foldDay.elapsedRealtimeMs + 1_000, "boot")
        val early = record(spent, sample(0, raw24, window = foldDay.wallEpochMs + 23 * hour), raw24)
        assertFalse("24 raw hours is not this day's end", early.rolled)
        assertEquals(60_000L, early.state.usedMs)
        assertEquals(0L, TimeLimitDayAccounting.remainingMs(early.state, 1))
        assertEquals(25 * hour - 1_000, liveReset(early.state, raw24))
        // Exactly one reset when the 25th hour does elapse.
        val end = AccessClockStamp(foldDay.wallEpochMs + 25 * hour, foldDay.elapsedRealtimeMs + 25 * hour, "boot")
        val rolled = TimeLimitDayAccounting.reconcile(early.state, end)
        assertTrue(rolled.rolled)
        assertEquals(spent.periodId + 1, rolled.state.periodId)
        assertEquals(24 * hour, liveReset(rolled.state, end))
    }

    /** PC-57/§9: a 23-hour spring-forward day releases at its own boundary, not a raw 24 h later. */
    @Test fun R18b_dstGapDayUsesItsShortBoundaryWithoutDoubleRolling() {
        // America/New_York 2026-03-08: local midnight 05:00Z; next local midnight 04:00Z (23 h).
        val gapDay = AccessClockStamp(Instant.parse("2026-03-08T05:00:00Z").toEpochMilli(), 1_000, "boot")
        val start = TimeLimitDayAccounting.initial(gapDay, "America/New_York")
        assertEquals(23 * hour, start.reset.remainingToResetMs)
        val spent = record(start, sample(60_000, gapDay, window = gapDay.wallEpochMs - hour), gapDay).state
        val end = AccessClockStamp(gapDay.wallEpochMs + 23 * hour, gapDay.elapsedRealtimeMs + 23 * hour, "boot")
        // The trusted remaining is 23 h even though 24 monotonic hours is a "full day" elsewhere.
        val notYet = AccessClockStamp(gapDay.wallEpochMs + 23 * hour - 1_000, gapDay.elapsedRealtimeMs + 23 * hour - 1_000, "boot")
        assertFalse(TimeLimitDayAccounting.reconcile(spent, notYet).rolled)
        assertEquals(0L, TimeLimitDayAccounting.remainingMs(TimeLimitDayAccounting.reconcile(spent, notYet).state, 1))
        val rolled = TimeLimitDayAccounting.reconcile(spent, end)
        assertTrue(rolled.rolled)
        assertEquals(spent.periodId + 1, rolled.state.periodId)
        assertEquals(24 * hour, liveReset(rolled.state, end))
        // A following wall edit cannot immediately roll the new day again.
        val jump = end.copy(wallEpochMs = end.wallEpochMs + 2 * day, elapsedRealtimeMs = end.elapsedRealtimeMs + 1)
        assertFalse(TimeLimitDayAccounting.reconcile(rolled.state, jump).rolled)
    }

    /** (h) Clock rolled backwards then forwards again: one reset per elapsed period, never two. */
    @Test fun R18b_backwardThenForwardWallLeapsCannotMintASecondReset() {
        val spent = record(initial(), sample(60_000, boot()), boot()).state
        val back = boot(1_000, base - 3 * day)
        val afterBack = record(spent, sample(0, back, window = (base - 3 * day - hour).coerceAtLeast(0)), back).state
        assertEquals(spent.periodId, afterBack.periodId)
        assertEquals(60_000L, afterBack.usedMs)
        // Forward again, still far inside the same trusted period.
        val forward = boot(2_000, base + 5 * day)
        val afterForward = record(afterBack, sample(0, forward, window = base + 5 * day - hour), forward).state
        assertEquals(spent.periodId, afterForward.periodId)
        assertEquals(0L, TimeLimitDayAccounting.remainingMs(afterForward, 1))
        // Only the elapsed minute rolls it, once.
        val boundary = boot(hour + 2_000, base + 5 * day)
        val rolled = TimeLimitDayAccounting.reconcile(afterForward, boundary)
        assertTrue(rolled.rolled)
        assertEquals(spent.periodId + 1, rolled.state.periodId)
        assertFalse(TimeLimitDayAccounting.reconcile(rolled.state,
            boot(hour + 2_001, base + 6 * day)).rolled)
    }

    /** A stale ticket from the old period cannot charge (or refund) the new one. */
    @Test fun R18b_staleTicketForTheOldPeriodCannotRefundTheNewDay() {
        val spent = record(initial(), sample(60_000, boot()), boot()).state
        val boundary = boot(hour)
        val rolled = TimeLimitDayAccounting.reconcile(spent, boundary)
        assertTrue(rolled.rolled)
        val stale = TimeLimitDayAccounting.record(rolled.state, spent.periodId, sample(0, boundary), boundary)
        assertFalse(stale.sampleAccepted)
        assertEquals(0L, stale.state.usedMs)
        assertEquals(spent.periodId + 1, stale.state.periodId)
    }

    /** Zero budget is exhausted before and after every wall/zone/reboot variation. */
    @Test fun R18b_zeroBudgetStaysExhaustedThroughWallZoneAndReboot() {
        val zero = record(initial(), null, boot()).state
        assertEquals(0L, TimeLimitDayAccounting.remainingMs(zero, 0))
        val jump = boot(1_000, base + 2 * day)
        for ((label, next) in listOf(
            "wall" to record(zero, null, jump).state,
            "reboot" to TimeLimitDayAccounting.reconcile(zero, reboot(0, base - 3 * day)).state,
            "zone" to TimeLimitDayAccounting.reconcile(zero, jump, "Pacific/Auckland").state)) {
            assertEquals(label, 0L, TimeLimitDayAccounting.remainingMs(next, 0))
            assertTrue("$label remaining-to-reset", liveReset(next, jump) > 0)
        }
    }
}
