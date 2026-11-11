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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun snapshot(current: PomodoroPhase?) = historyRulesSnapshot(StorageState.Ready(listOf(rule(current))))

    @Test fun `a resting parent stays an active owner commitment`() {
        val rested = snapshot(firstRest)
        val ready = rested as HistoryRulesSnapshot.Ready
        assertEquals(listOf(id), ready.rules.map { it.id })
        assertTrue(ready.ownerInactiveIds.isEmpty())
        assertEquals(true, ready.rules.single().ownerActive)
        assertEquals(4, ready.rules.single().rounds)
        assertEquals(listOf("$id.rest1"), ready.rules.single().plannedRests.map { it.grant })
        assertEquals(base + 25 * minute, ready.rules.single().plannedRests.single().startsAt)
        assertEquals(base + 30 * minute, ready.rules.single().plannedRests.single().endsAt)

        // A finished chain is genuinely inactive and has every rest behind it.
        val finished = (snapshot(null) as HistoryRulesSnapshot.Ready)
        assertEquals(setOf(id), finished.ownerInactiveIds)
        assertTrue(finished.rules.isEmpty())
    }

    @Test fun `planned rests are recorded once as planned break pairs`() = runBlocking {
        var now = base + 25 * minute + 1_000
        val moving = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = Instant.ofEpochMilli(millis())
            override fun millis(): Long = now
        }
        val store = FocusHistoryStore(temporary.newFolder(), moving)
        val reconciler = HistoryReconciler(store, moving, { ZoneOffset.UTC })
        val rested = (snapshot(firstRest) as HistoryRulesSnapshot.Ready).rules
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
        reconciler.observe((snapshot(firstRest) as HistoryRulesSnapshot.Ready).rules)
        assertEquals(2, store.load().events.size)
        now = base + 31 * minute
        val focusAgain = (snapshot(secondFocus) as HistoryRulesSnapshot.Ready).rules
        reconciler.observe(focusAgain)
        reconciler.observe(focusAgain)
        reconciler.observe(focusAgain)
        events = store.load().events
        assertEquals(listOf("started", "break_started", "break_ended"), events.map { it.type })
        val closed = events.last()
        assertEquals("$id.rest1", closed.grant)
        assertEquals(base + 30 * minute, closed.at)
        assertEquals("planned", closed.kind)
        // Stable IDs are what repeated reconciles and restarts deduplicate on.
        assertEquals(events.map { it.id }.distinct().size, events.size)
    }

    @Test fun `a late observation keeps the logical boundary and records observedAt`() = runBlocking {
        var now = base + 40 * minute
        val moving = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = Instant.ofEpochMilli(millis())
            override fun millis(): Long = now
        }
        val store = FocusHistoryStore(temporary.newFolder(), moving)
        val reconciler = HistoryReconciler(store, moving, { ZoneOffset.UTC })
        // First observation ever: focus round 2, so rest 1 is already behind us.
        reconciler.observe((snapshot(secondFocus) as HistoryRulesSnapshot.Ready).rules)
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
