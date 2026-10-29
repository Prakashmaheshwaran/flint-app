package com.flint.peakfocus.feature.blocklist

import com.flint.peakfocus.core.model.BreakLevel
import org.junit.Assert.*
import org.junit.Test

class AppDetailPresentationTest {
    private fun cause(key: String, tier: BreakLevel = BreakLevel.HARDER, end: Long? = 100,
        blocking: Boolean? = true, ongoing: Boolean = false) = AppDetailCause(
        key, "occurrence", "rule-$key", key, AppDetailKind.SCHEDULE, tier,
        active = true, enforcingNow = blocking, endsAtWall = end, ongoing = ongoing,
    )
    private fun snapshot(vararg rows: AppDetailCause) = AppDetailSnapshot(
        AppDetailReadiness.READY, AppDetailResolvedState.CoveredNotBlocking, rows.toList(),
    )

    @Test fun `orders enforcing rows then strongest tier latest end and stable identity`() {
        val rows = listOf(cause("inactive", BreakLevel.HARDCORE, blocking = false),
            cause("z", end = 200), cause("a", end = 200), cause("early"),
            cause("strong", BreakLevel.HARDCORE), cause("ongoing", end = null, ongoing = true))
        assertEquals(listOf("strong", "ongoing", "a", "z", "early", "inactive"),
            appDetailPresentation(snapshot(*rows.toTypedArray())).rows.map { it.causeKey })
    }

    @Test fun `unavailable never leaks cached rows or a fresh zero counter`() {
        val view = appDetailPresentation(snapshot(cause("cached")).copy(
            readiness = AppDetailReadiness.UNAVAILABLE,
            credits = AppDetailCredits(true, 0, 3),
        ))
        assertEquals(AppDetailHeadline.Unavailable, view.headline)
        assertTrue(view.rows.isEmpty())
        assertNull(view.credits)
    }

    @Test fun `missing resolved decision is unavailable rather than deriving from rows`() {
        val view = appDetailPresentation(snapshot(cause("blocked")).copy(resolved = null))
        assertEquals(AppDetailHeadline.Unavailable, view.headline)
        assertTrue(view.rows.isEmpty())
    }

    @Test fun `headline is copied from enforcement even with inactive or unconfirmed rows`() {
        val resolved = AppDetailResolvedState.Blocked("C supplied winner", BreakLevel.HARDCORE, 2, 900, false)
        val view = appDetailPresentation(snapshot(cause("other", blocking = null)).copy(resolved = resolved))
        assertEquals(AppDetailHeadline.Resolved(resolved), view.headline)
        assertNull(view.rows.single().enforcingNow)
    }

    @Test fun `readable counters retain used beyond a lowered cap and unknown counters stay unknown`() {
        val source = snapshot().copy(credits = AppDetailCredits(true, 4, 3))
        assertEquals(AppDetailCreditLine.Used(4, 3), appDetailPresentation(source).credits)
        assertEquals(AppDetailCreditLine.Unavailable, appDetailPresentation(source.copy(
            credits = AppDetailCredits(false, 0, 3))).credits)
        assertEquals(AppDetailCreditLine.Unavailable, appDetailPresentation(source.copy(
            credits = AppDetailCredits(true, null, 3))).credits)
    }

    @Test fun `floor remains explicit during policy failure and exposes no blocking rows or credits`() {
        val input = snapshot(cause("floor")).copy(safetyFloor = true,
            readiness = AppDetailReadiness.UNAVAILABLE, credits = AppDetailCredits(true, 2, 3))
        val view = appDetailPresentation(input)
        assertEquals(AppDetailHeadline.SafetyFloor, view.headline)
        assertTrue(view.showsSafetyFloorNote)
        assertTrue(view.rows.isEmpty())
        assertNull(view.credits)
    }

    @Test fun `pass recovery and next change use the published boundaries`() {
        val paused = AppDetailResolvedState.Paused(600)
        val view = appDetailPresentation(snapshot(cause("sleep", blocking = false)).copy(
            resolved = paused, nextChangeAtWall = 500))
        assertEquals(AppDetailHeadline.Resolved(paused), view.headline)
        assertEquals(500L, view.nextChangeAtWall)
    }
}
