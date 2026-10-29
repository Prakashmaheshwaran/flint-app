package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.blocking.engine.*
import com.flint.peakfocus.core.datastore.*
import com.flint.peakfocus.core.model.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class TargetLimitProjectionTest {
    private var now = AccessClockStamp(1_800_000_000_000L, 10_000, "boot")
    private val pkg = "selected.app"
    private fun snapshot(time: TimeLimit? = TimeLimit(pkg, 5, BreakLevel.HARDCORE),
        open: OpenLimit? = OpenLimit(pkg, 4, BreakLevel.HARDER),
        quota: OpenCreditLedger? = OpenCreditLedgerPolicy.initial(now, "UTC", mapOf(pkg to 2)),
        periodMs: Long = quota?.reset?.remainingToResetMs ?: 60_000): ProtectionSnapshot {
        val interval = AnchoredInterval(now, periodMs, now.wallEpochMs + periodMs)
        val causes = buildMap {
            if (time != null) put(timeCauseKey(pkg), EditorCause("time-period", interval, "UTC"))
            if (open != null) put(openCauseKey(pkg), EditorCause("open-period", interval, "UTC"))
        }
        return ProtectionSnapshot(FocusSnapshot(defaultBreakLevel = BreakLevel.EASY), emptyList(),
            listOfNotNull(time), listOfNotNull(open), EditorCommitmentState(causes = causes),
            HarderWaitPolicy.Ledger(), now, quota)
    }

    @Test fun timeTierComesFromItsStoredRecordAndBelowQuotaRemainsAnActiveCommitment() {
        val result = TargetLimitProjection.app(snapshot(open = null), pkg, now, 1_000)
        assertEquals(TimeLimitView(300_000, 1_000, BreakLevel.HARDCORE, true), result.timeLimit)
        val cause = result.causes.single()
        assertTrue(cause.active)
        assertEquals(false, cause.enforcingNow)
        assertEquals(BreakLevel.HARDCORE, cause.tier)
        assertNull(result.unavailableReason)
    }

    @Test fun positiveUnmeasurableUsageIsUnknownWhileZeroQuotaNeedsNoUsageAccess() {
        val stored = snapshot(open = null)
        val unknown = TargetLimitProjection.app(stored, pkg, now, null)
        assertFalse(unknown.timeLimit!!.measurable)
        assertNull(unknown.timeLimit.usedMs)
        assertNull(unknown.causes.single().enforcingNow)
        val zero = stored.copy(times = listOf(TimeLimit(pkg, 0)))
        assertEquals(true, TargetLimitProjection.app(zero, pkg, now, null).causes.single().enforcingNow)
    }

    @Test fun readableOpenAccountingDoesNotAdvertiseAnUnavailableTimedCredit() {
        val result = TargetLimitProjection.app(snapshot(time = null), pkg, now, null)
        val view = result.openCredit!!
        assertEquals(2, view.used)
        assertEquals(4, view.cap)
        assertTrue(view.readable)
        assertFalse(view.available)
        assertEquals(TargetLimitProjection.CREDIT_RUNTIME_UNAVAILABLE, view.unavailableReason)
        assertNull(result.causes.single().enforcingNow)
        assertEquals(BreakLevel.HARDER, result.causes.single().tier)
    }

    @Test fun missingCreditLedgerKeepsTheKnownCapButNeverInventsAZeroPool() {
        val view = TargetLimitProjection.app(snapshot(time = null, quota = null), pkg, now, null).openCredit!!
        assertNull(view.used)
        assertEquals(4, view.cap)
        assertFalse(view.readable)
        assertFalse(view.available)
        assertNull(view.periodEndsAtWall)
        assertEquals("open-credit-state-unavailable", view.unavailableReason)
    }

    @Test fun wallJumpMovesOnlyDisplayEstimatesAndNeverMutatesTheStoredQuota() {
        val stored = snapshot(time = null)
        val before = stored.openCredits!!
        val first = TargetLimitProjection.app(stored, pkg, now, null)
        now = now.copy(wallEpochMs = now.wallEpochMs + 3_600_000, elapsedRealtimeMs = now.elapsedRealtimeMs + 10_000)
        val after = TargetLimitProjection.app(stored, pkg, now, null)
        assertEquals(first.causes.single().endsAtWall!! + 3_600_000 - 10_000, after.causes.single().endsAtWall)
        assertEquals(first.openCredit!!.periodEndsAtWall!! + 3_600_000 - 10_000, after.openCredit!!.periodEndsAtWall)
        assertEquals(2, after.openCredit.used)
        assertTrue(after.causes.single().timeUncertain)
        assertEquals(before, stored.openCredits)
    }

    @Test fun aSavedGrantCannotMasqueradeAsRuntimeAccess() {
        val ledger = OpenCreditLedgerPolicy.initial(now, "UTC")
        val accepted = OpenCreditLedgerPolicy.acceptOpenCredit(ledger, pkg, "credit", listOf(4), emptyList(), false, now).next
        assertTrue(accepted.active.isNotEmpty())
        val stored = snapshot(time = null, quota = accepted)
        now = now.copy(wallEpochMs = now.wallEpochMs + 1_000, elapsedRealtimeMs = now.elapsedRealtimeMs + 1_000)
        val view = TargetLimitProjection.app(stored, pkg, now, null).openCredit!!
        assertEquals(1, view.used)
        assertFalse(view.available)
        assertNull(view.activeCreditEndsAtWall)
        assertEquals(accepted, stored.openCredits)
    }

    @Test fun missingOrExpiredLimitOccurrencesRemainUnavailableUntilTheOwnerMaterializesThem() {
        val stored = snapshot()
        val absent = TargetLimitProjection.app(stored.copy(editor = EditorCommitmentState()), pkg, now, 0)
        assertEquals("limit-occurrence-unavailable", absent.unavailableReason)
        val remaining = stored.editor.causes.getValue(timeCauseKey(pkg)).interval!!.remainingAtAnchorMs
        now = now.copy(wallEpochMs = now.wallEpochMs + remaining, elapsedRealtimeMs = now.elapsedRealtimeMs + remaining)
        assertEquals("limit-occurrence-unavailable", TargetLimitProjection.app(stored, pkg, now, 0).unavailableReason)
    }

    @Test fun savedQuotaZoneRetainsItsTwentyThreeHourDstBoundary() {
        now = AccessClockStamp(Instant.parse("2026-03-08T05:00:00Z").toEpochMilli(), 10_000, "boot")
        val quota = OpenCreditLedgerPolicy.initial(now, "America/New_York")
        val stored = snapshot(time = null, quota = quota, periodMs = 23 * 3_600_000L)
        val result = TargetLimitProjection.app(stored, pkg, now, null)
        val end = Instant.parse("2026-03-09T04:00:00Z").toEpochMilli()
        assertEquals(end, result.openCredit!!.periodEndsAtWall)
        assertEquals(end, result.causes.single().nextChangeAtWall)
    }

    @Test fun anotherAppsLimitsOrCreditCountersAreNotReturned() {
        val result = TargetLimitProjection.app(snapshot(), "unrelated.app", now, 9_999)
        assertTrue(result.causes.isEmpty())
        assertNull(result.openCredit)
        assertNull(result.timeLimit)
        assertNull(result.unavailableReason)
    }

    @Test fun stricterLegacyBudgetStillUsesTheStoredDataStoreTier() {
        val stored = snapshot(open = null)
        val stricter = TargetLimitProjection.app(stored, pkg, now, 90_000, legacyMinutes = 1)
        assertEquals(60_000L, stricter.timeLimit!!.quotaMs)
        assertEquals(BreakLevel.HARDCORE, stricter.timeLimit.tier)
        assertEquals(true, stricter.causes.single().enforcingNow)
        assertEquals(300_000L, TargetLimitProjection.app(stored, pkg, now, 0, legacyMinutes = 10).timeLimit!!.quotaMs)
    }

    @Test fun legacyOnlyBudgetUsesTheReadyDefaultTierAndSharedAnchoredPeriod() {
        val stored = snapshot(time = null, open = null).let {
            it.copy(focus = it.focus.copy(defaultBreakLevel = BreakLevel.HARDER))
        }
        val result = TargetLimitProjection.app(stored, pkg, now, 0, legacyMinutes = 3)
        val quota = checkNotNull(stored.openCredits)
        assertEquals(TimeLimitView(180_000, 0, BreakLevel.HARDER, true), result.timeLimit)
        assertEquals("legacy-time:$pkg", result.causes.single().causeKey)
        assertEquals("legacy-time-${quota.periodId}", result.causes.single().occurrenceId)
        assertEquals(result.causes.single().endsAtWall,
            now.wallEpochMs + quota.reset.remainingToResetMs)
        assertNull(result.unavailableReason)
    }

    @Test fun negativeLegacyUnsetMarkerDoesNotCreateAConfiguredLimit() {
        val result = TargetLimitProjection.app(snapshot(time = null, open = null), pkg, now, 0, legacyMinutes = -1)
        assertNull(result.timeLimit)
        assertTrue(result.causes.isEmpty())
    }
}
