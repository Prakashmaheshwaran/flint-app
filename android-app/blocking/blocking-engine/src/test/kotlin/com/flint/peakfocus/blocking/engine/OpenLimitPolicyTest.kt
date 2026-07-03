package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.BreakLevel
import com.flint.peakfocus.core.model.OpenCountState
import com.flint.peakfocus.core.model.OpenLimit
import com.flint.peakfocus.core.model.OpenLimitDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenLimitPolicyTest {

    private val policy = OpenLimitPolicy()
    private val self = "com.flint.peakfocus"
    private val insta = "com.instagram.android"
    private val today = 20637L // Fri 2026-07-03

    private fun limits(dailyOpens: Int, level: BreakLevel = BreakLevel.EASY) =
        listOf(OpenLimit(packageName = insta, dailyOpens = dailyOpens, breakLevel = level))

    // MARK: decide

    @Test
    fun noLimitConfiguredAllowsWithNullRemaining() {
        val decision = policy.decide(insta, OpenCountState(today), emptyList(), today)
        assertEquals(OpenLimitDecision.Allowed(remainingOpens = null), decision)
    }

    @Test
    fun underQuotaAllowsAndReportsRemaining() {
        var state = OpenCountState(today)
        state = policy.recordOpen(state, insta, today)
        val decision = policy.decide(insta, state, limits(3), today)
        assertEquals(OpenLimitDecision.Allowed(remainingOpens = 2), decision)
    }

    @Test
    fun atQuotaBlocksAndCarriesTheLimit() {
        var state = OpenCountState(today)
        repeat(3) { state = policy.recordOpen(state, insta, today) }
        val decision = policy.decide(insta, state, limits(3, BreakLevel.HARDCORE), today)
        assertTrue(decision is OpenLimitDecision.Blocked)
        val blocked = decision as OpenLimitDecision.Blocked
        assertEquals(BreakLevel.HARDCORE, blocked.limit.breakLevel) // UI needs the tier
        assertTrue(blocked.reason.contains("3/3"))
    }

    @Test
    fun overQuotaStaysBlocked() {
        var state = OpenCountState(today)
        repeat(5) { state = policy.recordOpen(state, insta, today) }
        assertTrue(policy.decide(insta, state, limits(3), today) is OpenLimitDecision.Blocked)
    }

    @Test
    fun otherAppsAreUnaffectedByAnAppsLimit() {
        var state = OpenCountState(today)
        repeat(9) { state = policy.recordOpen(state, insta, today) }
        val decision = policy.decide("com.spotify.music", state, limits(3), today)
        assertEquals(OpenLimitDecision.Allowed(remainingOpens = null), decision)
    }

    // MARK: counting + day rollover

    @Test
    fun recordOpenIncrementsPerPackageIndependently() {
        var state = OpenCountState(today)
        state = policy.recordOpen(state, insta, today)
        state = policy.recordOpen(state, insta, today)
        state = policy.recordOpen(state, "com.spotify.music", today)
        assertEquals(2, policy.opensUsed(state, insta, today))
        assertEquals(1, policy.opensUsed(state, "com.spotify.music", today))
        assertEquals(0, policy.opensUsed(state, "com.x", today))
    }

    @Test
    fun newDayResetsTheCounts() {
        var state = OpenCountState(today)
        repeat(3) { state = policy.recordOpen(state, insta, today) }
        assertTrue(policy.decide(insta, state, limits(3), today) is OpenLimitDecision.Blocked)

        val tomorrow = today + 1
        assertEquals(0, policy.opensUsed(state, insta, tomorrow))
        assertEquals(OpenLimitDecision.Allowed(remainingOpens = 3), policy.decide(insta, state, limits(3), tomorrow))
    }

    @Test
    fun recordOpenAcrossMidnightStartsAFreshDay() {
        var state = OpenCountState(today)
        repeat(4) { state = policy.recordOpen(state, insta, today) }
        state = policy.recordOpen(state, insta, today + 1)
        assertEquals(today + 1, state.epochDay)
        assertEquals(mapOf(insta to 1), state.opensByPackage)
    }

    @Test
    fun uninitializedStateBehavesLikeAFreshDay() {
        val state = OpenCountState() // DAY_UNINITIALIZED sentinel
        assertEquals(0, policy.opensUsed(state, insta, today))
        val recorded = policy.recordOpen(state, insta, today)
        assertEquals(today, recorded.epochDay)
        assertEquals(1, recorded.opensFor(insta))
    }

    // MARK: what counts as an "open"

    @Test
    fun launchingAnAppFromAnotherAppCountsAsOpen() {
        assertTrue(policy.countsAsOpen("com.google.android.apps.nexuslauncher", insta, self))
        assertTrue(policy.countsAsOpen("com.spotify.music", insta, self))
        assertTrue(policy.countsAsOpen(null, insta, self)) // first event after service start
    }

    @Test
    fun stayingInTheSameAppIsNotANewOpen() {
        assertFalse(policy.countsAsOpen(insta, insta, self))
    }

    @Test
    fun flintAndSystemSurfacesNeverCountAsOpens() {
        assertFalse(policy.countsAsOpen("com.x", self, self))
        assertFalse(policy.countsAsOpen("com.x", "com.android.systemui", self))
        assertFalse(policy.countsAsOpen("com.x", "com.android.settings", self))
        assertFalse(policy.countsAsOpen("com.x", "", self))
    }

    @Test
    fun bouncingBackFromShadeOrShieldIsNotAReopen() {
        // notification shade dip: app -> systemui -> same app (or any app) shouldn't re-count
        assertFalse(policy.countsAsOpen("com.android.systemui", insta, self))
        // returning from Flint's own shield/UI
        assertFalse(policy.countsAsOpen(self, insta, self))
    }

    @Test
    fun epochDayHelperMatchesDayMathConventions() {
        assertEquals(0L, policy.epochDay(0L))
        assertEquals(today, policy.epochDay(today * 86_400_000L + 12 * 3_600_000L))
        // just before UTC midnight with a +1h offset already belongs to the next local day
        assertEquals(today, policy.epochDay(today * 86_400_000L - 1L, utcOffsetMs = 3_600_000L))
    }
}
