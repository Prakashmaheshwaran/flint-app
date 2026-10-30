package com.flint.peakfocus.feature.blocklist

import com.flint.peakfocus.blocking.overlay.CauseKind
import com.flint.peakfocus.blocking.overlay.CoveringCause
import com.flint.peakfocus.blocking.overlay.FloorIdentity
import com.flint.peakfocus.blocking.overlay.OpenCreditView
import com.flint.peakfocus.blocking.overlay.Readiness
import com.flint.peakfocus.blocking.overlay.TargetProtection
import com.flint.peakfocus.blocking.overlay.TargetWebsiteCoverage
import com.flint.peakfocus.blocking.overlay.TimeLimitView
import com.flint.peakfocus.core.model.BreakLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDetailAdapterTest {
    private fun cause(key: String, tier: BreakLevel, enforcing: Boolean?, end: Long?, kind: CauseKind = CauseKind.SCHEDULE) =
        CoveringCause(ruleId = "r-$key", name = key, kind = kind, tier = tier, active = true, endsAtWall = end,
            timeUncertain = false, nextChangeAtWall = null, enforcingNow = enforcing, causeKey = key, occurrenceId = null)

    @Test fun `headline names the strongest cause while until is the latest enforcing end`() {
        val strong = cause("Hardcore mornings", BreakLevel.HARDCORE, true, end = 100)
        val later = cause("Easy evening", BreakLevel.EASY, true, end = 900)
        val snapshot = appDetailSnapshot(TargetProtection(Readiness.Ready, coveringCauses = listOf(later, strong),
            blockingNow = true, blockingUntilWall = 900, headline = strong))
        assertEquals(AppDetailResolvedState.Blocked("Hardcore mornings", BreakLevel.HARDCORE, 1, 900, false), snapshot.resolved)
        assertNull("a pending occurrence never gets an invented identity", snapshot.causes.first().occurrenceId)
    }

    @Test fun `unknown enforcement stays unconfirmed even during recovery`() {
        val unknown = TargetProtection(Readiness.Ready, coveringCauses = listOf(cause("x", BreakLevel.EASY, null, 5)),
            blockingNow = null, recoveryEndsAtWall = 50)
        assertEquals(AppDetailResolvedState.CoverageUnconfirmed, appDetailSnapshot(unknown).resolved)
        assertEquals(AppDetailResolvedState.Paused(50), appDetailSnapshot(unknown.copy(blockingNow = false)).resolved)
    }

    @Test fun `blocked without a named headline does not guess one from rows`() {
        val p = TargetProtection(Readiness.Ready, coveringCauses = listOf(cause("x", BreakLevel.HARDER, true, 5)), blockingNow = true)
        assertEquals(AppDetailResolvedState.BlockedUnattributed, appDetailSnapshot(p).resolved)
    }

    @Test fun `covered, not covered, floor and unavailable map without deriving coverage`() {
        val ready = TargetProtection(Readiness.Ready, blockingNow = false)
        assertEquals(AppDetailResolvedState.NotCovered, appDetailSnapshot(ready).resolved)
        assertEquals(AppDetailResolvedState.CoveredNotBlocking,
            appDetailSnapshot(ready.copy(coveringCauses = listOf(cause("x", BreakLevel.EASY, false, 5)))).resolved)
        val floor = appDetailSnapshot(TargetProtection(Readiness.Unavailable("x"), floor = FloorIdentity(FloorIdentity.Kind.STATIC_SYSTEM)))
        assertTrue(floor.safetyFloor)
        assertEquals(AppDetailHeadline.SafetyFloor, appDetailPresentation(floor).headline)
        assertEquals(AppDetailHeadline.Unavailable, appDetailPresentation(appDetailSnapshot(TargetProtection(Readiness.Unavailable("x")))).headline)
    }

    @Test fun `counters, unmeasured use and site coverage are copied as published`() {
        val p = appDetailSnapshot(TargetProtection(Readiness.Ready, blockingNow = false,
            openCredit = OpenCreditView(2, 3, readable = true, available = true, unavailableReason = null, activeCreditEndsAtWall = 77, periodEndsAtWall = 99),
            timeLimit = TimeLimitView(quotaMs = 1_800_000, usedMs = 600_000, tier = BreakLevel.HARDER, measurable = false),
            websiteCoverage = TargetWebsiteCoverage.AWAITING_BROWSER))
        assertEquals(AppDetailCredits(true, 2, 3, 77), p.credits)
        assertEquals(AppDetailTimeLimit(1_800_000, null, BreakLevel.HARDER), p.timeLimit)
        assertEquals(AppDetailWebCoverage.AWAITING_BROWSER, p.websiteCoverage)
        assertEquals(77L, appDetailPresentation(p).activeCreditEndsAtWall)
    }
}
