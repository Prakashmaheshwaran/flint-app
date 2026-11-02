package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.blocking.engine.CommitmentPolicy.Action
import com.flint.peakfocus.blocking.engine.CommitmentPolicy.EditKind
import com.flint.peakfocus.blocking.engine.CommitmentPolicy.Outcome
import com.flint.peakfocus.blocking.engine.CommitmentPolicy.ProtectionShape
import com.flint.peakfocus.blocking.engine.CommitmentPolicy.Window
import com.flint.peakfocus.blocking.engine.HarderWaitPolicy.Occurrence
import com.flint.peakfocus.blocking.engine.HarderWaitPolicy.Operation
import com.flint.peakfocus.blocking.engine.HarderWaitPolicy.OperationKind
import com.flint.peakfocus.blocking.engine.RollingEmergencyPassPolicy.Consumption
import com.flint.peakfocus.blocking.engine.RollingEmergencyPassPolicy.Eligibility
import com.flint.peakfocus.blocking.engine.RollingEmergencyPassPolicy.PassState
import com.flint.peakfocus.core.model.AccessClockStamp
import com.flint.peakfocus.core.model.BreakLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val BOOT = "boot-1"
private fun at(elapsedMs: Long, wallMs: Long = 1_800_000_000_000L + elapsedMs, boot: String = BOOT) =
    AccessClockStamp(wallEpochMs = wallMs, elapsedRealtimeMs = elapsedMs, bootSessionId = boot)

/** ADR-012 §4–§6 constants are cross-platform fixture values. */
class ProtectionContractConstantsTest {
    @Test fun `contract constants match ADR-012`() {
        assertEquals(300_000L, ProtectionContract.ORDINARY_BREAK_MS)
        assertEquals(300_000L, ProtectionContract.OPEN_CREDIT_MS)
        assertEquals(listOf(60_000L, 300_000L, 900_000L), ProtectionContract.HARDER_WAITS_MS)
        assertEquals(604_800_000L, ProtectionContract.PASS_COOLDOWN_MS)
        assertEquals(3_600_000L, ProtectionContract.PASS_RECOVERY_MS)
    }
}

class RollingEmergencyPassPolicyTest {
    private val policy = RollingEmergencyPassPolicy()
    private val fresh = PassState.Known()
    private val scope = RollingEmergencyPassPolicy.RecoveryScope(setOf("rule-a", "session-1"), setOf("session-1"))

    private fun spend(now: AccessClockStamp, op: String = "op-1"): PassState.Known =
        (policy.consume(fresh, now, op, scope) as Consumption.Consumed).state

    @Test fun `PC-39 same boot - denied at t+604799s, available at t+604800s`() {
        val state = spend(at(1_000L))
        val almost = policy.eligibility(state, at(1_000L + 604_799_000L))
        assertTrue(almost is Eligibility.CoolingDown && almost.remainingMs == 1_000L && !almost.clockUncertain)
        assertEquals(Eligibility.Available, policy.eligibility(state, at(1_000L + 604_800_000L)))
    }

    @Test fun `PC-40 no renewal from a Monday boundary, and PC-54-55 clock edits cannot shorten it`() {
        // Spent Sunday 2026-11-01 20:00 UTC; the old ISO-week policy would refill at Monday 00:00.
        val sunday = 1_793_563_200_000L
        val state = spend(at(0L, wallMs = sunday))
        val monday = policy.eligibility(state, at(14_400_000L, wallMs = sunday + 14_400_000L))
        assertTrue(monday is Eligibility.CoolingDown && monday.remainingMs == 604_800_000L - 14_400_000L)
        // Same boot, wall clock pushed 8 days forward after 1 hour of real elapsed time: still cooling,
        // and the date estimate is flagged uncertain (wall/elapsed discontinuity > 5 s).
        val forward = policy.eligibility(state, at(3_600_000L, wallMs = sunday + 8 * 86_400_000L))
        assertTrue(forward is Eligibility.CoolingDown && forward.remainingMs == 604_800_000L - 3_600_000L && forward.clockUncertain)
        // Clock set back: same remaining time, uncertain estimate.
        val back = policy.eligibility(state, at(3_600_000L, wallMs = sunday - 86_400_000L))
        assertTrue(back is Eligibility.CoolingDown && back.remainingMs == 604_800_000L - 3_600_000L && back.clockUncertain)
        // No edit: certain.
        val normal = policy.eligibility(state, at(3_600_000L, wallMs = sunday + 3_600_000L))
        assertTrue(normal is Eligibility.CoolingDown && !normal.clockUncertain)
    }

    @Test fun `PC-55 the uncertainty survives re-anchoring after a clock edit`() {
        val state = spend(at(0L, wallMs = 1_000L))
        val edited = at(60_000L, wallMs = 1_000L + 2 * 86_400_000L)
        val reanchored = policy.checkpointed(state, edited)
        val later = policy.eligibility(reanchored, at(120_000L, wallMs = 1_000L + 2 * 86_400_000L + 60_000L))
        assertTrue(later is Eligibility.CoolingDown && later.clockUncertain)
    }

    @Test fun `a finished cooldown clears on checkpoint`() {
        val state = spend(at(0L))
        assertNull((policy.checkpointed(state, at(604_800_000L)) as PassState.Known).cooldown)
    }

    @Test fun `a reboot credits no powered-off time and marks the estimate uncertain`() {
        val state = spend(at(0L))
        val before = policy.checkpointed(state, at(86_400_000L)) // one observed day
        val afterReboot = policy.eligibility(before, at(5L, boot = "boot-2"))
        assertTrue(afterReboot is Eligibility.CoolingDown)
        afterReboot as Eligibility.CoolingDown
        assertEquals(604_800_000L - 86_400_000L, afterReboot.remainingMs)
        assertTrue(afterReboot.clockUncertain)
        val rebased = policy.checkpointed(before, at(5L, boot = "boot-2"))
        val e = policy.eligibility(rebased, at(5L + 1_000L, boot = "boot-2"))
        // Monotonic again on the new boot, but the date estimate stays uncertain (sticky, §9).
        assertTrue(e is Eligibility.CoolingDown && e.clockUncertain && e.remainingMs == 604_800_000L - 86_400_000L - 1_000L)
    }

    @Test fun `PC-45 a retry of the committed operation never spends again or extends`() {
        val state = spend(at(0L), op = "op-1")
        val retry = policy.consume(state, at(10_000L), "op-1", scope)
        assertTrue(retry is Consumption.AlreadyConsumed)
        assertEquals(3_600_000L - 10_000L, policy.recoveryRemainingMs((retry as Consumption.AlreadyConsumed).recovery!!, at(10_000L)))
        val other = policy.consume(state, at(10_000L), "op-2", scope)
        assertTrue(other is Consumption.Denied && other.reason == RollingEmergencyPassPolicy.Denial.COOLING_DOWN)
    }

    @Test fun `PC-47 unavailable pass state never becomes a fresh pass`() {
        assertEquals(Eligibility.Unavailable, policy.eligibility(PassState.Unavailable, at(0L)))
        val c = policy.consume(PassState.Unavailable, at(0L), "op", scope)
        assertTrue(c is Consumption.Denied && c.reason == RollingEmergencyPassPolicy.Denial.UNAVAILABLE)
    }

    @Test fun `spend only when a commitment is active and the full scope is known`() {
        val none = policy.consume(fresh, at(0L), "op", RollingEmergencyPassPolicy.RecoveryScope(emptySet(), emptySet()))
        assertTrue(none is Consumption.Denied && none.reason == RollingEmergencyPassPolicy.Denial.NOTHING_TO_RECOVER)
        val unknown = policy.consume(fresh, at(0L), "op", scope = null)
        assertTrue(unknown is Consumption.Denied && unknown.reason == RollingEmergencyPassPolicy.Denial.UNAVAILABLE)
        val ok = policy.consume(fresh, at(0L), "op", scope) as Consumption.Consumed
        assertEquals(scope, ok.recovery.scope)
    }

    @Test fun `recovery lasts 60 minutes, expires on reboot, and resume-now keeps the pass spent`() {
        val c = policy.consume(fresh, at(0L), "op", scope) as Consumption.Consumed
        assertEquals(3_600_000L, policy.recoveryRemainingMs(c.recovery, at(0L)))
        assertEquals(0L, policy.recoveryRemainingMs(c.recovery, at(3_600_000L)))
        assertEquals(0L, policy.recoveryRemainingMs(c.recovery, at(10L, boot = "boot-2")))
        val resumed = policy.resumeNow(c.state)
        assertNull(resumed.recovery)
        assertTrue(policy.eligibility(resumed, at(1L)) is Eligibility.CoolingDown)
    }
}

class HarderWaitPolicyTest {
    private val policy = HarderWaitPolicy()
    private val x = Occurrence("rule-x", "occ-1")
    private val y = Occurrence("rule-y", "occ-7")
    private fun op(vararg causes: Occurrence, kind: OperationKind = OperationKind.BREAK) =
        Operation(kind, causes.associateWith { "rev-1" }, setOf("com.example.app"))

    private fun completeOnce(ledger: HarderWaitPolicy.Ledger, operation: Operation, startMs: Long): Pair<HarderWaitPolicy.Ledger, Long> {
        val req = policy.request(ledger, operation, at(startMs))
        val wait = req.pending.requiredMs
        val done = policy.commit(req.ledger, operation, at(startMs + wait)) as HarderWaitPolicy.Commit.Committed
        return done.ledger to wait
    }

    @Test fun `PC-18 one occurrence waits 60s, 300s, 900s, then 900s`() {
        var ledger = HarderWaitPolicy.Ledger()
        val waits = mutableListOf<Long>()
        var t = 0L
        repeat(4) {
            val (next, wait) = completeOnce(ledger, op(x), t)
            ledger = next; waits += wait; t += wait + 1L
        }
        assertEquals(listOf(60_000L, 300_000L, 900_000L, 900_000L), waits)
    }

    @Test fun `PC-19 overlapping causes wait the maximum and each count increments once`() {
        val ledger = HarderWaitPolicy.Ledger(counts = mapOf(x to 0, y to 2))
        val (after, wait) = completeOnce(ledger, op(x, y), 0L)
        assertEquals(900_000L, wait)
        assertEquals(mapOf(x to 1, y to 3), after.counts)
    }

    @Test fun `PC-20 cancel consumes nothing, requesting again restarts the full wait`() {
        val ledger = HarderWaitPolicy.Ledger(counts = mapOf(x to 1))
        val first = policy.request(ledger, op(x), at(0L))
        val cancelled = policy.cancel(first.ledger)
        assertEquals(mapOf(x to 1), cancelled.counts)
        assertNull(cancelled.pending)
        val again = policy.request(cancelled, op(x), at(200_000L))
        assertEquals(300_000L, again.remainingMs)
    }

    @Test fun `PC-21 a durable pending wait survives recreation and still needs confirmation`() {
        val req = policy.request(HarderWaitPolicy.Ledger(), op(x), at(0L))
        // "Process death": the persisted ledger is all that remains.
        val restored = req.ledger.copy()
        val mid = policy.commit(restored, op(x), at(30_000L))
        assertEquals(HarderWaitPolicy.Commit.NotYet(30_000L), mid)
        assertTrue(policy.commit(restored, op(x), at(60_000L)) is HarderWaitPolicy.Commit.Committed)
    }

    @Test fun `a reboot cancels the pending wait without incrementing`() {
        val req = policy.request(HarderWaitPolicy.Ledger(counts = mapOf(x to 1)), op(x), at(0L))
        assertEquals(HarderWaitPolicy.Commit.Stale, policy.commit(req.ledger, op(x), at(10L, boot = "boot-2")))
        val fresh = policy.request(req.ledger, op(x), at(10L, boot = "boot-2"))
        assertEquals(300_000L, fresh.remainingMs)
    }

    @Test fun `a different operation or revision needs a new full wait`() {
        val req = policy.request(HarderWaitPolicy.Ledger(), op(x), at(0L))
        val stop = op(x, kind = OperationKind.STOP)
        assertEquals(HarderWaitPolicy.Commit.Stale, policy.commit(req.ledger, stop, at(60_000L)))
        val revised = Operation(OperationKind.BREAK, mapOf(x to "rev-2"), setOf("com.example.app"))
        assertEquals(HarderWaitPolicy.Commit.Stale, policy.commit(req.ledger, revised, at(60_000L)))
    }

    @Test fun `a new natural occurrence starts at zero and only proven-terminal counts are dropped`() {
        val ledger = HarderWaitPolicy.Ledger(counts = mapOf(x to 2, y to 1))
        val next = Occurrence("rule-x", "occ-2")
        assertEquals(60_000L, policy.requiredWaitMs(ledger, op(next)))
        val pruned = policy.withoutTerminated(ledger, setOf(x))
        assertEquals(mapOf(y to 1), pruned.counts)
        assertEquals("a suspended (not terminal) occurrence keeps its n", 900_000L, policy.requiredWaitMs(policy.withoutTerminated(ledger, emptySet()), op(x)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an operation with no Harder cause is rejected`() {
        Operation(OperationKind.BREAK, emptyMap())
    }
}

class OpenCreditPolicyTest {
    private val policy = OpenCreditPolicy()
    private val app = "com.instagram.android"
    private val hour = 3_600_000L

    private fun counter(used: Int = 0, untilReset: Long = 10 * hour, at: AccessClockStamp = at(0L), ops: Set<String> = emptySet()) =
        OpenCreditPolicy.Counter(app, periodId = 20_000L, used = used, reset = OpenCreditPolicy.ResetClock(at, untilReset), consumedOperationIds = ops)

    private fun accept(
        counter: OpenCreditPolicy.Counter?,
        caps: List<Int> = listOf(3),
        live: OpenCreditPolicy.Grant? = null,
        others: List<String> = emptyList(),
        now: AccessClockStamp = at(0L),
        op: String = "op",
        nextMidnight: Long = 24 * hour,
        floor: Boolean = false,
    ) = policy.accept(app, op, caps, counter, live, others, floor, now, nextMidnight)

    @Test fun `PC-28 cap 3 gives three 300-second credits and the fourth is denied`() {
        var c = counter()
        repeat(3) { i ->
            val a = accept(c, op = "op-$i", now = at(i * 400_000L)) as OpenCreditPolicy.Acceptance.Accepted
            assertEquals(300_000L, a.grant.durationMs)
            c = a.counter
        }
        assertEquals(3, c.used)
        val fourth = accept(c, op = "op-4", now = at(2_000_000L))
        assertTrue(fourth is OpenCreditPolicy.Acceptance.Denied && fourth.reason == OpenCreditPolicy.Denial.EXHAUSTED)
    }

    @Test fun `PC-29 one credit left - the first operation wins, the second spends nothing`() {
        val first = accept(counter(used = 2), op = "surface-a") as OpenCreditPolicy.Acceptance.Accepted
        val second = accept(first.counter, live = first.grant, op = "surface-b")
        assertEquals(OpenCreditPolicy.Acceptance.AlreadyActive(first.grant, 300_000L), second)
        val afterExpiry = accept(first.counter, op = "surface-b", now = at(400_000L))
        assertTrue(afterExpiry is OpenCreditPolicy.Acceptance.Denied && afterExpiry.reason == OpenCreditPolicy.Denial.EXHAUSTED)
        assertEquals(3, first.counter.used)
    }

    @Test fun `PC-30 the last credit is usable and reports zero remaining`() {
        val a = accept(counter(used = 2)) as OpenCreditPolicy.Acceptance.Accepted
        assertEquals(0, a.remainingCredits)
        assertEquals(300_000L, policy.remainingMs(a.grant, at(0L)))
    }

    @Test fun `PC-23 a different request during a live grant spends nothing`() {
        val a = accept(counter()) as OpenCreditPolicy.Acceptance.Accepted
        assertEquals(OpenCreditPolicy.Acceptance.AlreadyActive(a.grant, 200_000L), accept(a.counter, live = a.grant, now = at(100_000L), op = "op-other"))
    }

    @Test fun `PC-31 a duplicate callback never charges, even after its grant expired and was pruned`() {
        val a = accept(counter(), op = "op-1") as OpenCreditPolicy.Acceptance.Accepted
        val live = accept(a.counter, live = a.grant, now = at(10_000L), op = "op-1")
        assertEquals(OpenCreditPolicy.Acceptance.Duplicate("op-1", a.grant), live)
        val late = accept(a.counter, live = null, now = at(310_000L), op = "op-1")
        assertEquals(OpenCreditPolicy.Acceptance.Duplicate("op-1", null), late)
    }

    @Test fun `a duplicate just after the reset is still no charge`() {
        val a = accept(counter(untilReset = 60_000L), op = "op-late") as OpenCreditPolicy.Acceptance.Accepted
        val afterReset = accept(a.counter, now = at(65_000L), op = "op-late")
        assertEquals(OpenCreditPolicy.Acceptance.Duplicate("op-late", null), afterReset)
    }

    @Test fun `an unknown next boundary never loops resets`() {
        val c = counter(used = 2, untilReset = 0L)
        val r1 = policy.rolled(c, at(10L), msUntilNextQuotaMidnight = 0L)
        val r2 = policy.rolled(r1, at(20L), msUntilNextQuotaMidnight = 0L)
        assertEquals(2, r2.used)
        assertEquals(c.periodId, r2.periodId)
    }

    @Test fun `a counter for another target is unavailable, and remaining credits display`() {
        val other = counter().copy(target = "com.other")
        assertEquals(OpenCreditPolicy.Denial.UNAVAILABLE, (accept(other) as OpenCreditPolicy.Acceptance.Denied).reason)
        assertEquals(1, policy.remainingCredits(listOf(3, 5), counter(used = 2), at(0L), 24 * hour))
        assertNull(policy.remainingCredits(emptyList(), counter(), at(0L), 24 * hour))
    }

    @Test fun `PC-32 no charge while another cause still blocks, naming it`() {
        val d = accept(counter(), others = listOf("Deep work (Hardcore)"))
        assertEquals(OpenCreditPolicy.Acceptance.Denied(OpenCreditPolicy.Denial.BLOCKED_BY_OTHER_CAUSE, listOf("Deep work (Hardcore)")), d)
    }

    @Test fun `PC-34 overlapping caps use the minimum on one shared counter`() {
        val a = accept(counter(used = 1), caps = listOf(5, 2)) as OpenCreditPolicy.Acceptance.Accepted
        assertEquals(0, a.remainingCredits)
        val d = accept(a.counter, caps = listOf(5, 2), op = "op-2", now = at(400_000L))
        assertTrue(d is OpenCreditPolicy.Acceptance.Denied && d.reason == OpenCreditPolicy.Denial.EXHAUSTED)
    }

    @Test fun `PC-35 a credit near the reset is clipped at the boundary, then one reset happens`() {
        val a = accept(counter(untilReset = 120_000L)) as OpenCreditPolicy.Acceptance.Accepted
        assertEquals(120_000L, a.grant.durationMs)
        val rolled = policy.rolled(a.counter, at(120_000L), msUntilNextQuotaMidnight = 24 * hour)
        assertEquals(0, rolled.used)
        assertEquals(20_001L, rolled.periodId)
        assertTrue(rolled.consumedOperationIds.isEmpty())
    }

    @Test fun `PC-54 a same-boot forward wall-clock jump never mints a fresh quota`() {
        val c = counter(used = 3)
        // One real minute later the wall clock says it is tomorrow.
        val jumped = at(60_000L, wallMs = 1_800_000_000_000L + 2 * 86_400_000L)
        val d = accept(c, now = jumped)
        assertTrue(d is OpenCreditPolicy.Acceptance.Denied && d.reason == OpenCreditPolicy.Denial.EXHAUSTED)
        assertEquals(3, policy.rolled(c, jumped, 24 * hour).used)
    }

    @Test fun `a reboot resumes remaining-to-reset without crediting powered-off time`() {
        val c = counter(used = 3, untilReset = 2 * hour)
        val observed = policy.rolled(c, at(hour), 24 * hour) // one hour observed
        val afterReboot = policy.rolled(observed, at(5L, boot = "boot-2"), 24 * hour)
        assertEquals(3, afterReboot.used)
        assertEquals(hour, afterReboot.reset.remainingToResetMs)
        val reset = policy.rolled(afterReboot, at(5L + hour, boot = "boot-2"), 24 * hour)
        assertEquals("exactly one reset, never replayed", 0, reset.used)
        assertEquals(20_001L, reset.periodId)
    }

    @Test fun `PC-36 the counter survives rule edits - it is keyed by target, not rule`() {
        val c = counter(used = 2)
        val sameDay = accept(c, caps = listOf(2), now = at(10_000L))
        assertTrue(sameDay is OpenCreditPolicy.Acceptance.Denied && sameDay.reason == OpenCreditPolicy.Denial.EXHAUSTED)
    }

    @Test fun `PC-37 unavailable counter state denies without zero-used defaults`() {
        val d = accept(counter = null)
        assertTrue(d is OpenCreditPolicy.Acceptance.Denied && d.reason == OpenCreditPolicy.Denial.UNAVAILABLE)
    }

    @Test fun `zero cap, no limit, floor targets and the exact boundary are never charged`() {
        assertEquals(OpenCreditPolicy.Denial.ZERO_CAP, (accept(counter(), caps = listOf(0)) as OpenCreditPolicy.Acceptance.Denied).reason)
        assertEquals(OpenCreditPolicy.Denial.NO_LIMIT, (accept(counter(), caps = emptyList()) as OpenCreditPolicy.Acceptance.Denied).reason)
        assertEquals(OpenCreditPolicy.Denial.SAFETY_FLOOR, (accept(counter(), floor = true) as OpenCreditPolicy.Acceptance.Denied).reason)
        val atBoundary = accept(counter(untilReset = 0L), nextMidnight = 0L)
        assertEquals(OpenCreditPolicy.Denial.PERIOD_BOUNDARY, (atBoundary as OpenCreditPolicy.Acceptance.Denied).reason)
    }

    @Test fun `a grant ends after a reboot without refunding the credit`() {
        val a = accept(counter()) as OpenCreditPolicy.Acceptance.Accepted
        assertEquals(0L, policy.remainingMs(a.grant, at(10L, boot = "boot-2")))
        assertEquals(1, a.counter.used)
    }
}

class CommitmentPolicyTest {
    private val policy = CommitmentPolicy()
    private val now0 = at(0L)
    private val active = CommitmentPolicy.CauseActivity.ScheduledOccurrence(
        enabled = true,
        current = AnchoredInterval(now0, remainingAtAnchorMs = 3_600_000L, wallEndEpochMs = now0.wallEpochMs + 3_600_000L),
        now = now0,
    )
    private val inactive = CommitmentPolicy.CauseActivity.ScheduledOccurrence(enabled = true, current = null, now = now0)

    @Test fun `active matrix follows ADR-012 section 2`() {
        for (a in listOf(Action.TOGGLE_OFF, Action.DELETE)) {
            assertEquals(Outcome.ALLOW, policy.decide(a, BreakLevel.EASY, active))
            assertEquals(Outcome.WAIT, policy.decide(a, BreakLevel.HARDER, active))
            assertEquals("PC-16 $a", Outcome.DEFER, policy.decide(a, BreakLevel.HARDCORE, active))
        }
        for (a in listOf(Action.STOP_EARLY, Action.ORDINARY_BREAK)) {
            assertEquals(Outcome.ALLOW, policy.decide(a, BreakLevel.EASY, active))
            assertEquals("PC-07 $a", Outcome.WAIT, policy.decide(a, BreakLevel.HARDER, active))
            assertEquals("PC-25 $a", Outcome.DENY, policy.decide(a, BreakLevel.HARDCORE, active))
        }
        for (tier in BreakLevel.entries) {
            assertEquals(Outcome.ALLOW, policy.decide(Action.START_ADDITIONAL, tier, active))
            assertEquals("no Harder wait for quota credits", Outcome.ALLOW, policy.decide(Action.ACCEPT_OPEN_CREDIT, tier, active))
            assertEquals(Outcome.ALLOW, policy.decide(Action.EMERGENCY_PASS, tier, active))
        }
    }

    @Test fun `PC-13 inactive templates are fully editable at every tier`() {
        for (tier in BreakLevel.entries) {
            for (a in listOf(Action.TOGGLE_OFF, Action.DELETE, Action.EMERGENCY_PASS)) {
                assertEquals("$tier $a", Outcome.ALLOW, policy.decide(a, tier, inactive))
            }
            for (a in listOf(Action.STOP_EARLY, Action.ORDINARY_BREAK, Action.ACCEPT_OPEN_CREDIT)) {
                assertEquals(Outcome.NOT_APPLICABLE, policy.decide(a, tier, inactive))
            }
            assertEquals(Outcome.ALLOW, policy.decideEdit(base.copy(tier = tier), base.copy(tier = BreakLevel.EASY, apps = emptySet()), inactive))
        }
        assertEquals(Outcome.ALLOW_AFTER_DISCLOSURE, policy.decide(Action.START_ADDITIONAL, BreakLevel.HARDCORE, inactive))
    }

    @Test fun `OS-owned controls are never gated`() {
        for (tier in BreakLevel.entries) for (act in listOf(active, inactive)) {
            for (a in listOf(Action.UNINSTALL_FLINT, Action.REVOKE_OS_PERMISSION, Action.CHANGE_CLOCK_OR_ZONE)) {
                assertEquals(Outcome.OS_OWNED, policy.decide(a, tier, act))
            }
        }
    }

    @Test fun `PC-15 a daily limit below quota is still active, so weakening it defers`() {
        val limit = base.copy(window = null, budget = 30)
        val belowQuota = CommitmentPolicy.CauseActivity.DailyLimit(enabled = true)
        assertEquals(Outcome.DEFER, policy.decideEdit(limit, limit.copy(budget = 45), belowQuota))
        assertEquals(Outcome.DEFER, policy.decide(Action.DELETE, BreakLevel.HARDCORE, belowQuota))
        assertEquals(Outcome.ALLOW, policy.decideEdit(limit, limit.copy(budget = 15), belowQuota))
    }

    @Test fun `sessions are active until their anchored end`() {
        val interval = AnchoredInterval(at(0L), remainingAtAnchorMs = 10_000L, wallEndEpochMs = at(0L).wallEpochMs + 10_000L)
        assertTrue(CommitmentPolicy.CauseActivity.Session(interval, at(5_000L)).isActive)
        val over = CommitmentPolicy.CauseActivity.Session(interval, at(10_000L))
        assertEquals(Outcome.NOT_APPLICABLE, policy.decide(Action.STOP_EARLY, BreakLevel.HARDCORE, over))
    }

    @Test fun `a same-boot wall-clock jump cannot end an active Hardcore session (section 9)`() {
        val interval = AnchoredInterval(at(0L), remainingAtAnchorMs = 3_600_000L, wallEndEpochMs = at(0L).wallEpochMs + 3_600_000L)
        // One real minute later the wall clock is pushed a day forward, same boot.
        val jumped = CommitmentPolicy.CauseActivity.Session(interval, at(60_000L, wallMs = at(0L).wallEpochMs + 86_400_000L))
        assertTrue(jumped.isActive)
        assertEquals(Outcome.DENY, policy.decide(Action.STOP_EARLY, BreakLevel.HARDCORE, jumped))
        assertEquals(Outcome.DEFER, policy.decideEdit(base, base.copy(apps = setOf("a")), jumped))
        // After a reboot the bounded fallback can only shorten: wall end in the past expires it.
        val rebooted = CommitmentPolicy.CauseActivity.Session(interval, at(5L, wallMs = at(0L).wallEpochMs + 4_000_000L, boot = "boot-2"))
        assertTrue(!rebooted.isActive)
    }

    @Test fun `PC-11 weakening an active Hardcore occurrence defers, Harder waits, Easy allows`() {
        val weaker = base.copy(apps = setOf("a"))
        assertEquals(Outcome.DEFER, policy.decideEdit(base, weaker, active))
        assertEquals(Outcome.WAIT, policy.decideEdit(base.copy(tier = BreakLevel.HARDER), weaker.copy(tier = BreakLevel.HARDER), active))
        assertEquals(Outcome.ALLOW, policy.decideEdit(base.copy(tier = BreakLevel.EASY), weaker.copy(tier = BreakLevel.EASY), active))
        assertEquals("PC-12 strengthening allowed", Outcome.ALLOW, policy.decideEdit(base, base.copy(apps = setOf("a", "b", "c")), active))
        assertEquals("rename allowed", Outcome.ALLOW, policy.decideEdit(base, base.copy(name = "x"), active))
        val harder = base.copy(tier = BreakLevel.HARDER)
        assertEquals("raising the tier needs disclosure", Outcome.ALLOW_AFTER_DISCLOSURE, policy.decideEdit(harder, base, active))
    }

    @Test fun `PC-04 PC-05 PC-06 replacement must provably dominate`() {
        val session = base.copy(window = null, endsAtEpochMs = 10_000L)
        assertEquals("PC-05 longer, superset", Outcome.ALLOW, policy.decideReplacement(session, session.copy(apps = setOf("a", "b", "c"), endsAtEpochMs = 20_000L), active))
        assertEquals("PC-06 drops B", Outcome.DENY, policy.decideReplacement(session, session.copy(apps = setOf("a"), endsAtEpochMs = 20_000L), active))
        assertEquals("PC-04 weaker tier", Outcome.DENY, policy.decideReplacement(session, session.copy(tier = BreakLevel.EASY), active))
        assertEquals(Outcome.WAIT, policy.decideReplacement(session.copy(tier = BreakLevel.HARDER), session.copy(tier = BreakLevel.EASY), active))
        assertEquals(Outcome.NOT_APPLICABLE, policy.decideReplacement(session, session.copy(apps = setOf("a")), inactive))
    }

    private val base = ProtectionShape(
        name = "Deep work",
        tier = BreakLevel.HARDCORE,
        allowListMode = false,
        apps = setOf("a", "b"),
        domains = setOf("reddit.com"),
        window = Window(9 * 60, 17 * 60, setOf(1, 2, 3, 4, 5), "Europe/Berlin"),
        budget = null,
    )

    @Test fun `rename is cosmetic`() {
        assertEquals(EditKind.COSMETIC, policy.classifyEdit(base, base.copy(name = "Focus")))
    }

    @Test fun `PC-12 adding targets, extending the window or raising the tier strengthens`() {
        assertEquals(EditKind.STRENGTHEN, policy.classifyEdit(base, base.copy(apps = setOf("a", "b", "c"))))
        assertEquals(EditKind.STRENGTHEN, policy.classifyEdit(base, base.copy(window = Window(8 * 60, 18 * 60, setOf(1, 2, 3, 4, 5, 6), "Europe/Berlin"))))
        assertEquals(EditKind.STRENGTHEN, policy.classifyEdit(base.copy(tier = BreakLevel.HARDER), base))
    }

    @Test fun `PC-11 shorten, remove targets, add allowed apps, lower tier, move window, fewer days or zone weakens`() {
        assertEquals(EditKind.WEAKEN, policy.classifyEdit(base, base.copy(apps = setOf("a"))))
        assertEquals(EditKind.WEAKEN, policy.classifyEdit(base, base.copy(domains = emptySet())))
        assertEquals(EditKind.WEAKEN, policy.classifyEdit(base, base.copy(tier = BreakLevel.EASY)))
        assertEquals(EditKind.WEAKEN, policy.classifyEdit(base, base.copy(window = Window(10 * 60, 17 * 60, setOf(1, 2, 3, 4, 5), "Europe/Berlin"))))
        assertEquals(EditKind.WEAKEN, policy.classifyEdit(base, base.copy(window = base.window!!.copy(days = setOf(1, 2, 3)))))
        val everyDay = base.copy(window = base.window!!.copy(days = emptySet()))
        assertEquals(EditKind.WEAKEN, policy.classifyEdit(everyDay, everyDay.copy(window = everyDay.window!!.copy(days = setOf(1, 2, 3, 4, 5)))))
        assertEquals(EditKind.WEAKEN, policy.classifyEdit(base, base.copy(window = base.window!!.copy(zoneId = "America/New_York"))))
        assertEquals(EditKind.WEAKEN, policy.classifyEdit(base, base.copy(allowListMode = true)))
        val allow = base.copy(allowListMode = true, apps = setOf("phone-helper"))
        assertEquals(EditKind.WEAKEN, policy.classifyEdit(allow, allow.copy(apps = setOf("phone-helper", "chat"))))
        assertEquals(EditKind.STRENGTHEN, policy.classifyEdit(allow, allow.copy(apps = emptySet())))
        assertEquals("allow-list destinations may not change", EditKind.WEAKEN, policy.classifyEdit(allow, allow.copy(domains = setOf("reddit.com", "news.example"))))
        val session = base.copy(window = null, endsAtEpochMs = 10_000L)
        assertEquals(EditKind.WEAKEN, policy.classifyEdit(session, session.copy(endsAtEpochMs = 9_000L)))
        assertEquals(EditKind.STRENGTHEN, policy.classifyEdit(session, session.copy(endsAtEpochMs = 12_000L)))
    }

    @Test fun `PC-15 raising or removing a budget weakens, lowering strengthens`() {
        val limit = base.copy(window = null, budget = 30)
        assertEquals(EditKind.WEAKEN, policy.classifyEdit(limit, limit.copy(budget = 45)))
        assertEquals(EditKind.WEAKEN, policy.classifyEdit(limit, limit.copy(budget = null)))
        assertEquals(EditKind.STRENGTHEN, policy.classifyEdit(limit, limit.copy(budget = 15)))
    }

    @Test fun `overnight windows compare without crossing midnight shape changes`() {
        val sleep = base.copy(window = Window(22 * 60, 6 * 60, emptySet(), "Europe/Berlin"))
        assertEquals(EditKind.STRENGTHEN, policy.classifyEdit(sleep, sleep.copy(window = Window(21 * 60, 7 * 60, emptySet(), "Europe/Berlin"))))
        assertEquals(EditKind.WEAKEN, policy.classifyEdit(sleep, sleep.copy(window = Window(9 * 60, 23 * 60, emptySet(), "Europe/Berlin"))))
    }

    @Test fun `session and occurrence bounds`() {
        assertTrue(isValidNewSessionDuration(24 * 3_600_000L))
        assertTrue(!isValidNewSessionDuration(24 * 3_600_000L + 1))
        assertTrue(isValidOccurrenceDuration(25 * 3_600_000L))
        assertTrue(!isValidOccurrenceDuration(26 * 3_600_000L + 1))
        assertTrue(!isValidNewSessionDuration(0L))
    }

    @Test fun `clock discontinuity threshold is 5 seconds`() {
        assertTrue(!clockDiscontinuity(at(0L, wallMs = 1_000L), at(10_000L, wallMs = 16_000L)))
        assertTrue(clockDiscontinuity(at(0L, wallMs = 1_000L), at(10_000L, wallMs = 16_001L)))
    }

    @Test fun `trusted elapsed rejects blank boots and backwards time`() {
        assertNull(trustedElapsedMs(at(0L, boot = ""), at(10L, boot = "")))
        assertNull(trustedElapsedMs(at(10L), at(5L)))
        assertEquals(5L, trustedElapsedMs(at(5L), at(10L)))
    }
}
