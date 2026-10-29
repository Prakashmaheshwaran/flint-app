package com.flint.peakfocus

import com.flint.peakfocus.core.datastore.*
import com.flint.peakfocus.core.history.FocusHistoryAggregator
import com.flint.peakfocus.core.history.FocusHistoryStore
import com.flint.peakfocus.core.history.HistoryEvent
import com.flint.peakfocus.core.history.HistoryClearPhase
import com.flint.peakfocus.core.history.HistoryReconciler
import com.flint.peakfocus.core.history.HistoryRule
import com.flint.peakfocus.core.history.SessionOutcome
import com.flint.peakfocus.core.model.*
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FocusHistoryTerminalDeliveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val start = 1_800_000_000_000L
    private val hour = 3_600_000L
    private val terminal = EditorTerminalEvent("editor:completed:session-late", "session-late", "session-late",
        EditorTerminalKind.COMPLETED, AccessClockStamp(start + hour, hour, "boot"),
        AccessClockStamp(start + 2 * hour, 2 * hour, "boot"), 1L, false)
    private class MutableClock(var now: Long) : Clock() {
        override fun instant() = Instant.ofEpochMilli(now)
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId): Clock = this
    }

    @Test fun failedHistoryAppendNeverAcknowledgesOwnerIntent() = runTest {
        val owner = PendingOwner(terminal)
        assertEquals(0, projectEditorHistoryTerminals(owner, { false }, ZoneOffset.UTC))
        assertEquals(listOf(terminal), owner.pending)
        assertEquals(0, owner.acknowledgements)
        assertEquals(1, projectEditorHistoryTerminals(owner, { true }, ZoneOffset.UTC))
        assertTrue(owner.pending.isEmpty())
        assertEquals(1, owner.acknowledgements)
    }

    @Test fun committedHistorySurvivesFailedAckAndLateReplayWithoutDoubleCounting() = runTest {
        val clock = Clock.fixed(Instant.ofEpochMilli(start + 2 * hour), ZoneOffset.UTC)
        val store = FocusHistoryStore(temporary.newFolder(), clock)
        assertTrue(store.append(HistoryEvent(type = "started", session = terminal.sessionId, at = start,
            plannedEnd = start + hour, tz = "UTC", name = "Focus", tier = "easy", source = "manual")))
        val owner = PendingOwner(terminal).apply { failAck = true }
        runCatching { projectEditorHistoryTerminals(owner, store::update, ZoneOffset.UTC) }
        assertEquals(listOf(terminal), owner.pending)
        assertEquals(3, store.load().events.size)
        owner.failAck = false
        projectEditorHistoryTerminals(owner, store::update, ZoneOffset.UTC)
        assertTrue(owner.pending.isEmpty())
        val events = store.load().events
        assertEquals(3, events.size)
        val record = events.single { it.id == terminal.id }
        assertEquals(terminal.logicalEnd.wallEpochMs, record.at)
        assertEquals(terminal.observedAt.wallEpochMs, record.observedAt)
        val session = FocusHistoryAggregator.sessions(events, clock.millis(), emptySet()).single()
        assertEquals(SessionOutcome.COMPLETED, session.outcome)
        assertEquals(hour, session.focusedMs)
    }

    @Test fun terminalBeforeRuleObservationCommitsMissedFinalDeadlineAtomically() = runTest {
        val clock = Clock.fixed(Instant.ofEpochMilli(start + 3 * hour), ZoneOffset.UTC)
        for (oldDeadline in listOf(start + hour, start + 3 * hour)) {
            val store = FocusHistoryStore(temporary.newFolder(), clock)
            assertTrue(store.append(HistoryEvent(type = "started", session = terminal.sessionId, at = start,
                plannedEnd = oldDeadline, tz = "UTC", name = "Focus", tier = "easy", source = "manual")))
            val finalDeadline = start + 2 * hour
            val final = terminal.copy(logicalEnd = AccessClockStamp(finalDeadline, 2 * hour, "boot"),
                observedAt = AccessClockStamp(clock.millis(), 3 * hour, "boot"))
            val owner = PendingOwner(final)
            projectEditorHistoryTerminals(owner, store::update, ZoneOffset.UTC)
            assertTrue(owner.pending.isEmpty())
            // A later rule observation cannot repair a terminal's plan; delivery owns it.
            val reconciler = com.flint.peakfocus.core.history.HistoryReconciler(store, clock, { ZoneOffset.UTC })
            reconciler.observe(emptyList())
            val session = FocusHistoryAggregator.sessions(store.load().events, clock.millis(), emptySet()).single()
            assertEquals(SessionOutcome.COMPLETED, session.outcome)
            assertEquals(finalDeadline, session.plannedEnd)
            assertEquals(2 * hour, session.focusedMs)
            assertEquals(3, store.load().events.size)
        }
    }

    @Test fun failedAckThenClearSuppressesOldTerminalReplayAfterStoreRestart() = runTest {
        val clock = Clock.fixed(Instant.ofEpochMilli(start + 2 * hour), ZoneOffset.UTC)
        val store = FocusHistoryStore(temporary.newFolder(), clock)
        assertTrue(store.append(HistoryEvent(type = "started", session = terminal.sessionId, at = start,
            plannedEnd = start + hour, tz = "UTC", name = "Focus", tier = "easy", source = "manual")))
        val owner = PendingOwner(terminal).apply { failAck = true }
        runCatching { projectEditorHistoryTerminals(owner, store::update, ZoneOffset.UTC) }
        assertEquals(3, store.load().events.size)
        assertTrue(store.clear(emptySet()))
        val restarted = FocusHistoryStore(store.directory, clock)
        assertEquals(clock.millis(), restarted.load().clearedAt)
        owner.failAck = false
        projectEditorHistoryTerminals(owner, restarted::update, ZoneOffset.UTC)
        assertTrue(owner.pending.isEmpty())
        assertTrue(restarted.load().events.isEmpty())
    }

    @Test fun clearRetainsRunningIdentityForLateAuthoritativeShortening() = runTest {
        val clock = Clock.fixed(Instant.ofEpochMilli(start + 2 * hour), ZoneOffset.UTC)
        val store = FocusHistoryStore(temporary.newFolder(), clock)
        assertTrue(store.append(HistoryEvent(type = "started", session = terminal.sessionId, at = start,
            plannedEnd = start + 3 * hour, tz = "UTC", name = "Focus", tier = "easy", source = "manual")))
        assertTrue(store.clear(setOf(terminal.sessionId)))
        val restarted = FocusHistoryStore(store.directory, clock)
        val owner = PendingOwner(terminal)
        projectEditorHistoryTerminals(owner, restarted::update, ZoneOffset.UTC)
        assertTrue(owner.pending.isEmpty())
        val session = FocusHistoryAggregator.sessions(restarted.load().events, clock.millis(), emptySet()).single()
        assertEquals(SessionOutcome.COMPLETED, session.outcome)
        assertEquals(hour, session.focusedMs)
    }

    @Test fun rollbackClearAndRecoveryNeverRearmOrReplayRemovedTimer() = runTest {
        for (phase in listOf<HistoryClearPhase?>(null, HistoryClearPhase.INTENT_COMMITTED, HistoryClearPhase.LOG_REPLACED)) {
            val clock = MutableClock(start + 2 * hour)
            val store = FocusHistoryStore(temporary.newFolder(), clock)
            val id = "session-timer-v2-$start-old"
            val old = terminal.copy(id = "editor:completed:$id", sessionId = id, occurrenceId = id)
            assertTrue(store.append(HistoryEvent(type = "started", session = id, at = start,
                plannedEnd = start + hour, tz = "UTC", name = "Focus", tier = "easy", source = "manual")))
            val owner = PendingOwner(old).apply { failAck = true }
            runCatching { projectEditorHistoryTerminals(owner, store::update, ZoneOffset.UTC) }
            assertEquals(3, store.load().events.size)
            clock.now = start + hour / 2
            FocusHistoryStore(store.directory, clock, clearPhase = { if (it == phase) error("interrupted") })
                .clear(emptySet())
            val reopened = FocusHistoryStore(store.directory, clock)
            assertEquals(start + 2 * hour, reopened.load().clearedAt)
            val observer = HistoryReconciler(reopened, clock, { ZoneOffset.UTC })
            observer.observe(listOf(HistoryRule(id, "Focus", "easy", start + hour)))
            assertTrue(reopened.load().events.isEmpty())
            clock.now = start + 3 * hour
            val freshId = "session-timer-v2-${clock.millis()}-fresh"
            observer.observe(listOf(HistoryRule(freshId, "New focus", "easy", start + 4 * hour)))
            owner.failAck = false
            assertEquals(1, projectEditorHistoryTerminals(owner, reopened::update, ZoneOffset.UTC))
            assertTrue(owner.pending.isEmpty())
            assertFalse(reopened.load().events.any { it.session == id })
            assertEquals(listOf(freshId), reopened.load().events.map { it.session })
            clock.now = start + 5 * hour
            val fresh = terminal.copy(id = "editor:completed:$freshId", sessionId = freshId, occurrenceId = freshId,
                logicalEnd = AccessClockStamp(start + 4 * hour, 4 * hour, "boot"),
                observedAt = AccessClockStamp(clock.millis(), 5 * hour, "boot"))
            projectEditorHistoryTerminals(PendingOwner(fresh), reopened::update, ZoneOffset.UTC)
            val session = FocusHistoryAggregator.sessions(reopened.load().events, clock.millis(), emptySet()).single()
            assertEquals(freshId, session.id)
            assertEquals(SessionOutcome.COMPLETED, session.outcome)
            assertEquals(hour, session.focusedMs)
        }
    }

    @Test fun cancelledAppendLeavesOwnerIntentPending() = runTest {
        val owner = PendingOwner(terminal)
        try {
            projectEditorHistoryTerminals(owner, { throw CancellationException("write cancelled") }, ZoneOffset.UTC)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(listOf(terminal), owner.pending)
        assertEquals(0, owner.acknowledgements)
    }

    @Test fun mapperKeepsStableIdentityAndUncertainClockMetadata() {
        val record = terminal.copy(timeUncertain = true).toHistoryEvent(ZoneOffset.UTC)
        assertEquals(terminal.id, record.id)
        assertEquals(terminal.sessionId, record.session)
        assertEquals("time_uncertain", record.reason)
        assertTrue(record.inferred)
    }

    @Test fun failedSinkDoesNotStarveAnotherTerminalAndOnlySuccessfulIdsAreAcknowledged() = runTest {
        val clock = Clock.fixed(Instant.ofEpochMilli(start + 2 * hour), ZoneOffset.UTC)
        val store = FocusHistoryStore(temporary.newFolder(), clock)
        val second = terminal.copy(id = "editor:completed:second", sessionId = "session-second", occurrenceId = "session-second")
        for (event in listOf(terminal, second)) assertTrue(store.append(HistoryEvent(type = "started",
            session = event.sessionId, at = start, plannedEnd = start + hour, tz = "UTC",
            name = "Focus", tier = "easy", source = "manual")))
        val owner = PendingOwner(terminal, second)
        var attempts = 0
        assertEquals(1, projectEditorHistoryTerminals(owner, { projection ->
            if (attempts++ == 0) throw IOException("first sink failed")
            store.update(projection)
        }, ZoneOffset.UTC))
        assertEquals(listOf(terminal), owner.pending)
        assertEquals(listOf(second.sessionId), FocusHistoryAggregator.sessions(store.load().events,
            clock.millis(), emptySet()).filter { it.outcome == SessionOutcome.COMPLETED }.map { it.id })
        assertEquals(1, projectEditorHistoryTerminals(owner, store::update, ZoneOffset.UTC))
        assertTrue(owner.pending.isEmpty())
    }

    @Test fun cancellationBeforeBatchAckReplaysAlreadyCommittedTerminalWithoutDuplicates() = runTest {
        val clock = Clock.fixed(Instant.ofEpochMilli(start + 2 * hour), ZoneOffset.UTC)
        val store = FocusHistoryStore(temporary.newFolder(), clock)
        val second = terminal.copy(id = "editor:completed:second", sessionId = "session-second", occurrenceId = "session-second")
        for (event in listOf(terminal, second)) assertTrue(store.append(HistoryEvent(type = "started",
            session = event.sessionId, at = start, plannedEnd = start + hour, tz = "UTC",
            name = "Focus", tier = "easy", source = "manual")))
        val owner = PendingOwner(terminal, second)
        var attempts = 0
        try {
            projectEditorHistoryTerminals(owner, { projection ->
                if (attempts++ > 0) throw CancellationException("second sink cancelled")
                store.update(projection)
            }, ZoneOffset.UTC)
            fail("Cancellation must propagate before the batch ack")
        } catch (_: CancellationException) { }
        assertEquals(0, owner.acknowledgements)
        assertEquals(listOf(terminal, second), owner.pending)
        assertEquals(4, store.load().events.size)
        assertEquals(2, projectEditorHistoryTerminals(owner, store::update, ZoneOffset.UTC))
        assertTrue(owner.pending.isEmpty())
        assertEquals(6, store.load().events.size)
    }

    /** Real owner delivery extension; only its queue read/ack storage is faked. */
    private class PendingOwner(vararg events: EditorTerminalEvent) : EditorCommitmentStore {
        var pending = events.toList()
        var acknowledgements = 0
        var failAck = false
        override val state = flowOf(StorageState.Ready(EditorCommitmentState()))
        override val terminalEvents get() = flowOf(StorageState.Ready(pending))
        override suspend fun readTerminalEvents() = pending
        override suspend fun ackTerminalEvents(ids: Set<String>) {
            if (failAck) throw IOException("ack interrupted")
            acknowledgements++
            pending = pending.filterNot { it.id in ids }
        }
        override suspend fun request(change: EditorChange, now: AccessClockStamp, safety: EditorSafety): EditorCommitResult = error("unused")
        override suspend fun requestEnabled(ruleIds: Set<String>, enabled: Boolean, label: String, now: AccessClockStamp, safety: EditorSafety, requireAll: Boolean): EditorCommitResult = error("unused")
        override suspend fun confirm(id: Long, now: AccessClockStamp, safety: EditorSafety): EditorCommitResult = error("unused")
        override suspend fun cancel(id: Long) = error("unused")
        override suspend fun dismissMigrationNotice() = error("unused")
        override suspend fun reconcile(now: AccessClockStamp) = error("unused")
        override suspend fun reconcileRules(now: AccessClockStamp): List<BlockRule> = error("unused")
        override suspend fun startTimer(rule: BlockRule, now: AccessClockStamp, safety: EditorSafety): Boolean = error("unused")
    }
}
