package com.flint.peakfocus.core.history

import com.flint.peakfocus.core.model.AccessClockStamp
import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.BlockTargets
import com.flint.peakfocus.core.model.PomodoroPhase
import com.flint.peakfocus.core.model.PomodoroPhaseKind
import com.flint.peakfocus.core.model.PomodoroPlan
import com.flint.peakfocus.core.model.RuleEvaluation
import com.flint.peakfocus.core.model.RuleRuntime
import com.flint.peakfocus.core.model.StorageState
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * F01 §7: one `started` event for the parent (with its planned rounds), a `planned` break pair
 * per observed rest, rest time excluded from focus totals, and round credit only from observed
 * rests or a committed completion — never from a deadline.
 */
class PomodoroHistoryTest {
    @get:Rule val temporary = TemporaryFolder()

    private val minute = 60_000L
    private val base = 1_793_196_000_000L
    private val plan = PomodoroPlan(focusMinutes = 25, restMinutes = 5, rounds = 4)
    private val chainEnd = base + plan.totalMs
    private val id = "session-timer-v2-$base-pomodoro"

    private fun phase(kind: PomodoroPhaseKind, round: Int, startMin: Long, endMin: Long) =
        plan.phases.single {
            it.kind == kind && it.round == round && it.startMs == startMin * minute
        }.also { assertEquals(endMin * minute, it.endMs) }

    private val firstRest = phase(PomodoroPhaseKind.REST, 1, 25, 30)
    private val secondFocus = phase(PomodoroPhaseKind.FOCUS, 2, 30, 55)

    private fun projection(current: PomodoroPhase?) = RuleEvaluation(
        active = current?.isFocus == true,
        endsAtEpochMs = chainEnd,
        nextBoundaryEpochMs = current?.let { base + it.endMs },
        phase = current,
    )

    private fun rule(current: PomodoroPhase?) = BlockRule(
        id = id,
        name = "Pomodoro",
        targets = BlockTargets(),
        expiresAtEpochMs = chainEnd,
        pomodoro = plan,
        runtime = RuleRuntime(id, AccessClockStamp(base, 10_000, "boot"), plan.totalMs, chainEnd, "UTC",
            AccessClockStamp(base, 10_000, "boot"), evaluation = projection(current)),
    )

    /** The owner's read-time projection [elapsedMs] of trusted chain time after [base]. */
    private fun ruleAt(elapsedMs: Long): BlockRule {
        val phase = plan.phaseAt(elapsedMs)
        return rule(phase)
    }

    private fun snapshot(current: PomodoroPhase?) = historyRulesSnapshot(StorageState.Ready(listOf(rule(current))))

    private fun snapshotAt(elapsedMs: Long) = historyRulesSnapshot(StorageState.Ready(listOf(ruleAt(elapsedMs))))

    private fun readyAt(elapsedMs: Long) = (snapshotAt(elapsedMs) as HistoryRulesSnapshot.Ready).rules

    private class MutableClock(var now: Long) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(millis())
        override fun millis(): Long = now
    }

    private fun restGrant(round: Int) = "$id.rest$round"

    @Test fun `a resting parent stays an active owner commitment`() {
        val rested = snapshot(firstRest)
        val ready = rested as HistoryRulesSnapshot.Ready
        assertEquals(listOf(id), ready.rules.map { it.id })
        assertTrue(ready.ownerInactiveIds.isEmpty())
        assertEquals(true, ready.rules.single().ownerActive)
        assertEquals(4, ready.rules.single().rounds)
        assertEquals(firstRest.index, ready.rules.single().phaseIndex)
        // The whole plan is visible: the reconciler decides which boundaries have passed.
        assertEquals(listOf("$id.rest1", "$id.rest2", "$id.rest3"),
            ready.rules.single().plannedRests.map { it.grant })
        assertEquals(base + 25 * minute, ready.rules.single().plannedRests.first().startsAt)
        assertEquals(base + 30 * minute, ready.rules.single().plannedRests.first().endsAt)

        // A finished chain is genuinely inactive but stays visible for its rests.
        val finished = (snapshot(null) as HistoryRulesSnapshot.Ready)
        assertEquals(setOf(id), finished.ownerInactiveIds)
        assertEquals(listOf(id), finished.rules.map { it.id })
        assertEquals(false, finished.rules.single().ownerActive)
        assertNull(finished.rules.single().phaseIndex)
    }

    @Test fun `a rest to focus transition is a distinct observable value`() {
        // The snapshot is the only thing the owner flow deduplicates on: without the phase in
        // it, rest 1 and focus 2 read identically and the rest end was never observed.
        val rested = (snapshot(firstRest) as HistoryRulesSnapshot.Ready)
        val focusAgain = (snapshot(secondFocus) as HistoryRulesSnapshot.Ready)
        assertNotEquals(rested, focusAgain)
        assertNotEquals(rested.rules, focusAgain.rules)
        assertEquals(firstRest.index, rested.rules.single().phaseIndex)
        assertEquals(secondFocus.index, focusAgain.rules.single().phaseIndex)
        assertEquals(rested.rules.single().plannedRests, focusAgain.rules.single().plannedRests)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `every rest pair of a completed chain is recorded on time`() = runTest {
        var clockNow = base
        val clock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = Instant.ofEpochMilli(millis())
            override fun millis(): Long = clockNow
        }
        val store = FocusHistoryStore(temporary.newFolder(), clock)
        val reconciler = HistoryReconciler(store, clock, { ZoneOffset.UTC })
        val seen = mutableListOf<HistoryRulesSnapshot>()
        val reading = backgroundScope.launch {
            historyOwnerSnapshots(
                read = { snapshotAt(testScheduler.currentTime) },
                subscribe = { _callback ->
                    val unsubscribe: () -> Unit = {}
                    unsubscribe
                },
            ).collect { seen += it }
        }
        runCurrent()
        // Every phase boundary must survive the real owner flow's distinctUntilChanged.
        val boundaries = listOf(0L, 25L, 30L, 55L, 60L, 85L, 90L, 115L).map { it * minute }
        boundaries.forEach { at ->
            if (at > testScheduler.currentTime) advanceTimeBy(at - testScheduler.currentTime)
            advanceTimeBy(500); runCurrent()
        }
        reading.cancel(); runCurrent()

        val delivered = seen.map { (it as HistoryRulesSnapshot.Ready).rules.single().phaseIndex }
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, null), delivered)

        // Replay exactly what the flow delivered, each at its own logical observation time.
        seen.forEach { snapshot ->
            val phaseIndex = (snapshot as HistoryRulesSnapshot.Ready).rules.single().phaseIndex
            clockNow = base + (plan.phases.getOrNull(phaseIndex ?: -1)?.startMs ?: plan.totalMs) + 500
            runBlocking { reconciler.collectSnapshots(flowOf(snapshot)) }
        }

        val events = store.load().events
        val starts = events.filter { it.type == "break_started" }
        val ends = events.filter { it.type == "break_ended" }
        assertEquals(listOf("break_started", "break_ended", "break_started", "break_ended",
            "break_started", "break_ended"),
            events.filter { it.type.startsWith("break_") }.sortedBy { it.at }.map { it.type })
        assertEquals(3, starts.size); assertEquals(3, ends.size)
        // No dangling pair, all three on time, at the logical rest boundaries.
        assertEquals(starts.map { it.grant }.toSet(), ends.map { it.grant }.toSet())
        assertEquals(listOf("$id.rest1", "$id.rest2", "$id.rest3"), starts.map { it.grant })
        assertEquals(starts.map { it.grant }, ends.map { it.grant })
        assertTrue("an on-time read is not inferred", (starts + ends).none { it.inferred })
        assertEquals((1..3).map { "$id.rest$it" }, starts.map { it.grant })
        assertEquals((1..3).map { base + (listOf(25, 55, 85)[it - 1]) * minute }, starts.map { it.at })
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `a chain that died mid-run still records every rest when first seen after its end`() = runTest {
        val clock = MutableClock(base + 50 * minute)
        val store = FocusHistoryStore(temporary.newFolder(), clock)
        val reconciler = HistoryReconciler(store, clock, { ZoneOffset.UTC })
        // The process was alive inside focus round 2, so rest 1 was recorded then.
        reconciler.observe(readyAt(50 * minute))
        // Killed here; restarted at t=120, past the chain end. The rule is still stored, so the
        // plan and its committed anchor are still the authority for the rests it never saw.
        clock.now = base + 120 * minute
        reconciler.observe(readyAt(120 * minute))
        // The owner delivers the natural terminal for the same logical end.
        store.update { listOf(HistoryEvent(type = "completed", session = id, at = chainEnd, tz = "UTC",
            originRoute = "editor_terminal", reason = "deadline")) }

        val events = store.load().events
        val rests = events.filter { it.type.startsWith("break_") }
        assertEquals(6, rests.size)
        assertTrue("a rest boundary this read missed is inferred", rests.all { it.inferred })
        assertEquals(setOf("$id.rest1", "$id.rest2", "$id.rest3"), rests.mapNotNull { it.grant }.toSet())
        // A finished plan is not a recovered plan: the post-end read must not rewrite it.
        assertTrue(events.none { it.type == "rescheduled" || it.type == "ended_early" })

        val session = FocusHistoryAggregator.sessions(events, clock.millis(), emptySet()).single()
        assertEquals(SessionOutcome.COMPLETED, session.outcome)
        // 115 committed minutes minus the three five-minute rests; rest is never focus time.
        assertEquals(100 * minute, session.focusedMs)
        assertEquals(4, session.pomodoroRoundsCompleted)
    }

    @Test fun `a planned rest that has not started yet is not recorded`() {
        val rest = PlannedRest(round = 1, grant = restGrant(1),
            startsAt = base + 25 * minute, endsAt = base + 30 * minute)
        // A backward wall change can leave the plan's rest in the future: no event yet.
        assertTrue(plannedRestEvents(id, listOf(rest), emptyList(), base + 10 * minute, "UTC").isEmpty())
        assertEquals(listOf(HistoryEventType.BREAK_STARTED),
            plannedRestEvents(id, listOf(rest), emptyList(), base + 25 * minute, "UTC").map { it.knownType })
        assertEquals(listOf(HistoryEventType.BREAK_STARTED, HistoryEventType.BREAK_ENDED),
            plannedRestEvents(id, listOf(rest), emptyList(), base + 30 * minute, "UTC").map { it.knownType })
    }

    @Test fun `planned rests are recorded once as planned break pairs`() = runBlocking {
        var now = base + 25 * minute + 1_000
        val moving = MutableClock(now)
        val store = FocusHistoryStore(temporary.newFolder(), moving)
        val reconciler = HistoryReconciler(store, moving, { ZoneOffset.UTC })
        val rested = readyAt(25 * minute + 1_000)
        reconciler.observe(rested)
        reconciler.observe(rested)
        var events = store.load().events
        assertEquals(listOf("started", "break_started"), events.map { it.type })
        val opened = events.last()
        assertEquals("planned", opened.kind)
        assertEquals("$id.rest1", opened.grant)
        assertEquals(base + 25 * minute, opened.at)
        assertEquals(base + 30 * minute, opened.breakEnd)
        assertFalse("an on-time observation is not inferred", opened.inferred)
        assertEquals(4, events.first().rounds)
        assertEquals(chainEnd, events.first().plannedEnd)

        // Still inside the rest: no end yet. Past it: exactly one end, and repeats dedupe.
        now = base + 29 * minute
        moving.now = now
        reconciler.observe(readyAt(29 * minute))
        assertEquals(2, store.load().events.size)
        now = base + 30 * minute + 1_000
        moving.now = now
        val focusAgain = readyAt(30 * minute + 1_000)
        reconciler.observe(focusAgain)
        reconciler.observe(focusAgain)
        reconciler.observe(focusAgain)
        events = store.load().events
        assertEquals(listOf("started", "break_started", "break_ended"), events.map { it.type })
        val closed = events.last()
        assertEquals("$id.rest1", closed.grant)
        assertEquals(base + 30 * minute, closed.at)
        assertEquals("planned", closed.kind)
        assertFalse("the reconciler was running at the boundary", closed.inferred)
        // Stable IDs are what repeated reconciles and restarts deduplicate on.
        assertEquals(events.map { it.id }.distinct().size, events.size)
    }

    @Test fun `a late observation keeps the logical boundary and records observedAt`() = runBlocking {
        val now = base + 40 * minute
        val clock = MutableClock(now)
        val store = FocusHistoryStore(temporary.newFolder(), clock)
        val reconciler = HistoryReconciler(store, clock, { ZoneOffset.UTC })
        // First observation ever: focus round 2, so rest 1 is already behind us.
        reconciler.observe(readyAt(40 * minute))
        val events = store.load().events
        val opened = events.single { it.type == "break_started" }
        assertEquals(base + 25 * minute, opened.at)
        assertTrue(opened.inferred)
        assertEquals(now, opened.observedAt)
        // Its end is also already observed at this point.
        assertEquals(base + 30 * minute, events.single { it.type == "break_ended" }.at)
    }

    @Test fun `rest time is excluded from focus totals and rounds come only from evidence`() {
        val started = HistoryEvent(type = "started", session = id, at = base, tz = "UTC",
            plannedEnd = chainEnd, tier = "harder", source = "unknown", name = "Pomodoro", rounds = 4)
        val opened = HistoryEvent(type = "break_started", session = id, at = base + 25 * minute, tz = "UTC",
            breakEnd = base + 30 * minute, kind = "planned", grant = "$id.rest1")
        val closed = HistoryEvent(type = "break_ended", session = id, at = base + 30 * minute, tz = "UTC",
            kind = "planned", grant = "$id.rest1")

        // Running inside focus round 2: 26 minutes of focus, one round proven by rest 1.
        val running = FocusHistoryAggregator.sessions(listOf(started, opened, closed),
            base + 31 * minute, setOf(id)).single()
        assertEquals(SessionOutcome.RUNNING, running.outcome)
        assertEquals(26 * minute, running.focusedMs)
        assertNull("a planned rest is not an ordinary break", running.breaks)
        assertEquals(4, running.pomodoroRounds)
        assertEquals(1, running.pomodoroRoundsCompleted)
        assertEquals("Pomodoro · 1 of 4 rounds", FocusHistoryText.rounds(running))

        // A committed completion proves every planned round.
        val completed = started.copy(id = "done-terminal", session = "done", type = "completed", at = chainEnd)
        val done = FocusHistoryAggregator.sessions(listOf(started.copy(session = "done"), opened.copy(session = "done"),
            closed.copy(session = "done"), completed), chainEnd, emptySet()).single()
        assertEquals(SessionOutcome.COMPLETED, done.outcome)
        assertEquals(4, done.pomodoroRoundsCompleted)
        assertEquals(110 * minute, done.focusedMs)

        // A deadline alone proves no round, no completion and no focus coverage.
        val planOnly = FocusHistoryAggregator.sessions(listOf(started), chainEnd, emptySet()).single()
        assertEquals(SessionOutcome.UNCONFIRMED, planOnly.outcome)
        assertEquals(4, planOnly.pomodoroRounds)
        assertEquals(0, planOnly.pomodoroRoundsCompleted)
        assertEquals(0L, planOnly.focusedMs)
    }
}
