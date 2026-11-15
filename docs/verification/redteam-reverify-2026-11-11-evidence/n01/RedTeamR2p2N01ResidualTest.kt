package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.blocking.engine.*
import com.flint.peakfocus.core.datastore.*
import com.flint.peakfocus.core.model.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Red-team re-verification of residual R2p2-N01 on origin/main b888084 (after PR #247).
 *
 * PR #247 made the two serialized reducers re-derive the *zero-minute* Time contributor from
 * the committed snapshot. These tests probe the same "stale Time evidence at commit" shape
 * where the committed budget is positive, where the release-continuation path is used, and
 * where the pre-transaction readiness guard consumes evidence from an older definition.
 *
 * Every test pins its own clock; no host time zone or wall clock is read.
 */
class RedTeamR2p2N01ResidualTest {
    private val now = AccessClockStamp(1_800_000_000_000, 10_000, "boot")
    private val pkg = "a"
    private val self = "self"

    /** A healthy durable day: 20 minutes of measured usage inside the current trusted period. */
    private val usedDay = TimeLimitDayAccounting.initial(now, "UTC").let {
        it.copy(usedMs = 20 * 60_000L, observedUsedMs = 20 * 60_000L)
    }

    private fun budget(minutes: Int, remaining: Long? = TimeLimitDayAccounting.remainingMs(usedDay, minutes)) =
        TrustedTimeLimitBudget(pkg, minutes, usedDay, remaining, TrustedTimeLimitBudget.Measurement.OBSERVED)

    private fun uuid(suffix: Int) = "11111111-1111-4111-8111-${suffix.toString().padStart(12, '0')}"

    private fun credits(at: AccessClockStamp = now) = OpenCreditLedgerPolicy.initial(at, "UTC")

    private fun openCause(ledger: OpenCreditLedger) =
        EditorCause("day", AnchoredInterval(anchor = ledger.reset.checkpoint,
            remainingAtAnchorMs = ledger.reset.remainingToResetMs,
            wallEndEpochMs = ledger.reset.checkpoint.wallEpochMs + ledger.reset.remainingToResetMs), "UTC")

    private fun creditSnapshot(minutes: Int = 30, tier: BreakLevel = BreakLevel.HARDCORE,
        at: AccessClockStamp = now): ProtectionSnapshot {
        val ledger = credits(at)
        return ProtectionSnapshot(
            FocusSnapshot(protection = ProtectionLedger(at, at)),
            emptyList(),
            listOfNotNull(TimeLimit(pkg, minutes, tier, "UTC")),
            listOf(OpenLimit(pkg, 3, BreakLevel.HARDCORE)),
            EditorCommitmentState(causes = mapOf(openCauseKey(pkg) to openCause(ledger), timeCauseKey(pkg) to
                EditorCause("day", AnchoredInterval(at, 3_600_000, at.wallEpochMs + 3_600_000), "UTC")), timeline = at),
            HarderWaitPolicy.Ledger(),
            at,
            ledger,
        )
    }

    private fun ruleSnapshot(minutes: Int = 30): ProtectionSnapshot {
        val rule = BlockRule("session-timer-v2-rt", "Red team one", BlockTargets(apps = setOf(AppRef(pkg))),
            breakLevel = BreakLevel.EASY, expiresAtEpochMs = now.wallEpochMs + 3_600_000)
        return ProtectionSnapshot(
            FocusSnapshot(protection = ProtectionLedger(now, now)),
            listOf(rule),
            listOfNotNull(TimeLimit(pkg, minutes, BreakLevel.EASY, "UTC")),
            emptyList(),
            EditorCommitmentState(causes = mapOf(ruleCauseKey(rule.id) to
                EditorCause(rule.id, AnchoredInterval(now, 3_600_000, now.wallEpochMs + 3_600_000), "UTC"),
                timeCauseKey(pkg) to EditorCause("day", AnchoredInterval(now, 3_600_000, now.wallEpochMs + 3_600_000), "UTC")),
                timeline = now),
            HarderWaitPolicy.Ledger(),
            now,
            null,
        )
    }

    private val creditInput = OrdinaryProtectionInput(pkg, self, timeSpent = false, openBlocked = true)
    private val breakInput = OrdinaryProtectionInput(pkg, self, timeSpent = false, openBlocked = false)

    private fun reviewedCreditId(s: ProtectionSnapshot, suffix: Int = 1): GrantRequestId {
        val scope = TimedOpenCreditPolicy.view(s, creditInput, emptyList(), s.observedAt)!!.requestScope
        return GrantRequestId(s.observedAt.wallEpochMs, uuid(suffix), s.observedAt, scope)
    }

    private fun applied(s: ProtectionSnapshot, m: ProtectionMutation<*>): ProtectionSnapshot =
        s.copy(focus = m.focus, openCredits = m.openCredits ?: s.openCredits, harder = m.harder ?: s.harder)

    private fun counters(m: ProtectionMutation<GrantResult>, before: ProtectionSnapshot) =
        (m.openCredits ?: before.openCredits)!!.counters

    // ---------------------------------------------------------------------------------------
    // Attack A: a POSITIVE budget that becomes exhausted between review and transaction
    // (strengthening edit lowers the cap below the durable measured usage). §5 "Enforce all
    // other contributors first: ... exhausted Time Limit ... do not charge"; PC-10/PC-32.
    // ---------------------------------------------------------------------------------------

    @Test fun REOPENS_R2p2N01_positiveBudgetLoweredBelowDurableUsageStillChargesTheCredit() {
        val review = creditSnapshot(minutes = 30)
        val reviewed = reviewedCreditId(review)

        // Preconditions: the reviewed evidence is healthy, unspent evidence for 30 minutes.
        assertFalse("review evidence must be unspent", budget(30).spent)
        assertEquals(600_000L, budget(30).remainingMs)
        // The strengthening edit commits 1 minute while 20 minutes are already durable usage.
        assertEquals("the committed 1-minute limit is already exhausted",
            0L, TimeLimitDayAccounting.remainingMs(usedDay, 1))
        assertTrue("the committed 1-minute limit is exhausted on sight", budget(1).spent)

        val committed = review.copy(times = listOf(TimeLimit(pkg, 1, BreakLevel.HARDCORE, "UTC")))
        // The reviewed Open identity does not cover Time definitions, so it is still current.
        assertEquals(reviewed.creditScope,
            TimedOpenCreditPolicy.view(committed, creditInput, emptyList(), now)!!.requestScope)

        val m = TimedOpenCreditPolicy.request(committed, creditInput, reviewed, emptyList(), false)
        assertEquals("a credit must not be charged while the committed Time Limit still blocks",
            GrantResult.Denied, m.result)
        assertTrue("quota must not be spent", counters(m, committed).isEmpty())
        assertTrue((m.openCredits ?: committed.openCredits)!!.active.isEmpty())
        assertTrue(m.focus.protection!!.pendingCreditEnds.isEmpty())
    }

    @Test fun REOPENS_R2p2N01_positiveBudgetLoweredBelowDurableUsageStillGrantsAnOrdinaryBreak() {
        val review = ruleSnapshot(minutes = 30)
        // Click-time frozen scope: the rule only (the 30-minute limit is not exhausted).
        val clicked = OrdinaryProtectionPolicy.frozenScopes(review, breakInput, emptyList(), now)!!
        assertEquals(1, clicked.size)

        // Strengthening edit to 1 minute; durable usage (20 min) already exceeds it.
        assertEquals(0L, TimeLimitDayAccounting.remainingMs(usedDay, 1))
        val committed = review.copy(times = listOf(TimeLimit(pkg, 1, BreakLevel.HARDCORE, "UTC")))
        // GlobalRecoveryController.requestOrdinary recomputes the frozen set at commit time.
        val atCommit = OrdinaryProtectionPolicy.frozenScopes(committed, breakInput, emptyList(), now)!!
        assertEquals("the stale Boolean makes the commit scope blind to the new Hardcore tier",
            clicked, atCommit)

        val m = OrdinaryProtectionPolicy.request(committed, breakInput,
            "p1-${uuid(2)}", now, emptyList(), atCommit)
        assertEquals("an exhausted committed Hardcore Time Limit must deny the ordinary grant",
            GrantResult.Denied, m.result)
        assertTrue("no access may be released", m.focus.protection!!.ordinaryGrants.isEmpty())
    }

    // ---------------------------------------------------------------------------------------
    // Attack B: the release-continuation path (covers/retireInvalid) still trusts the stale
    // Boolean, so a committed exhausted Time Limit neither ends the grant nor re-shields.
    // §3 "Creating a stronger cause during a weaker grant immediately blocks; grants never
    // extend to future cause IDs."
    // ---------------------------------------------------------------------------------------

    private fun liveBreak(): ProtectionSnapshot {
        val review = ruleSnapshot(minutes = 30).copy(times = emptyList(),
            editor = EditorCommitmentState(causes = mapOf(ruleCauseKey("session-timer-v2-rt") to
                EditorCause("session-timer-v2-rt", AnchoredInterval(now, 300_000, now.wallEpochMs + 300_000), "UTC")),
                timeline = now))
        val scopes = OrdinaryProtectionPolicy.frozenScopes(review, breakInput, emptyList(), now)!!
        val accepted = OrdinaryProtectionPolicy.request(review, breakInput, "p1-${uuid(3)}", now, emptyList(), scopes)
        assertTrue(accepted.result is GrantResult.Granted)
        return review.copy(focus = accepted.focus, harder = accepted.harder ?: review.harder)
    }

    @Test fun REOPENS_R2p2N01_zeroMinuteLimitCommittedDuringALiveBreakKeepsCoveringTheTarget() {
        val live = liveBreak()
        assertTrue(OrdinaryProtectionPolicy.covers(live, breakInput, emptyList(), now))
        val committed = live.copy(times = listOf(TimeLimit(pkg, 0, BreakLevel.HARDCORE, "UTC")),
            editor = live.editor.copy(causes = live.editor.causes +
                (timeCauseKey(pkg) to EditorCause("day", AnchoredInterval(now, 3_600_000, now.wallEpochMs + 3_600_000), "UTC"))))
        assertFalse("a newly committed Hardcore Time Limit must immediately end the weaker break",
            OrdinaryProtectionPolicy.covers(committed, breakInput, emptyList(), now))
    }

    @Test fun REOPENS_R2p2N01_zeroMinuteLimitCommittedDuringALiveBreakIsNotRetiredDurably() {
        val live = liveBreak()
        val committed = live.copy(times = listOf(TimeLimit(pkg, 0, BreakLevel.HARDCORE, "UTC")),
            editor = live.editor.copy(causes = live.editor.causes +
                (timeCauseKey(pkg) to EditorCause("day", AnchoredInterval(now, 3_600_000, now.wallEpochMs + 3_600_000), "UTC"))))
        val retired = OrdinaryProtectionPolicy.retireInvalid(committed, breakInput, emptyList())
        assertTrue("the superseded break must be retired durably", retired.protection!!.ordinaryGrants.isEmpty())
    }

    @Test fun REOPENS_R2p2N01_positiveLimitLoweredBelowDurableUsageKeepsCoveringALiveBreak() {
        val live = liveBreak()
        assertEquals(0L, TimeLimitDayAccounting.remainingMs(usedDay, 1))
        val committed = live.copy(times = listOf(TimeLimit(pkg, 1, BreakLevel.HARDCORE, "UTC")),
            editor = live.editor.copy(causes = live.editor.causes +
                (timeCauseKey(pkg) to EditorCause("day", AnchoredInterval(now, 3_600_000, now.wallEpochMs + 3_600_000), "UTC"))))
        assertFalse("an exhausted committed Hardcore Time Limit must end the weaker break",
            OrdinaryProtectionPolicy.covers(committed, breakInput, emptyList(), now))
    }

    // ---------------------------------------------------------------------------------------
    // Attack C: the pre-transaction readiness guard consumes evidence bound to an older
    // definition and still permits new access. "Bind nonzero usage evidence to current limit
    // revision/period, refusing stale or unprovable evidence" (R2p2-N01 fix direction).
    // ---------------------------------------------------------------------------------------

    @Test fun REOPENS_R2p2N01_readinessGuardAcceptsEvidenceBoundToADifferentLimitDefinition() {
        val committed = listOf(TimeLimit(pkg, 1, BreakLevel.HARDCORE, "UTC"))
        // 20 of 30 minutes used -> the 1-minute definition is exhausted on sight.
        assertEquals(0L, TimeLimitDayAccounting.remainingMs(usedDay, 1))
        val stale = budget(30)
        assertEquals(600_000L, stale.remainingMs)
        assertFalse(stale.spent)
        assertFalse("the guard must refuse evidence for a definition that no longer exists",
            TimeLimitDomainReadiness.permitsNewAccess(pkg, StorageState.Ready(committed),
                StorageState.Ready(committed), stale, true, now))
    }

    @Test fun REOPENS_R2p2N01_readinessGuardTreatsUnknownEarlierUsageAsUnspent() {
        // Upgrade/clock change left earlier OS usage unproven for this period; the persisted
        // floor is not evidence that the remaining positive budget is unspent.
        val unknown = TimeLimitDayAccounting.initial(now, "UTC").copy(accountingUnknownUntilReset = true)
        val committed = StorageState.Ready(listOf(TimeLimit(pkg, 30, BreakLevel.HARDCORE, "UTC")))
        assertFalse("unproven earlier usage must not be treated as an unspent positive budget",
            TimeLimitDomainReadiness.permitsNewAccess(pkg, committed, committed,
                TrustedTimeLimitBudget(pkg, 30, unknown, 600_000L, TrustedTimeLimitBudget.Measurement.OBSERVED),
                true, now))
    }

    // ---------------------------------------------------------------------------------------
    // Closed paths (evidence that the merged change and the surrounding checks hold).
    // ---------------------------------------------------------------------------------------

    @Test fun CLOSED_R2p2N01_zeroMinuteLimitCommittedAfterReviewDeniesAndChargesNothing() {
        val review = creditSnapshot()
        val reviewed = reviewedCreditId(review)
        val committed = review.copy(times = listOf(TimeLimit(pkg, 0, BreakLevel.HARDCORE, "UTC")))
        val m = TimedOpenCreditPolicy.request(committed, creditInput, reviewed, emptyList(), false)
        assertEquals(GrantResult.Denied, m.result)
        assertTrue(counters(m, committed).isEmpty())
        assertTrue((m.openCredits ?: committed.openCredits)!!.active.isEmpty())
        assertTrue(m.focus.protection!!.pendingCreditEnds.isEmpty())
    }

    @Test fun CLOSED_R2p2N01_replayingTheSameReceiptAfterAZeroMinuteCommitChargesNothingAndReleasesNothing() {
        val review = creditSnapshot()
        val reviewed = reviewedCreditId(review)
        val first = TimedOpenCreditPolicy.request(review, creditInput, reviewed, emptyList(), false)
        assertEquals(GrantResult.OpenAllowed, first.result)
        val withGrant = applied(review, first)
        assertEquals(1, withGrant.openCredits!!.counters.getValue(pkg).used)

        val committed = withGrant.copy(times = listOf(TimeLimit(pkg, 0, BreakLevel.HARDCORE, "UTC")))
        // Replay after a lost acknowledgement / process death: same nonce, same frozen scope.
        val replay = TimedOpenCreditPolicy.request(committed, creditInput, reviewed, emptyList(), false)
        assertEquals(GrantResult.Denied, replay.result)
        assertEquals(1, counters(replay, committed).getValue(pkg).used)
        // A fresh reviewed action is equally refused.
        val fresh = TimedOpenCreditPolicy.request(committed, creditInput, reviewedCreditId(committed, 4), emptyList(), false)
        assertEquals(GrantResult.Denied, fresh.result)
        assertEquals(1, counters(fresh, committed).getValue(pkg).used)
    }

    @Test fun CLOSED_R2p2N01_sessionAddedBetweenReviewAndCommitDeniesWithoutCharging() {
        val review = creditSnapshot()
        val reviewed = reviewedCreditId(review)
        val session = BlockRule("session-timer-v2-late", "Late focus", BlockTargets(apps = setOf(AppRef(pkg))),
            breakLevel = BreakLevel.HARDCORE, expiresAtEpochMs = now.wallEpochMs + 600_000)
        val committed = review.copy(rules = listOf(session), editor = review.editor.copy(causes =
            review.editor.causes + (ruleCauseKey(session.id) to
                EditorCause(session.id, AnchoredInterval(now, 600_000, now.wallEpochMs + 600_000), "UTC"))))
        val m = TimedOpenCreditPolicy.request(committed, creditInput, reviewed, emptyList(), false)
        assertEquals(GrantResult.Denied, m.result)
        assertTrue(counters(m, committed).isEmpty())
    }

    @Test fun CLOSED_R2p2N01_trustedDayBoundaryBetweenReviewAndCommitInvalidatesTheReviewedScope() {
        val late = AccessClockStamp(java.time.Instant.parse("2026-10-29T23:59:00Z").toEpochMilli(), 10_000, "boot")
        val review = creditSnapshot(at = late)
        val reviewed = reviewedCreditId(review)
        val next = late.copy(wallEpochMs = late.wallEpochMs + 60_001, elapsedRealtimeMs = late.elapsedRealtimeMs + 60_001)
        val rolled = OpenCreditLedgerPolicy.reconcile(review.openCredits!!, next).next
        assertTrue("the trusted boundary must roll the quota period", rolled.periodId > review.openCredits!!.periodId)
        val nextCause = EditorCause("day-next", AnchoredInterval(next, rolled.reset.remainingToResetMs,
            next.wallEpochMs + rolled.reset.remainingToResetMs), "UTC")
        val committed = review.copy(openCredits = rolled, observedAt = next,
            editor = review.editor.copy(causes = review.editor.causes + (openCauseKey(pkg) to nextCause)))
        val m = TimedOpenCreditPolicy.request(committed, creditInput, reviewed, emptyList(), false)
        assertEquals(GrantResult.Denied, m.result)
        assertTrue(counters(m, committed).isEmpty())
    }

    @Test fun CLOSED_R2p2N01_unprovableMeasurementNeverPermitsNewAccess() {
        val committed = StorageState.Ready(listOf(TimeLimit(pkg, 1, BreakLevel.HARDCORE, "UTC")))
        for (quality in listOf(TrustedTimeLimitBudget.Measurement.ACCESS_MISSING,
            TrustedTimeLimitBudget.Measurement.QUERY_FAILED, TrustedTimeLimitBudget.Measurement.HISTORY_UNKNOWN,
            TrustedTimeLimitBudget.Measurement.STORAGE_UNAVAILABLE)) {
            assertFalse(quality.name, TimeLimitDomainReadiness.permitsNewAccess(pkg, committed, committed,
                TrustedTimeLimitBudget(pkg, 1, usedDay, 600_000L, quality), true, now))
        }
        assertFalse("unmeasured positive budget",
            TimeLimitDomainReadiness.permitsNewAccess(pkg, committed, committed, budget(1, remaining = null), true, now))
    }
}
