package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.BreakLevel
import com.flint.peakfocus.core.model.OpenCountState
import com.flint.peakfocus.core.model.OpenLimit
import com.flint.peakfocus.core.model.OpenLimitDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenLimitPolicyTest {

    private val policy = OpenLimitPolicy()
    private val self = "com.flint.peakfocus"
    private val insta = "com.instagram.android"
    private val spotify = "com.spotify.music"
    private val shade = "com.android.systemui"
    private val launcher = "com.google.android.apps.nexuslauncher"
    private val today = 20637L // Fri 2026-07-03

    private fun limits(dailyOpens: Int, level: BreakLevel = BreakLevel.EASY) =
        listOf(OpenLimit(packageName = insta, dailyOpens = dailyOpens, breakLevel = level))

    private fun session(openLimits: List<OpenLimit>, day: Long = today) =
        Session(policy, openLimits, self, day)

    /**
     * Drives [OpenLimitPolicy.onForegroundTransition] the way a detector does: counts and grant
     * carried from one transition to the next, `previous` advancing on every observed package.
     *
     * [goTo] is the coordinator entry point. [passThrough] is the seam where a detector sees a
     * package but short-circuits before the coordinator — Flint's own windows (both paths) and
     * the resolved launcher/dialer (Path A's `neverBlock`) — so `previous` moves but no
     * transition is folded.
     */
    private class Session(
        private val policy: OpenLimitPolicy,
        private val limits: List<OpenLimit>,
        private val self: String,
        private val day: Long,
    ) {
        var counts = OpenCountState(day)
        var granted: String? = null
        private var previous: String? = null

        fun goTo(packageName: String): OpenLimitDecision {
            val transition =
                policy.onForegroundTransition(previous, packageName, self, counts, granted, limits, day)
            counts = transition.counts
            granted = transition.granted
            previous = packageName
            return transition.decision
        }

        fun passThrough(packageName: String) {
            previous = packageName
        }
    }

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

    // MARK: forward-only rollover (time-change guard, always-on half — see ClockChangeGuard)

    @Test
    fun clockSetBackADayDoesNotRegrantConsumedOpens() {
        var state = OpenCountState(today)
        repeat(3) { state = policy.recordOpen(state, insta, today) }

        val yesterday = today - 1 // the clock now lies
        assertEquals(state, policy.rolledOver(state, yesterday)) // no reset, nothing to persist
        assertEquals(3, policy.opensUsed(state, insta, yesterday))
        assertTrue(policy.decide(insta, state, limits(3), yesterday) is OpenLimitDecision.Blocked)
        // …and the state is intact once the clock returns to the real day.
        assertTrue(policy.decide(insta, state, limits(3), today) is OpenLimitDecision.Blocked)
    }

    @Test
    fun opensRecordedDuringAClockSetBackAccrueAgainstTheKeptDay() {
        var state = OpenCountState(today)
        state = policy.recordOpen(state, insta, today)
        state = policy.recordOpen(state, insta, today - 1) // opened while the clock lies
        assertEquals(today, state.epochDay)
        assertEquals(2, policy.opensUsed(state, insta, today))
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

    // MARK: foreground transitions — decide once per open, never re-block a granted one

    @Test
    fun theOpenJustGrantedIsNotReblockedWhileTheAppHoldsTheForeground() {
        val s = session(limits(1)) // "open Instagram once a day": that one open must be usable
        assertTrue(s.goTo(insta) is OpenLimitDecision.Allowed)
        assertEquals(1, policy.opensUsed(s.counts, insta, today)) // quota now spent…

        // …but the app is still in front. Path B re-evaluates a second later; Path A on the
        // next in-app window event. Both must leave the user inside the open they paid for.
        repeat(3) { assertTrue(s.goTo(insta) is OpenLimitDecision.Allowed) }
        assertEquals(1, policy.opensUsed(s.counts, insta, today)) // and never re-count it
    }

    @Test
    fun reopeningTheAppOnceTheQuotaIsSpentBlocks() {
        val s = session(limits(1))
        assertTrue(s.goTo(insta) is OpenLimitDecision.Allowed)
        assertTrue(s.goTo(spotify) is OpenLimitDecision.Allowed) // left for another real app
        assertTrue(s.goTo(insta) is OpenLimitDecision.Blocked) // a second open, over budget
        assertNull(s.granted)
        assertEquals(1, policy.opensUsed(s.counts, insta, today)) // a blocked open is not recorded
    }

    @Test
    fun aShadeDipDoesNotEndTheGrantedOpen() {
        val s = session(limits(1))
        assertTrue(s.goTo(insta) is OpenLimitDecision.Allowed)
        assertTrue(s.goTo(shade) is OpenLimitDecision.Allowed) // notification shade
        assertTrue(s.goTo(insta) is OpenLimitDecision.Allowed) // same open, resumed
        assertEquals(insta, s.granted)
        assertEquals(1, policy.opensUsed(s.counts, insta, today))
    }

    @Test
    fun bouncingThroughTheShadeIntoAnotherAppEndsTheGrantedOpen() {
        val s = session(limits(1))
        assertTrue(s.goTo(insta) is OpenLimitDecision.Allowed)
        assertTrue(s.goTo(shade) is OpenLimitDecision.Allowed)
        assertTrue(s.goTo(spotify) is OpenLimitDecision.Allowed) // a real app: the open is over
        assertNull(s.granted)
        assertTrue(s.goTo(shade) is OpenLimitDecision.Allowed)
        assertTrue(s.goTo(insta) is OpenLimitDecision.Blocked) // …so coming back is a new open
    }

    @Test
    fun returningFromFlintDuringAGrantedOpenStaysAllowed() {
        val s = session(limits(1))
        assertTrue(s.goTo(insta) is OpenLimitDecision.Allowed)
        s.passThrough(self) // a deliberate visit to Flint's own UI
        assertTrue(s.goTo(insta) is OpenLimitDecision.Allowed)
        assertEquals(1, policy.opensUsed(s.counts, insta, today))
    }

    @Test
    fun returningFromFlintToABlockedAppStaysBlocked() {
        val s = session(limits(1))
        assertTrue(s.goTo(insta) is OpenLimitDecision.Allowed)
        s.passThrough(launcher)
        assertTrue(s.goTo(insta) is OpenLimitDecision.Blocked)
        s.passThrough(self) // the block screen's "Open Flint", then back to the shielded app
        assertTrue(s.goTo(insta) is OpenLimitDecision.Blocked)
    }

    @Test
    fun aLauncherRoundTripIsANewOpenEvenWhileAGrantNamesTheApp() {
        // Path A never routes the launcher through the coordinator, so the grant still reads
        // `insta` when the user comes back — the entry check must win over it.
        val s = session(limits(1))
        assertTrue(s.goTo(insta) is OpenLimitDecision.Allowed)
        assertEquals(insta, s.granted)
        s.passThrough(launcher)
        assertTrue(s.goTo(insta) is OpenLimitDecision.Blocked)
    }

    @Test
    fun aUseAnywayBreakReblocksOnceItsExemptionEnds() {
        val s = session(limits(1))
        assertTrue(s.goTo(insta) is OpenLimitDecision.Allowed)
        s.passThrough(launcher)
        assertTrue(s.goTo(insta) is OpenLimitDecision.Blocked)
        // "Use anyway" spends one more open (BlockScreenCoordinator.requestBreak) without
        // granting it: the break's exemption window is what lifts the shield, so the re-check
        // when that window ends must block again.
        s.counts = policy.recordOpen(s.counts, insta, today)
        assertTrue(s.goTo(insta) is OpenLimitDecision.Blocked)
        assertEquals(2, policy.opensUsed(s.counts, insta, today))
    }

    @Test
    fun remainingOpensCountsDownTheOpenItJustRecorded() {
        val s = session(limits(3))
        assertEquals(OpenLimitDecision.Allowed(2), s.goTo(insta))
        assertEquals(OpenLimitDecision.Allowed(2), s.goTo(insta)) // same open, same remainder
        s.passThrough(launcher)
        assertEquals(OpenLimitDecision.Allowed(1), s.goTo(insta))
        s.passThrough(launcher)
        assertEquals(OpenLimitDecision.Allowed(0), s.goTo(insta)) // the third and last
        s.passThrough(launcher)
        assertTrue(s.goTo(insta) is OpenLimitDecision.Blocked) // the fourth
    }

    @Test
    fun appsWithoutALimitAreAlwaysAllowedAndReportNoRemainder() {
        val s = session(limits(1))
        assertEquals(OpenLimitDecision.Allowed(null), s.goTo(spotify))
        assertEquals(OpenLimitDecision.Allowed(null), s.goTo(spotify))
    }

    @Test
    fun aGrantedOpenSurvivesMidnightAndTheFreshDayRestoresTheQuota() {
        val s = session(limits(1))
        assertTrue(s.goTo(insta) is OpenLimitDecision.Allowed)
        // Parked in the app when the local day rolls over: the re-check rolls the counts and
        // hands back a fresh quota — the day's opens are gone, not the user's current session.
        val rolled =
            policy.onForegroundTransition(insta, insta, self, s.counts, s.granted, limits(1), today + 1)
        assertEquals(OpenLimitDecision.Allowed(1), rolled.decision)
        assertEquals(today + 1, rolled.counts.epochDay)
        assertEquals(0, policy.opensUsed(rolled.counts, insta, today + 1))
    }

    @Test
    fun enteringAnotherAppEndsTheGrantEvenWhenThatAppIsItselfBlocked() {
        val both = limits(1) + OpenLimit(packageName = spotify, dailyOpens = 1)
        val s = session(both)
        assertTrue(s.goTo(spotify) is OpenLimitDecision.Allowed) // spends Spotify's only open
        assertTrue(s.goTo(insta) is OpenLimitDecision.Allowed) // spends Instagram's only open
        assertEquals(insta, s.granted)
        assertTrue(s.goTo(spotify) is OpenLimitDecision.Blocked) // Spotify is over budget
        assertNull(s.granted) // a block always clears the grant…
        assertTrue(s.goTo(insta) is OpenLimitDecision.Blocked) // …so Instagram is a fresh open
    }
}
