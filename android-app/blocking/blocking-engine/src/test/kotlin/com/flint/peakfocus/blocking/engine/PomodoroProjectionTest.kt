package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.*
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F01 enforcement (§4 of `docs/architecture/pomodoro-chain.md`): a Pomodoro parent's own rule
 * enforces only during focus, the phase follows trusted elapsed time, and every phase boundary is
 * the next evaluation boundary.
 */
class PomodoroProjectionTest {
    private val minute = 60_000L
    private val zone = ZoneId.of("UTC")
    private val start = Instant.parse("2026-10-28T10:00:00Z").toEpochMilli()
    private val anchor = AccessClockStamp(start, 1_000, "boot-a")
    private val plan = PomodoroPlan(focusMinutes = 25, restMinutes = 5, rounds = 4)
    private val engine = BlockDecisionEngine()

    private fun parent() = BlockRule(
        id = "session-timer-v2-${start}-parent",
        name = "Pomodoro",
        targets = BlockTargets(apps = setOf(AppRef("com.target"))),
        expiresAtEpochMs = start + plan.totalMs,
        pomodoro = plan,
        runtime = RuleRuntime(occurrenceId = "session-timer-v2-${start}-parent", anchor = anchor,
            remainingAtAnchorMs = plan.totalMs, wallEndEpochMs = start + plan.totalMs,
            zoneId = "UTC", timeline = anchor),
    )

    private fun other() = BlockRule(
        id = "session-timer-v2-${start}-other",
        name = "Other",
        targets = BlockTargets(apps = setOf(AppRef("com.target"))),
        expiresAtEpochMs = start + 10 * 60 * minute,
        runtime = RuleRuntime(occurrenceId = "session-timer-v2-${start}-other", anchor = anchor,
            remainingAtAnchorMs = 10 * 60 * minute, wallEndEpochMs = start + 10 * 60 * minute,
            zoneId = "UTC", timeline = anchor),
    )

    /** The same boot, [minutes] of trusted elapsed time after the anchor. */
    private fun after(minutes: Double): AccessClockStamp {
        val elapsed = (minutes * minute).toLong()
        return AccessClockStamp(start + elapsed, anchor.elapsedRealtimeMs + elapsed, "boot-a")
    }

    private fun decide(rules: List<BlockRule>, now: AccessClockStamp): Verdict =
        engine.decide("com.target", null, rules, "com.flint", -1, -1, now.wallEpochMs)

    @Test fun `focus blocks and rest allows`() {
        val focus = RuleClockProjection.project(parent(), after(10.0))
        assertTrue(focus.runtime!!.evaluation!!.active)
        assertEquals(PomodoroPhaseKind.FOCUS, focus.runtime!!.evaluation!!.phase!!.kind)
        assertTrue(decide(listOf(focus), after(10.0)) is Verdict.Block)

        val rest = RuleClockProjection.project(parent(), after(26.0))
        assertFalse(rest.runtime!!.evaluation!!.active)
        assertEquals(PomodoroPhaseKind.REST, rest.runtime!!.evaluation!!.phase!!.kind)
        assertEquals(Verdict.Allow, decide(listOf(rest), after(26.0)))
    }

    @Test fun `a rest keeps an overlapping non-pomodoro rule enforcing`() {
        val rest = RuleClockProjection.project(parent(), after(26.0))
        val overlap = RuleClockProjection.project(other(), after(26.0))
        assertFalse(rest.runtime!!.evaluation!!.active)
        assertTrue(overlap.runtime!!.evaluation!!.active)
        val verdict = decide(listOf(rest, overlap), after(26.0))
        assertTrue(verdict is Verdict.Block)
        assertEquals(other().id, (verdict as Verdict.Block).ruleId)
    }

    @Test fun `the chain end stays the commitment end during a rest`() {
        val rest = RuleClockProjection.project(parent(), after(26.0))
        assertEquals(after(26.0).wallEpochMs + 89 * minute, rest.expiresAtEpochMs)
        assertEquals(after(26.0).wallEpochMs + 89 * minute, rest.runtime!!.evaluation!!.endsAtEpochMs)
    }

    @Test fun `the next evaluation boundary is the phase end`() {
        // 26 min in, rest 1 runs to 30 min: the boundary is exactly the rest end.
        val rest = RuleClockProjection.project(parent(), after(26.0))
        val boundary = NextEvaluationBoundary.next(after(26.0).wallEpochMs, zone, "com.target", listOf(rest))
        assertEquals(after(26.0).wallEpochMs + 4 * minute, boundary)
        assertEquals(rest.runtime!!.evaluation!!.nextBoundaryEpochMs, boundary)

        // 10 min in, focus 1 runs to 25 min: the boundary is the rest start.
        val focus = RuleClockProjection.project(parent(), after(10.0))
        assertEquals(after(10.0).wallEpochMs + 15 * minute,
            NextEvaluationBoundary.next(after(10.0).wallEpochMs, zone, "com.target", listOf(focus)))

        // 114 min in, focus 4 runs to the chain end.
        val last = RuleClockProjection.project(parent(), after(114.0))
        assertEquals(after(114.0).wallEpochMs + minute,
            NextEvaluationBoundary.next(after(114.0).wallEpochMs, zone, "com.target", listOf(last)))
    }

    @Test fun `the cached projection never reuses a phase past its boundary`() {
        fun stamp(elapsedMs: Long) = AccessClockStamp(start + elapsedMs, anchor.elapsedRealtimeMs + elapsedMs, "boot-a")
        // 50 ms before the rest starts, then 50 ms after: the cache entry is younger than its
        // 100 ms reuse window, so only the boundary guard prevents a stale focus read.
        var now = stamp(25 * minute - 50)
        val cache = CachedRuleClockProjection { now }
        val rules = listOf(parent())
        assertEquals(PomodoroPhaseKind.FOCUS, cache.project(rules).single().runtime!!.evaluation!!.phase!!.kind)
        now = stamp(25 * minute)
        assertEquals(PomodoroPhaseKind.REST, cache.project(rules).single().runtime!!.evaluation!!.phase!!.kind)
        now = stamp(30 * minute)
        assertEquals(PomodoroPhaseKind.FOCUS, cache.project(rules).single().runtime!!.evaluation!!.phase!!.kind)
    }

    @Test fun `a same-boot wall jump neither moves nor restarts the phase`() {
        val restStart = after(26.0)
        // Forward wall jump: same elapsed time, so the same phase.
        val forward = restStart.copy(wallEpochMs = restStart.wallEpochMs + 5 * 3_600_000L)
        val forwardPhase = RuleClockProjection.project(parent(), forward).runtime!!.evaluation!!
        assertEquals(PomodoroPhaseKind.REST, forwardPhase.phase!!.kind)
        assertFalse(forwardPhase.active)
        // Forward jump past the chain end by wall only: elapsed time still decides.
        val far = restStart.copy(wallEpochMs = restStart.wallEpochMs + 30 * 3_600_000L)
        assertNotNullPhase(RuleClockProjection.project(parent(), far).runtime!!.evaluation!!)
        // Backward wall jump: the rest did not restart, and focus 4 has not returned.
        val backward = restStart.copy(wallEpochMs = start - 3_600_000L)
        val backwardPhase = RuleClockProjection.project(parent(), backward).runtime!!.evaluation!!
        assertEquals(PomodoroPhaseKind.REST, backwardPhase.phase!!.kind)
        assertEquals(1, backwardPhase.phase!!.round)
    }

    @Test fun `reboot re-anchoring never restarts a completed round or lengthens a rest`() {
        // Reboot 26 minutes of wall time later: bounded fallback uses min(saved, wall end − now),
        // so the chain resumes inside rest 1 instead of restarting focus round 1.
        val reboot = AccessClockStamp(start + 26 * minute, 400, "boot-b")
        val projection = RuleClockProjection.project(parent(), reboot).runtime!!.evaluation!!
        assertEquals(PomodoroPhaseKind.REST, projection.phase!!.kind)
        assertEquals(1, projection.phase!!.round)
        assertEquals(reboot.wallEpochMs + 89 * minute, projection.endsAtEpochMs)
        assertEquals(reboot.wallEpochMs + 4 * minute, projection.nextBoundaryEpochMs)

        // Reboot an hour of wall time later: rounds 1–2 and both rests are behind us.
        val later = AccessClockStamp(start + 60 * minute, 400, "boot-b")
        val resumed = RuleClockProjection.project(parent(), later).runtime!!.evaluation!!
        assertEquals(PomodoroPhaseKind.FOCUS, resumed.phase!!.kind)
        assertEquals(3, resumed.phase!!.round)
        assertEquals(later.wallEpochMs + 55 * minute, resumed.endsAtEpochMs)

        // The bounded §9 fallback is min(saved remaining, wall end − now), so a wall clock that
        // moved backwards across a reboot lengthens the chain (fail-closed, more protection).
        // It never shortens it and never drops the frozen plan.
        val rolledBack = AccessClockStamp(start + 10 * minute, 400, "boot-b")
        val moved = RuleClockProjection.project(parent(), rolledBack).runtime!!.evaluation!!
        assertEquals(PomodoroPhaseKind.FOCUS, moved.phase!!.kind)
        assertEquals(1, moved.phase!!.round)
        assertEquals(rolledBack.wallEpochMs + 105 * minute, moved.endsAtEpochMs)
        assertEquals(plan, RuleClockProjection.project(parent(), rolledBack).pomodoro)
        // A reboot observed past the chain end is finished, with no round revived.
        val late = AccessClockStamp(start + 10 * 60 * minute, 400, "boot-b")
        val finished = RuleClockProjection.project(parent(), late).runtime!!.evaluation!!
        assertFalse(finished.active)
        assertNull(finished.phase)
    }

    @Test fun `finished chains enforce nothing and expose no boundary`() {
        val finished = after(115.0)
        val projected = RuleClockProjection.project(parent(), finished)
        val evaluation = projected.runtime!!.evaluation!!
        assertFalse(evaluation.active)
        assertNull(evaluation.phase)
        assertEquals(Verdict.Allow, decide(listOf(projected), finished))
        assertTrue(projected.isExpired(finished.wallEpochMs))
    }

    @Test fun `a malformed runtime still fails closed instead of inventing an open chain`() {
        val malformed = parent().copy(runtime = parent().runtime!!.copy(wallEndEpochMs = null))
        val evaluation = RuleClockProjection.project(malformed, after(26.0)).runtime!!.evaluation!!
        assertFalse(evaluation.active)
        assertNull(evaluation.phase)
    }

    private fun assertNotNullPhase(evaluation: RuleEvaluation) {
        assertEquals(PomodoroPhaseKind.REST, evaluation.phase!!.kind)
        assertFalse(evaluation.active)
    }
}
