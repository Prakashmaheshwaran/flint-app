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

    private fun spend(now: AccessClockStamp, op: String = "op-1"): PassState.Known =
        (policy.consume(fresh, now, op, hasActiveCommitment = true) as Consumption.Consumed).state

    @Test fun `PC-39 same boot - denied at t+604799s, available at t+604800s`() {
        val state = spend(at(1_000L))
        val almost = policy.eligibility(state, at(1_000L + 604_799_000L))
        assertTrue(almost is Eligibility.CoolingDown && almost.remainingMs == 1_000L && !almost.clockUncertain)
        assertEquals(Eligibility.Available, policy.eligibility(state, at(1_000L + 604_800_000L)))
    }

    @Test fun `PC-40 no renewal from a week boundary or a wall-clock or zone change`() {
        val state = spend(at(0L, wallMs = 1_000L))
        // Same boot, wall clock pushed 8 days forward after 1 hour of real elapsed time.
        val e = policy.eligibility(state, at(3_600_000L, wallMs = 8 * 86_400_000L))
        assertTrue(e is Eligibility.CoolingDown && e.remainingMs == 604_800_000L - 3_600_000L)
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
        assertTrue(e is Eligibility.CoolingDown && !e.clockUncertain && e.remainingMs == 604_800_000L - 86_400_000L - 1_000L)
    }

    @Test fun `PC-45 a retry of the committed operation never spends again or extends`() {
        val state = spend(at(0L), op = "op-1")
        val retry = policy.consume(state, at(10_000L), "op-1", hasActiveCommitment = true)
        assertTrue(retry is Consumption.AlreadyConsumed)
        assertEquals(3_600_000L - 10_000L, policy.recoveryRemainingMs((retry as Consumption.AlreadyConsumed).recovery!!, at(10_000L)))
        val other = policy.consume(state, at(10_000L), "op-2", hasActiveCommitment = true)
        assertTrue(other is Consumption.Denied && other.reason == RollingEmergencyPassPolicy.Denial.COOLING_DOWN)
    }

    @Test fun `PC-47 unavailable pass state never becomes a fresh pass`() {
        assertEquals(Eligibility.Unavailable, policy.eligibility(PassState.Unavailable, at(0L)))
        val c = policy.consume(PassState.Unavailable, at(0L), "op", hasActiveCommitment = true)
        assertTrue(c is Consumption.Denied && c.reason == RollingEmergencyPassPolicy.Denial.UNAVAILABLE)
    }

    @Test fun `spend only when a personal commitment is active`() {
        val c = policy.consume(fresh, at(0L), "op", hasActiveCommitment = false)
        assertTrue(c is Consumption.Denied && c.reason == RollingEmergencyPassPolicy.Denial.NOTHING_TO_RECOVER)
    }

    @Test fun `recovery lasts 60 minutes, expires on reboot, and resume-now keeps the pass spent`() {
        val c = policy.consume(fresh, at(0L), "op", hasActiveCommitment = true) as Consumption.Consumed
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
        Operation(kind, causes.associateWith { 1L }, setOf("com.example.app"))

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
        val revised = Operation(OperationKind.BREAK, mapOf(x to 2L), setOf("com.example.app"))
        assertEquals(HarderWaitPolicy.Commit.Stale, policy.commit(req.ledger, revised, at(60_000L)))
    }

    @Test fun `a new natural occurrence starts at zero`() {
        val ledger = HarderWaitPolicy.Ledger(counts = mapOf(x to 2))
        val next = Occurrence("rule-x", "occ-2")
        val pruned = policy.pruned(ledger, setOf(next))
        assertEquals(60_000L, policy.requiredWaitMs(pruned, op(next)))
    }
}

class OpenCreditPolicyTest {
    private val policy = OpenCreditPolicy()
    private val app = "com.instagram.android"
    private val day = 20_000L
    private val hour = 3_600_000L

    private fun accept(
        counter: OpenCreditPolicy.Counter?,
        caps: List<Int> = listOf(3),
        live: OpenCreditPolicy.Grant? = null,
        others: List<String> = emptyList(),
        now: AccessClockStamp = at(0L),
        op: String = "op",
        untilMidnight: Long = 10 * hour,
        floor: Boolean = false,
        period: Long = day,
    ) = policy.accept(app, op, caps, counter, period, live, others, floor, now, untilMidnight)

    @Test fun `PC-28 cap 3 gives three 300-second credits and the fourth is denied`() {
        var counter = OpenCreditPolicy.Counter(app, day, 0)
        repeat(3) { i ->
            val a = accept(counter, op = "op-$i", now = at(i * 400_000L)) as OpenCreditPolicy.Acceptance.Accepted
            assertEquals(300_000L, a.grant.durationMs)
            counter = a.counter
        }
        assertEquals(3, counter.used)
        val fourth = accept(counter, op = "op-4", now = at(2_000_000L))
        assertTrue(fourth is OpenCreditPolicy.Acceptance.Denied && fourth.reason == OpenCreditPolicy.Denial.EXHAUSTED)
    }

    @Test fun `PC-30 the last credit is usable and reports zero remaining`() {
        val a = accept(OpenCreditPolicy.Counter(app, day, 2)) as OpenCreditPolicy.Acceptance.Accepted
        assertEquals(0, a.remainingCredits)
        assertEquals(300_000L, policy.remainingMs(a.grant, at(0L)))
    }

    @Test fun `PC-23 and PC-31 a live grant is never charged or extended again`() {
        val a = accept(OpenCreditPolicy.Counter(app, day, 0)) as OpenCreditPolicy.Acceptance.Accepted
        val again = accept(a.counter, live = a.grant, now = at(100_000L), op = "op-other")
        assertEquals(OpenCreditPolicy.Acceptance.AlreadyActive(a.grant, 200_000L), again)
        val retry = accept(a.counter, live = a.grant, now = at(400_000L), op = "op")
        assertTrue("retry of the same op after expiry is not a new charge", retry is OpenCreditPolicy.Acceptance.AlreadyActive)
    }

    @Test fun `PC-32 no charge while another cause still blocks, naming it`() {
        val d = accept(OpenCreditPolicy.Counter(app, day, 0), others = listOf("Deep work (Hardcore)"))
        assertEquals(OpenCreditPolicy.Acceptance.Denied(OpenCreditPolicy.Denial.BLOCKED_BY_OTHER_CAUSE, listOf("Deep work (Hardcore)")), d)
    }

    @Test fun `PC-34 overlapping caps use the minimum on one shared counter`() {
        val a = accept(OpenCreditPolicy.Counter(app, day, 1), caps = listOf(5, 2)) as OpenCreditPolicy.Acceptance.Accepted
        assertEquals(0, a.remainingCredits)
        val d = accept(a.counter, caps = listOf(5, 2), op = "op-2", now = at(400_000L))
        assertTrue(d is OpenCreditPolicy.Acceptance.Denied && d.reason == OpenCreditPolicy.Denial.EXHAUSTED)
    }

    @Test fun `PC-35 a credit near midnight is clipped at the boundary`() {
        val a = accept(OpenCreditPolicy.Counter(app, day, 0), untilMidnight = 120_000L) as OpenCreditPolicy.Acceptance.Accepted
        assertEquals(120_000L, a.grant.durationMs)
    }

    @Test fun `PC-36 the counter is per target and never reset by a clock moving back`() {
        val c = OpenCreditPolicy.Counter(app, day, 3)
        assertEquals(c, policy.rolled(c, day - 1))
        assertEquals(0, policy.rolled(c, day + 1).used)
    }

    @Test fun `PC-37 unavailable counter state denies without zero-used defaults`() {
        val d = accept(counter = null)
        assertTrue(d is OpenCreditPolicy.Acceptance.Denied && d.reason == OpenCreditPolicy.Denial.UNAVAILABLE)
    }

    @Test fun `zero cap, no limit and safety floor targets are never charged`() {
        assertEquals(OpenCreditPolicy.Denial.ZERO_CAP, (accept(OpenCreditPolicy.Counter(app, day, 0), caps = listOf(0)) as OpenCreditPolicy.Acceptance.Denied).reason)
        assertEquals(OpenCreditPolicy.Denial.NO_LIMIT, (accept(OpenCreditPolicy.Counter(app, day, 0), caps = emptyList()) as OpenCreditPolicy.Acceptance.Denied).reason)
        assertEquals(OpenCreditPolicy.Denial.SAFETY_FLOOR, (accept(OpenCreditPolicy.Counter(app, day, 0), floor = true) as OpenCreditPolicy.Acceptance.Denied).reason)
    }

    @Test fun `a grant ends after a reboot without refunding the credit`() {
        val a = accept(OpenCreditPolicy.Counter(app, day, 0)) as OpenCreditPolicy.Acceptance.Accepted
        assertEquals(0L, policy.remainingMs(a.grant, at(10L, boot = "boot-2")))
        assertEquals(1, a.counter.used)
    }
}

class CommitmentPolicyTest {
    private val policy = CommitmentPolicy()

    @Test fun `active matrix follows ADR-012 section 2`() {
        val weakening = listOf(Action.EDIT_WEAKEN, Action.TOGGLE_OFF, Action.DELETE)
        for (a in weakening) {
            assertEquals(Outcome.ALLOW, policy.decide(a, BreakLevel.EASY, active = true))
            assertEquals(Outcome.WAIT, policy.decide(a, BreakLevel.HARDER, active = true))
            assertEquals("PC-11/PC-15/PC-16 $a", Outcome.DEFER, policy.decide(a, BreakLevel.HARDCORE, active = true))
        }
        for (a in listOf(Action.STOP_EARLY, Action.ORDINARY_BREAK)) {
            assertEquals(Outcome.ALLOW, policy.decide(a, BreakLevel.EASY, active = true))
            assertEquals("PC-07 $a", Outcome.WAIT, policy.decide(a, BreakLevel.HARDER, active = true))
            assertEquals("PC-25 $a", Outcome.DENY, policy.decide(a, BreakLevel.HARDCORE, active = true))
        }
        assertEquals("PC-04", Outcome.DENY, policy.decide(Action.REPLACE_WEAKER, BreakLevel.HARDCORE, active = true))
        for (tier in BreakLevel.entries) {
            assertEquals(Outcome.ALLOW, policy.decide(Action.ACCEPT_OPEN_CREDIT, tier, active = true))
            assertEquals(Outcome.ALLOW, policy.decide(Action.EDIT_STRENGTHEN, tier, active = true))
            assertEquals(Outcome.ALLOW, policy.decide(Action.EMERGENCY_PASS, tier, active = true))
        }
    }

    @Test fun `PC-13 inactive templates are fully editable at every tier`() {
        for (tier in BreakLevel.entries) {
            for (a in listOf(Action.EDIT_WEAKEN, Action.TOGGLE_OFF, Action.DELETE, Action.EDIT_STRENGTHEN)) {
                assertEquals("$tier $a", Outcome.ALLOW, policy.decide(a, tier, active = false))
            }
            assertEquals(Outcome.NOT_APPLICABLE, policy.decide(Action.STOP_EARLY, tier, active = false))
            assertEquals(Outcome.NOT_APPLICABLE, policy.decide(Action.ORDINARY_BREAK, tier, active = false))
        }
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

    @Test fun `PC-11 shorten, remove targets, add allowed apps, lower tier, move window or zone weakens`() {
        assertEquals(EditKind.WEAKEN, policy.classifyEdit(base, base.copy(apps = setOf("a"))))
        assertEquals(EditKind.WEAKEN, policy.classifyEdit(base, base.copy(domains = emptySet())))
        assertEquals(EditKind.WEAKEN, policy.classifyEdit(base, base.copy(tier = BreakLevel.EASY)))
        assertEquals(EditKind.WEAKEN, policy.classifyEdit(base, base.copy(window = Window(10 * 60, 17 * 60, setOf(1, 2, 3, 4, 5), "Europe/Berlin"))))
        assertEquals(EditKind.WEAKEN, policy.classifyEdit(base, base.copy(window = base.window!!.copy(zoneId = "America/New_York"))))
        assertEquals(EditKind.WEAKEN, policy.classifyEdit(base, base.copy(allowListMode = true)))
        val allow = base.copy(allowListMode = true, apps = setOf("phone-helper"))
        assertEquals(EditKind.WEAKEN, policy.classifyEdit(allow, allow.copy(apps = setOf("phone-helper", "chat"))))
        assertEquals(EditKind.STRENGTHEN, policy.classifyEdit(allow, allow.copy(apps = emptySet())))
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
}
