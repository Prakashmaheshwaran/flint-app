package com.flint.peakfocus.feature.blocklist

import com.flint.peakfocus.core.model.BreakLevel
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneOffset
import java.util.Locale

class AppDetailCopyTest {
    private fun blocked(others: Int, until: Long?, ongoing: Boolean) = AppDetailHeadline.Resolved(
        AppDetailResolvedState.Blocked("Work", BreakLevel.HARDER, others, until, ongoing))

    @Test fun `blocked headline names the strongest cause and tier and keeps the reader's end`() {
        val who = listOf("Work", ResArg(R.string.target_tier_harder))
        assertEquals(DetailLine(R.string.target_blocked_until, args = who, time = 9), headlineLine(blocked(0, 9, false)))
        assertEquals(DetailLine(R.string.target_blocked_ongoing, args = who), headlineLine(blocked(0, 9, true)))
        assertEquals(DetailLine(R.string.target_blocked, args = who), headlineLine(blocked(0, null, false)))
        assertEquals(DetailLine(plural = R.plurals.target_blocked_more_until, count = 2, args = who + 2, time = 9),
            headlineLine(blocked(2, 9, false)))
        assertEquals(R.plurals.target_blocked_more_ongoing, headlineLine(blocked(1, 9, true)).plural)
        assertEquals(R.plurals.target_blocked_more, headlineLine(blocked(1, null, false)).plural)
        assertEquals(R.string.target_blocked_unattributed,
            headlineLine(AppDetailHeadline.Resolved(AppDetailResolvedState.BlockedUnattributed)).text)
    }

    @Test fun `non-blocking headlines and unknown states`() {
        assertEquals(R.string.target_unavailable, headlineLine(AppDetailHeadline.Unavailable).text)
        assertEquals(R.string.target_safety_floor, headlineLine(AppDetailHeadline.SafetyFloor).text)
        assertEquals(DetailLine(R.string.target_paused_until, time = 5),
            headlineLine(AppDetailHeadline.Resolved(AppDetailResolvedState.Paused(5))))
        assertEquals(R.string.target_coverage_unconfirmed,
            headlineLine(AppDetailHeadline.Resolved(AppDetailResolvedState.CoverageUnconfirmed)).text)
        assertEquals(R.string.target_not_covered, headlineLine(AppDetailHeadline.Resolved(AppDetailResolvedState.NotCovered)).text)
    }

    private fun cause(enforcing: Boolean?, active: Boolean = true, end: Long? = 7, ongoing: Boolean = false) = AppDetailCause(
        "k", "o", "r", "Rule", AppDetailKind.SCHEDULE, BreakLevel.EASY, active, enforcing, end, ongoing)

    @Test fun `row status never turns unknown enforcement into not blocking`() {
        assertEquals(R.string.target_cause_unknown, causeStatusLine(cause(null)).text)
        assertEquals(DetailLine(R.string.target_cause_blocking_until, time = 7), causeStatusLine(cause(true)))
        assertEquals(R.string.target_cause_blocking, causeStatusLine(cause(true, end = null)).text)
        assertEquals(DetailLine(R.string.target_cause_active_ends, time = 7), causeStatusLine(cause(false)))
        assertEquals(R.string.target_cause_active, causeStatusLine(cause(false, end = null)).text)
        assertEquals(R.string.target_cause_inactive, causeStatusLine(cause(false, active = false)).text)
    }

    @Test fun `credits show used of cap only from a readable counter`() {
        assertEquals(DetailLine(R.string.target_credits_used, args = listOf(4, 3)), creditLine(AppDetailCreditLine.Used(4, 3)))
        assertEquals(R.string.target_credits_unavailable, creditLine(AppDetailCreditLine.Unavailable).text)
    }

    @Test fun `time limit shows measured use or says it can't be measured, never zero`() {
        assertEquals(DetailLine(R.string.target_time_used, args = listOf("10m", "30m")),
            timeLimitLine(AppDetailTimeLimit(1_800_000, 600_000, BreakLevel.EASY)))
        assertEquals(DetailLine(R.string.target_time_unmeasured, args = listOf("30m")),
            timeLimitLine(AppDetailTimeLimit(1_800_000, null, BreakLevel.EASY)))
    }

    @Test fun `times show the weekday only when not today`() {
        val now = 1_767_268_800_000L // 2026-01-01T12:00Z, a Thursday
        assertEquals("1:30 PM", detailTimeText(now + 5_400_000, now, ZoneOffset.UTC, Locale.US))
        assertEquals("Fri 9:00 AM", detailTimeText(now + 75_600_000, now, ZoneOffset.UTC, Locale.US))
    }

    @Test fun `website lines carry the original check time or say no check exists`() {
        assertEquals(DetailLine(R.string.target_web_available, time = 42), websiteCoverageLine(AppDetailWebCoverage.AVAILABLE, 42))
        assertEquals(DetailLine(R.string.target_web_unconfirmed, time = 42), websiteCoverageLine(AppDetailWebCoverage.UNCONFIRMED, 42))
        assertEquals(DetailLine(R.string.target_web_no_check), websiteCoverageLine(AppDetailWebCoverage.DEGRADED, null))
        assertEquals(DetailLine(R.string.target_web_awaiting), websiteCoverageLine(AppDetailWebCoverage.AWAITING_BROWSER, 42))
        assertEquals(DetailLine(R.string.target_web_not_required), websiteCoverageLine(AppDetailWebCoverage.NOT_REQUIRED, null))
    }
}
