package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.AccessClockStamp
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class TimeLimitDayAccountingTest {
    private val base = Instant.parse("2026-10-28T23:00:00Z").toEpochMilli()
    private val hour = 3_600_000L
    private fun at(ms: Long = 0, wall: Long = ms, boot: String = "boot") = AccessClockStamp(base + wall, 10_000 + ms, boot)
    private fun initial() = TimeLimitDayAccounting.initial(at(), "UTC")
    private fun sample(ms: Long, now: AccessClockStamp = at(), window: Long = base - 23 * hour) = TimeLimitUsageSample(window, ms, now)
    private fun record(state: TimeLimitDayState, sample: TimeLimitUsageSample?, now: AccessClockStamp = at()) =
        TimeLimitDayAccounting.record(state, state.periodId, sample, now).state

    @Test fun PC_54_PC_56_PC_60_newOrPreviouslyUnderBudgetTargetsCannotClaimUnusedQuotaAfterAClockJumpOrReboot() {
        for (current in listOf(initial(), record(initial(), sample(30_000)))) {
            for (boot in listOf("boot", "next-boot")) {
                val changed = at(1_000, 48 * hour, boot)
                val after = record(current, sample(0, changed, base + 47 * hour), changed)
                assertTrue(after.accountingUnknownUntilReset)
                assertEquals(current.periodId, after.periodId)
                assertTrue(after.usedMs >= current.usedMs)
                assertNull(TimeLimitDayAccounting.remainingMs(after, 1))
                val end = changed.copy(wallEpochMs = changed.wallEpochMs + after.reset.remainingToResetMs,
                    elapsedRealtimeMs = changed.elapsedRealtimeMs + after.reset.remainingToResetMs)
                val reset = TimeLimitDayAccounting.reconcile(after, end)
                assertTrue(reset.rolled)
                assertFalse(reset.state.accountingUnknownUntilReset)
            }
        }
    }

    @Test fun PC_54_forwardWallMidnightCannotRefillAnExhaustedPositiveBudget() {
        val spent = record(initial(), sample(60_000))
        val jumped = at(1_000, 48 * hour)
        val after = record(spent, sample(0, jumped, base + 47 * hour), jumped)
        assertEquals(spent.periodId, after.periodId)
        assertEquals(60_000L, after.usedMs)
        assertEquals(0L, TimeLimitDayAccounting.remainingMs(after, 1))
        assertEquals(hour - 1_000, after.reset.remainingToResetMs)
    }

    @Test fun PC_54_newRawBucketAddsUsageInsteadOfRefundingAPartialBudget() {
        val first = record(initial(), sample(50 * 60_000))
        val jumped = at(1_000, 48 * hour)
        val zero = record(first, sample(0, jumped, base + 47 * hour), jumped)
        val later = at(601_000, 48 * hour + 600_000)
        val spent = record(zero, sample(10 * 60_000, later, base + 47 * hour), later)
        assertEquals(60 * 60_000L, spent.usedMs)
        assertEquals(0L, TimeLimitDayAccounting.remainingMs(spent, 60))
    }

    @Test fun PC_55_backwardJumpInsideTheSameRawBucketCannotReplaceConsumedUsage() {
        val first = record(initial(), sample(60_000))
        val rollback = at(1_000, -hour)
        val second = record(first, sample(0, rollback), rollback)
        assertEquals(first.usedMs, second.usedMs)
        assertEquals(first.periodId, second.periodId)
        assertEquals(hour - 1_000, second.reset.remainingToResetMs)
    }

    @Test fun PC_56_deviceTravelCannotMoveTheSavedZoneOrItsTrustedBoundary() {
        val old = initial()
        val shifted = at(1_000, 13 * hour)
        val next = TimeLimitDayAccounting.reconcile(old, shifted).state
        assertEquals("UTC", next.zoneId)
        assertEquals(old.periodId, next.periodId)
        assertEquals(hour - 1_000, next.reset.remainingToResetMs)
    }

    @Test fun PC_54_PC_60_onlyTrustedElapsedMidnightRefillsOnceAndRejectsStaleReports() {
        val spent = record(initial(), sample(60_000))
        val end = at(hour, 3 * 24 * hour)
        val result = TimeLimitDayAccounting.record(spent, spent.periodId, sample(0, end), end)
        assertTrue(result.rolled)
        assertFalse(result.sampleAccepted)
        assertEquals(spent.periodId + 1, result.state.periodId)
        assertEquals(0L, result.state.usedMs)
        assertFalse(TimeLimitDayAccounting.reconcile(result.state, end).rolled)
        assertEquals(60_000L, TimeLimitDayAccounting.remainingMs(result.state, 1))
    }

    @Test fun PC_60_rebootRetainsSpendAndResumesSavedResetWithoutPoweredOffCredit() {
        val spent = record(initial(), sample(60_000))
        val reboot = at(5, 7 * 24 * hour, "new-boot")
        val next = TimeLimitDayAccounting.reconcile(spent, reboot).state
        assertEquals(spent.periodId, next.periodId)
        assertEquals(spent.usedMs, next.usedMs)
        assertEquals(hour, next.reset.remainingToResetMs)
        assertTrue(next.timeUncertain)
        assertFalse(TimeLimitDayAccounting.reconcile(next, at(5 + hour - 1, 8 * 24 * hour, "new-boot")).rolled)
        assertTrue(TimeLimitDayAccounting.reconcile(next, at(5 + hour, 8 * 24 * hour, "new-boot")).rolled)
    }

    @Test fun PC_54_PC_60_trustedRolloverRetainsTheRawBucketBaseline() {
        val old = record(initial(), sample(60_000))
        val now = at(hour)
        val rolled = TimeLimitDayAccounting.reconcile(old, now).state
        val after = record(rolled, sample(65_000, now), now)
        assertEquals(5_000L, after.usedMs)
    }

    @Test fun PC_54_duplicateLowerAndOutOfOrderMeasurementsNeverRefundOrDoubleCount() {
        val old = record(initial(), sample(60_000))
        assertEquals(old, record(old, sample(60_000)))
        val later = at(1_000)
        val lower = record(old, sample(5_000, later), later)
        assertEquals(old.usedMs, lower.usedMs)
        val stale = TimeLimitDayAccounting.record(lower, lower.periodId, sample(120_000, at()), later)
        assertFalse(stale.sampleAccepted)
        assertEquals(old.usedMs, stale.state.usedMs)
    }

    @Test fun PC_61_missingMeasurementNeverWritesAnArtificialSpentOrZeroUsedValue() {
        val used = record(initial(), sample(30_000))
        val result = TimeLimitDayAccounting.record(used, used.periodId, null, at(1_000))
        assertFalse(result.sampleAccepted)
        assertEquals(30_000L, result.state.usedMs)
    }

    @Test fun PC_56_savedZoneMidnightsRespectShortAndLongDstDays() {
        for ((date, hours) in listOf("2026-03-08T05:00:00Z" to 23, "2026-11-01T04:00:00Z" to 25)) {
            val clock = AccessClockStamp(Instant.parse(date).toEpochMilli(), 1, "boot")
            val day = TimeLimitDayAccounting.initial(clock, "America/New_York")
            assertEquals(hours * hour, day.reset.remainingToResetMs)
        }
    }

    @Test fun PC_56_templateZoneChangeWaitsForTheExistingTrustedBoundary() {
        val old = record(initial(), sample(60_000))
        val pending = TimeLimitDayAccounting.reconcile(old, at(1_000), "Asia/Tokyo")
        assertFalse(pending.rolled)
        assertEquals("UTC", pending.state.zoneId)
        assertEquals(60_000L, pending.state.usedMs)
        assertEquals(hour - 1_000, pending.state.reset.remainingToResetMs)
        val next = TimeLimitDayAccounting.reconcile(pending.state, at(hour), "Asia/Tokyo")
        assertTrue(next.rolled)
        assertEquals("Asia/Tokyo", next.state.zoneId)
        assertEquals(old.periodId + 1, next.state.periodId)
        assertEquals(15 * hour, next.state.reset.remainingToResetMs)
        assertEquals(0L, next.state.usedMs)
    }

    @Test fun PC_70_importedLargePositiveBudgetsRemainMeasurableWithoutOverflow() {
        assertEquals(Int.MAX_VALUE * 60_000L, TimeLimitDayAccounting.remainingMs(record(initial(), sample(0)), Int.MAX_VALUE))
        val used = record(initial(), sample(60_000))
        assertEquals(2_000 * 60_000L - 60_000, TimeLimitDayAccounting.remainingMs(used, 2_000))
    }

    @Test fun PC_54_PC_60_unknownLegacyHistoryCannotBecomeUnusedFromANewRawDayZero() {
        assertNull(TimeLimitDayAccounting.remainingMs(initial(), 1))
        assertEquals(0L, TimeLimitDayAccounting.remainingMs(initial(), 0))
        val old = initial().copy(accountingUnknownUntilReset = true)
        val jumped = at(1_000, 48 * hour)
        val observedZero = record(old, sample(0, jumped, base + 47 * hour), jumped)
        assertEquals(0L, observedZero.usedMs)
        assertNull(TimeLimitDayAccounting.remainingMs(observedZero, 1))
        assertTrue(observedZero.accountingUnknownUntilReset)
        val end = at(hour, 48 * hour + hour - 1_000)
        val reset = TimeLimitDayAccounting.reconcile(observedZero, end)
        assertTrue(reset.rolled)
        assertFalse(reset.state.accountingUnknownUntilReset)
        assertEquals(60_000L, TimeLimitDayAccounting.remainingMs(reset.state, 1))
        assertEquals(observedZero.periodId + 1, reset.state.periodId)
    }

    @Test fun PC_60_unknownHistorySurvivesRebootButNewExhaustionRemainsEnforceable() {
        val old = record(initial().copy(accountingUnknownUntilReset = true), sample(30_000))
        val reboot = at(5, 7 * 24 * hour, "new-boot")
        val zero = record(old, sample(0, reboot, base + 7 * 24 * hour), reboot)
        assertTrue(zero.accountingUnknownUntilReset)
        assertEquals(hour, zero.reset.remainingToResetMs)
        assertEquals(30_000L, zero.usedMs)
        assertNull(TimeLimitDayAccounting.remainingMs(zero, 1))
        val later = at(1_005, 7 * 24 * hour + 1_000, "new-boot")
        val spent = record(zero, sample(30_000, later, base + 7 * 24 * hour), later)
        assertEquals(60_000L, spent.usedMs)
        assertEquals(0L, TimeLimitDayAccounting.remainingMs(spent, 1))
        assertTrue(spent.accountingUnknownUntilReset)
        assertFalse(TimeLimitDayAccounting.reconcile(spent, at(5 + hour - 1, 7 * 24 * hour + hour - 6, "new-boot")).rolled)
    }

    @Test(expected = IllegalArgumentException::class)
    fun PC_71_futureMeasurementWindowIsInvalidEvidence() {
        record(initial(), sample(1, window = base + 1))
    }
}
