package com.flint.peakfocus

import com.flint.peakfocus.blocking.engine.HarderWaitPolicy
import com.flint.peakfocus.blocking.engine.RuleClockProjection
import com.flint.peakfocus.blocking.overlay.ProtectionStatus
import com.flint.peakfocus.core.datastore.EditorCommitmentState
import com.flint.peakfocus.core.datastore.ProtectionCommit
import com.flint.peakfocus.core.datastore.ProtectionMutation
import com.flint.peakfocus.core.datastore.ProtectionSnapshot
import com.flint.peakfocus.core.datastore.ProtectionTransactionStore
import com.flint.peakfocus.core.datastore.deliverLifecycleEvents
import com.flint.peakfocus.core.history.FocusHistoryAggregator
import com.flint.peakfocus.core.history.FocusHistoryStore
import com.flint.peakfocus.core.history.HistoryCodec
import com.flint.peakfocus.core.history.HistoryEvent
import com.flint.peakfocus.core.history.HistoryEventType
import com.flint.peakfocus.core.history.HistoryEventTimeConfidence
import com.flint.peakfocus.core.history.HistoryReconciler
import com.flint.peakfocus.core.history.HistoryRulesSnapshot
import com.flint.peakfocus.core.history.SessionOutcome
import com.flint.peakfocus.core.history.historyRulesSnapshot
import com.flint.peakfocus.core.model.AccessClockStamp
import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.BlockTargets
import com.flint.peakfocus.core.model.FocusSnapshot
import com.flint.peakfocus.core.model.ProtectionBreakEndReason
import com.flint.peakfocus.core.model.ProtectionLedger
import com.flint.peakfocus.core.model.ProtectionLifecycleEvent
import com.flint.peakfocus.core.model.ProtectionLifecycleKind
import com.flint.peakfocus.core.model.RuleEvaluation
import com.flint.peakfocus.core.model.RuleRuntime
import com.flint.peakfocus.core.model.StorageFailure
import com.flint.peakfocus.core.model.StorageState
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProtectionHistoryConsumerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val begin = 1_793_196_000_000L
    private val minute = 60_000L
    private val clock = Clock.fixed(Instant.ofEpochMilli(begin + 60 * minute), ZoneOffset.UTC)
    private fun stamp(at: Long) = AccessClockStamp(at, at - begin, "private-boot-identity")
    private fun store(directory: File = temporary.newFolder(), at: Long = clock.millis()) =
        FocusHistoryStore(directory, Clock.fixed(Instant.ofEpochMilli(at), ZoneOffset.UTC))
    private fun consumer(store: FocusHistoryStore, live: () -> Set<String>? = { emptySet() }) =
        ProtectionHistoryConsumer(store, liveSessionIds = live) { ZoneOffset.UTC }
    private fun start(id: String) = HistoryEvent(type = "started", session = id, at = begin, tz = "UTC",
        plannedEnd = begin + 60 * minute, tier = "harder", source = "manual", name = "Focus")
    private fun pass() = ProtectionLifecycleEvent(
        id = "pass:p1-opaque-operation", operationId = "p1-opaque-operation",
        kind = ProtectionLifecycleKind.PASS_COMMITTED,
        affectedRuleIds = setOf("session-a", "session-b", "schedule-a", "limit-a"),
        endedTransientIds = setOf("session-b"), at = stamp(begin + 10 * minute),
        untilEpochMs = begin + 15 * minute, revision = 42,
    )
    private fun breakStart(grant: String = "grant-a", at: Long = begin + 10 * minute) = ProtectionLifecycleEvent(
        id = "break-started:$grant", operationId = grant, kind = ProtectionLifecycleKind.BREAK_STARTED,
        affectedRuleIds = setOf("session-a", "session-b", "schedule-a"), at = stamp(at),
        grantId = grant, untilEpochMs = at + 5 * minute,
    )
    private fun breakEnd(start: ProtectionLifecycleEvent, at: Long = begin + 12 * minute,
                         reason: ProtectionBreakEndReason = ProtectionBreakEndReason.RESUMED) = start.copy(
        id = "break-ended:${start.grantId}", kind = ProtectionLifecycleKind.BREAK_ENDED,
        at = stamp(at), untilEpochMs = null, breakEndReason = reason,
    )
    private fun accessKinds(): List<ProtectionLifecycleKind> {
        val kinds = ProtectionLifecycleKind.entries.filter { it.name in setOf("ACCESS_STARTED", "ACCESS_ENDED") }
        // This branch must compile against the actual merged C API, not a stacked fixture.
        // Keep future owner enum execution explicitly pending until that additive API lands.
        assumeTrue("C has not yet supplied ACCESS lifecycle kinds", kinds.size == 2)
        return kinds
    }

    @Test fun oneGlobalPassReferencesOnlyAffectedSessionsAndEndsOnlyExactTransientScope() = runBlocking {
        val store = store()
        assertTrue(store.update { listOf(start("session-a"), start("session-b"), start("session-unaffected")) })
        assertTrue(consumer(store).deliver(pass()))
        val events = store.load().events
        val global = events.single { it.knownType == HistoryEventType.PASS_USED }
        assertEquals("p1-opaque-operation", global.session)
        assertEquals(listOf("session-a", "session-b"), global.sessions)
        assertEquals(global.session, global.grant)
        assertEquals(begin + 15 * minute, global.recoveryEnd)
        val terminal = events.single { it.knownType?.terminal == true }
        assertEquals("session-b", terminal.session)
        assertEquals("emergency_pass", terminal.route)
        assertEquals(global.at, terminal.at)
        val sessions = FocusHistoryAggregator.sessions(events, begin + 20 * minute,
            setOf("session-a", "session-unaffected")).associateBy { it.id }
        assertEquals(15 * minute, sessions.getValue("session-a").focusedMs)
        assertEquals(10 * minute, sessions.getValue("session-b").focusedMs)
        assertEquals(SessionOutcome.ENDED_EARLY, sessions.getValue("session-b").outcome)
        assertEquals(20 * minute, sessions.getValue("session-unaffected").focusedMs)
        val summary = FocusHistoryAggregator.summarize(store.load(), begin + 20 * minute,
            ZoneOffset.UTC, setOf("session-a", "session-unaffected"))
        // Overlapping focus sessions are unioned, while the pass's terminal counts once.
        assertEquals(20 * minute, summary.days.last().focusedMs)
        assertEquals(0, summary.days.last().completed)
        assertEquals(1, summary.days.last().endedEarly)
        assertTrue(summary.days.last().inProgress)
        val wire = events.joinToString("\n", transform = HistoryCodec::encode)
        for (privateValue in listOf("schedule-a", "limit-a", "private-boot-identity", "elapsedRealtime", "affectedRuleIds"))
            assertFalse(privateValue, wire.contains(privateValue))
        assertTrue(events.all { it.v == 1 })
        assertTrue(events.all { it.timeConfidence == null })
    }

    @Test fun globalPassWithNoFocusSessionsStillRecordsExactlyOneOperation() = runBlocking {
        val store = store()
        assertTrue(consumer(store).deliver(pass().copy(affectedRuleIds = setOf("schedule-a"), endedTransientIds = emptySet())))
        val only = store.load().events.single()
        assertEquals(HistoryEventType.PASS_USED, only.knownType)
        assertEquals(emptyList<String>(), only.sessions)
        assertTrue(FocusHistoryAggregator.sessions(listOf(only), clock.millis(), emptySet()).isEmpty())
    }

    @Test fun newOrdinaryOnlyPassAfterClearIsAckedWithoutAnUnanchoredOperationRecord() = runBlocking {
        val clearAt = begin + 20 * minute
        val store = store(at = clearAt)
        assertTrue(store.clear(emptySet()))
        val event = pass().copy(id = "pass:new-ordinary-only", operationId = "new-ordinary-only",
            affectedRuleIds = setOf("schedule-new"), endedTransientIds = emptySet(),
            at = stamp(clearAt + minute), untilEpochMs = clearAt + 61 * minute)
        assertTrue(event.at.wallEpochMs > store.load().clearedAt!!)
        val owner = Owner(event)
        // A live ordinary rule cannot establish that its pass operation postdates clear.
        val adapter = consumer(store) { error("No focus identities require liveness lookup") }
        owner.deliverLifecycleEvents(adapter::deliver)
        assertTrue(owner.pending.isEmpty())
        assertEquals(1, owner.acknowledgements)
        val snapshot = store.load()
        assertTrue(snapshot.events.isEmpty())
        assertEquals("", File(store.directory, "events.v1.jsonl").readText())
        assertFalse(File(store.directory, "metadata.v1.json").readText().contains(event.operationId))
        val summary = FocusHistoryAggregator.summarize(snapshot, clearAt + 2 * minute, ZoneOffset.UTC, emptySet())
        assertTrue(summary.sessions.isEmpty())
        assertEquals(0L, summary.days.last().focusedMs)
        assertEquals(0, summary.days.last().completed)
        assertEquals(0, summary.days.last().endedEarly)
    }

    @Test fun breakEndsPairByGrantForEachSessionIncludingEndBeforeStartDelivery() = runBlocking {
        val store = store()
        store.update { listOf(start("session-a"), start("session-b")) }
        val first = breakStart()
        val second = breakStart("grant-b", begin + 20 * minute)
        val consumer = consumer(store)
        // Delivery order cannot require a start to be visible before accepting a durable end.
        for (event in listOf(breakEnd(first), first, second, breakEnd(second, begin + 21 * minute)))
            assertTrue(consumer.deliver(event))
        val events = store.load().events
        for (id in listOf("session-a", "session-b")) {
            val breaks = events.filter { it.session == id && it.grant != null }
            assertEquals(4, breaks.size)
            assertEquals(setOf("grant-a", "grant-b"), breaks.map { it.grant }.toSet())
            assertTrue(breaks.filter { it.knownType == HistoryEventType.BREAK_STARTED }.all { it.kind == "ordinary" })
        }
        assertEquals(8, events.filter { it.grant != null }.map { it.id }.toSet().size)
        val sessions = FocusHistoryAggregator.sessions(events, begin + 30 * minute, setOf("session-a", "session-b"))
        assertTrue(sessions.all { it.focusedMs == 27 * minute && it.breaks == 2 })
    }

    @Test fun everyOwnerBreakEndReasonKeepsItsWireMeaningAndExactScope() = runBlocking {
        val store = store()
        for (reason in ProtectionBreakEndReason.entries) {
            val event = breakEnd(breakStart(reason.name), reason = reason).copy(affectedRuleIds = setOf("session-b", "schedule-a"))
            assertTrue(consumer(store).deliver(event))
        }
        val events = store.load().events
        assertEquals(setOf("expired", "resumed", "superseded", "recovery"), events.map { it.reason }.toSet())
        assertTrue(events.all { it.session == "session-b" && it.knownType == HistoryEventType.BREAK_ENDED })
    }

    @Test fun openCreditAccessKindsAcknowledgeWithoutHistoryRecordsOrFocusedTimeChanges() = runBlocking {
        val kinds = accessKinds()
        val store = store()
        assertTrue(store.update { listOf(start("session-a"), start("session-b")) })
        val before = File(store.directory, "events.v1.jsonl").readBytes()
        val beforeSummary = FocusHistoryAggregator.summarize(store.load(), begin + 20 * minute,
            ZoneOffset.UTC, setOf("session-a", "session-b"))
        for (kind in kinds) {
            val owner = Owner(breakStart().copy(id = kind.name, kind = kind))
            owner.deliverLifecycleEvents(consumer(store)::deliver)
            assertTrue(owner.pending.isEmpty())
            assertEquals(1, owner.acknowledgements)
        }
        assertArrayEquals(before, File(store.directory, "events.v1.jsonl").readBytes())
        assertEquals(beforeSummary, FocusHistoryAggregator.summarize(store.load(), begin + 20 * minute,
            ZoneOffset.UTC, setOf("session-a", "session-b")))
    }

    @Test fun openCreditAccessDoesNotReadHistoryOrZoneEvenWhenHistoryIsUnavailable() = runBlocking {
        val kinds = accessKinds()
        val unavailable = temporary.newFile()
        val before = unavailable.readBytes()
        for (kind in kinds) {
            val owner = Owner(breakStart().copy(id = kind.name, kind = kind))
            val consumer = ProtectionHistoryConsumer(store(unavailable)) { error("No focus projection expected") }
            owner.deliverLifecycleEvents(consumer::deliver)
            assertTrue(owner.pending.isEmpty())
            assertEquals(1, owner.acknowledgements)
        }
        assertArrayEquals(before, unavailable.readBytes())
    }

    @Test fun replayAfterReopeningStoreAndChangingSetOrderDoesNotDuplicateRecords() = runBlocking {
        val store = store()
        val event = pass().copy(endedTransientIds = setOf("session-a", "session-b"))
        assertTrue(consumer(store).deliver(event))
        val before = File(store.directory, "events.v1.jsonl").readBytes()
        val reopened = store(store.directory)
        repeat(2) {
            assertTrue(consumer(reopened).deliver(event.copy(affectedRuleIds = event.affectedRuleIds.reversed().toSet(),
                endedTransientIds = event.endedTransientIds.reversed().toSet())))
        }
        assertArrayEquals(before, File(store.directory, "events.v1.jsonl").readBytes())
        assertEquals(3, reopened.load().events.size)
    }

    @Test fun realOwnerDrainOnlyAcknowledgesAfterHistoryIsReadableFromAnotherStore() = runBlocking {
        val store = store()
        val owner = Owner(pass())
        owner.beforeAck = {
            val committed = store(store.directory).load().events
            assertEquals(setOf("pass_used", "ended_early"), committed.map { it.type }.toSet())
        }
        owner.deliverLifecycleEvents(consumer(store)::deliver)
        assertTrue(owner.pending.isEmpty())
        assertEquals(1, owner.acknowledgements)
    }

    @Test fun crashAfterHistoryCommitBeforeAckReplaysThroughRealOwnerDrainExactlyOnce() = runBlocking {
        val store = store()
        val owner = Owner(pass())
        try {
            owner.deliverLifecycleEvents { event ->
                assertTrue(consumer(store).deliver(event))
                throw CancellationException("process died before owner acknowledgement")
            }
            fail("Cancellation was swallowed")
        } catch (_: CancellationException) { }
        assertEquals(setOf(pass().id), owner.pending.keys)
        assertEquals(0, owner.acknowledgements)
        val restarted = store(store.directory)
        owner.deliverLifecycleEvents(consumer(restarted)::deliver)
        assertEquals(2, restarted.load().events.size)
        assertTrue(owner.pending.isEmpty())
    }

    @Test fun failedAtomicBatchLeavesNoPartialPassOrTerminalAndNoOwnerAck() = runBlocking {
        val store = store()
        store.load()
        val blocked = File(store.directory, "events.v1.jsonl.tmp")
        assertTrue(blocked.mkdir())
        // Keep the obstruction nonempty so the store's finally block cannot remove it.
        val obstruction = File(blocked, "fixture").apply { writeText("blocked") }
        val owner = Owner(pass())
        owner.deliverLifecycleEvents(consumer(store)::deliver)
        assertTrue(store(store.directory).load().events.isEmpty())
        assertEquals(setOf(pass().id), owner.pending.keys)
        assertEquals(0, owner.acknowledgements)
        assertTrue(obstruction.delete()); assertTrue(blocked.delete())
        owner.deliverLifecycleEvents(consumer(store)::deliver)
        assertEquals(2, store(store.directory).load().events.size)
        assertTrue(owner.pending.isEmpty())
    }

    @Test fun failedOwnerAckRetainsEventAndReplayedHistoryDoesNotMultiply() = runBlocking {
        val store = store()
        val owner = Owner(pass()).apply { failAck = true }
        owner.deliverLifecycleEvents(consumer(store)::deliver)
        assertEquals(2, store.load().events.size)
        assertEquals(setOf(pass().id), owner.pending.keys)
        owner.failAck = false
        owner.deliverLifecycleEvents(consumer(store(store.directory))::deliver)
        assertEquals(2, store.load().events.size)
        assertTrue(owner.pending.isEmpty())
    }

    @Test fun failedAckThenClearAndReopenDoesNotResurrectPassOrBreakRecords() = runBlocking {
        val clearAt = begin + 20 * minute
        for (event in listOf(pass(), breakStart(), breakEnd(breakStart()),
            pass().copy(at = stamp(clearAt), untilEpochMs = clearAt + 5 * minute),
            pass().copy(affectedRuleIds = setOf("schedule-a"), endedTransientIds = emptySet()))) {
            val store = store(at = clearAt)
            assertTrue(store.update { listOf(start("session-a"), start("session-b")) })
            val owner = Owner(event).apply { failAck = true }
            owner.deliverLifecycleEvents(consumer(store)::deliver)
            assertFalse(store.load().events.isEmpty())
            assertEquals(setOf(event.id), owner.pending.keys)
            assertTrue(store.clear(emptySet()))
            val reopened = store(store.directory, clearAt)
            assertEquals(clearAt, reopened.load().clearedAt)
            assertTrue(reopened.load().events.isEmpty())
            owner.failAck = false
            owner.deliverLifecycleEvents(consumer(reopened)::deliver)
            assertTrue(owner.pending.isEmpty())
            assertTrue(store(store.directory, clearAt).load().events.isEmpty())
            assertEquals("", File(store.directory, "events.v1.jsonl").readText())
        }
    }

    @Test fun delayedOldPassKeepsOnlyRetainedSessionReferencesAndExactTerminals() = runBlocking {
        val store = store(at = begin + 20 * minute)
        assertTrue(store.update { listOf(start("session-a"), start("session-b")) })
        assertTrue(store.clear(setOf("session-a")))
        val event = pass().copy(endedTransientIds = setOf("session-a", "session-b"))
        val owner = Owner(event)
        owner.deliverLifecycleEvents(consumer(store)::deliver)
        val events = store.load().events
        assertEquals(listOf("session-a"), events.single { it.knownType == HistoryEventType.PASS_USED }.sessions)
        assertEquals("session-a", events.single { it.knownType == HistoryEventType.ENDED_EARLY }.session)
        assertFalse(File(store.directory, "events.v1.jsonl").readText().contains("session-b"))
        assertTrue(owner.pending.isEmpty())
    }

    @Test fun failedPassAckThenPartialClearPreservesOnlyTheRetainedRunningSession() = runBlocking {
        val now = begin + 20 * minute
        val store = store(at = now)
        assertTrue(store.update { listOf(start("session-a"), start("session-b")) })
        val owner = Owner(pass()).apply { failAck = true }
        owner.deliverLifecycleEvents(consumer(store)::deliver)
        assertTrue(store.clear(setOf("session-a")))
        val afterClear = File(store.directory, "events.v1.jsonl").readBytes()
        owner.failAck = false
        owner.deliverLifecycleEvents(consumer(store(store.directory, now))::deliver)
        val events = store.load().events
        assertEquals(2, events.size)
        assertEquals(listOf("session-a"), events.single { it.knownType == HistoryEventType.PASS_USED }.sessions)
        assertFalse(afterClear.toString(Charsets.UTF_8).contains("session-b"))
        assertArrayEquals(afterClear, File(store.directory, "events.v1.jsonl").readBytes())
        assertTrue(owner.pending.isEmpty())
    }

    @Test fun clearRetainsRunningBreakPairButReplayNeverRestoresClearedSessionScope() = runBlocking {
        val now = begin + 20 * minute
        val store = store(at = now)
        assertTrue(store.update { listOf(start("session-a"), start("session-b")) })
        val start = breakStart()
        val owner = Owner(start).apply { failAck = true }
        owner.deliverLifecycleEvents(consumer(store)::deliver)
        assertTrue(store.clear(setOf("session-a")))
        val reopened = store(store.directory, now)
        assertTrue(consumer(reopened).deliver(breakEnd(start)))
        owner.failAck = false
        owner.deliverLifecycleEvents(consumer(reopened)::deliver)
        val events = reopened.load().events
        assertEquals(3, events.size)
        assertTrue(events.all { it.session == "session-a" })
        assertEquals(setOf("grant-a"), events.mapNotNull { it.grant }.toSet())
        val session = FocusHistoryAggregator.sessions(events, begin + 30 * minute, setOf("session-a")).single()
        assertEquals(28 * minute, session.focusedMs)
        assertEquals(1, session.breaks)
        assertTrue(owner.pending.isEmpty())
    }

    @Test fun lateEventsBeyondClearCutoffCannotRestoreRemovedIdentities() = runBlocking {
        val clearAt = begin + 20 * minute
        val lateStart = breakStart(at = clearAt + minute)
        val latePass = pass().copy(at = stamp(clearAt + minute), untilEpochMs = clearAt + 6 * minute)
        for (event in listOf(latePass, lateStart, breakEnd(lateStart, clearAt + 2 * minute))) {
            val store = store(at = clearAt)
            assertTrue(store.update { listOf(start("session-a"), start("session-b")) })
            assertTrue(store.clear(emptySet()))
            val reopened = store(store.directory, clearAt)
            assertTrue(event.at.wallEpochMs > reopened.load().clearedAt!!)
            val owner = Owner(event)
            owner.deliverLifecycleEvents(consumer(reopened)::deliver)
            assertTrue(owner.pending.isEmpty())
            assertEquals(1, owner.acknowledgements)
            assertTrue(store(store.directory, clearAt).load().events.isEmpty())
            assertEquals("", File(store.directory, "events.v1.jsonl").readText())
        }
    }

    @Test fun latePassAndBreaksKeepRetainedRunningScopeAndTruthfulSummaryCounts() = runBlocking {
        val clearAt = begin + 20 * minute
        val store = store(at = clearAt)
        assertTrue(store.update { listOf(start("session-a"), start("session-b")) })
        assertTrue(store.clear(setOf("session-a")))
        val reopened = store(store.directory, begin + 40 * minute)
        val breakStart = breakStart(at = begin + 22 * minute)
        val latePass = pass().copy(at = stamp(begin + 25 * minute), untilEpochMs = begin + 30 * minute)
        for (event in listOf(breakEnd(breakStart, begin + 24 * minute), breakStart, latePass)) {
            val owner = Owner(event).apply { failAck = true }
            owner.deliverLifecycleEvents(consumer(reopened)::deliver)
            owner.failAck = false
            owner.deliverLifecycleEvents(consumer(store(store.directory, begin + 40 * minute))::deliver)
            assertTrue(owner.pending.isEmpty())
        }
        val snapshot = reopened.load()
        assertEquals(4, snapshot.events.size)
        assertFalse(File(store.directory, "events.v1.jsonl").readText().contains("session-b"))
        assertEquals(listOf("session-a"), snapshot.events.single { it.knownType == HistoryEventType.PASS_USED }.sessions)
        val summary = FocusHistoryAggregator.summarize(snapshot, begin + 40 * minute, ZoneOffset.UTC, setOf("session-a"))
        assertEquals(33 * minute, summary.sessions.single().focusedMs)
        assertEquals(1, summary.sessions.single().breaks)
        assertEquals(33 * minute, summary.days.last().focusedMs)
        assertEquals(0, summary.days.last().completed)
        assertEquals(0, summary.days.last().endedEarly)
        assertTrue(summary.days.last().inProgress)
    }

    @Test fun newStartedIdentityAfterClearStillAdmitsItsPassAndEarlyTerminal() = runBlocking {
        val clearAt = begin + 20 * minute
        val store = store(at = clearAt)
        assertTrue(store.clear(emptySet()))
        val newId = "session-new"
        assertTrue(store.append(start(newId).copy(at = clearAt + minute)))
        val event = pass().copy(affectedRuleIds = setOf(newId, "session-removed"), endedTransientIds = setOf(newId),
            at = stamp(clearAt + 10 * minute), untilEpochMs = clearAt + 15 * minute)
        val owner = Owner(event)
        owner.deliverLifecycleEvents(consumer(store)::deliver)
        assertTrue(owner.pending.isEmpty())
        val snapshot = store.load()
        assertEquals(listOf(newId), snapshot.events.single { it.knownType == HistoryEventType.PASS_USED }.sessions)
        val summary = FocusHistoryAggregator.summarize(snapshot, clearAt + 20 * minute, ZoneOffset.UTC, emptySet())
        assertEquals(9 * minute, summary.days.last().focusedMs)
        assertEquals(0, summary.days.last().completed)
        assertEquals(1, summary.days.last().endedEarly)
        assertEquals(SessionOutcome.ENDED_EARLY, summary.sessions.single().outcome)
        assertFalse(File(store.directory, "events.v1.jsonl").readText().contains("session-removed"))
    }

    @Test fun rollbackClearKeepsCommittedHighWaterAndAcknowledgesLaterOrphanReplay() = runBlocking {
        val store = store()
        assertTrue(store.update { listOf(start("session-a"), start("session-b")) })
        val owner = Owner(pass()).apply { failAck = true }
        owner.deliverLifecycleEvents(consumer(store)::deliver)
        val rolledBack = store(store.directory, begin + 5 * minute)
        assertTrue(rolledBack.clear(emptySet()))
        assertEquals(pass().at.wallEpochMs, rolledBack.load().clearedAt)
        owner.failAck = false
        owner.deliverLifecycleEvents(consumer(rolledBack)::deliver)
        val late = Owner(breakEnd(breakStart(), begin + 30 * minute))
        late.deliverLifecycleEvents(consumer(rolledBack)::deliver)
        assertTrue(owner.pending.isEmpty() && late.pending.isEmpty())
        assertTrue(store(store.directory, begin + 5 * minute).load().events.isEmpty())
    }

    @Test fun liveNewSessionBreakDefersBeforeStartedThenRetriesWithoutInventingOrDuplicatingTime() = runBlocking {
        val clearAt = begin + 20 * minute
        val store = store(at = clearAt)
        assertTrue(store.clear(emptySet()))
        val id = "session-new"
        val event = breakStart(at = clearAt + 2 * minute).copy(affectedRuleIds = setOf(id))
        val owner = Owner(event)
        val adapter = consumer(store) { setOf(id) }
        owner.deliverLifecycleEvents(adapter::deliver)
        assertEquals(setOf(event.id), owner.pending.keys)
        assertEquals(0, owner.acknowledgements)
        assertTrue(store.load().events.isEmpty())
        assertFalse(store.load().degraded)
        assertTrue(store.append(start(id).copy(at = clearAt + minute)))
        owner.deliverLifecycleEvents(adapter::deliver)
        assertTrue(owner.pending.isEmpty())
        assertEquals(1, owner.acknowledgements)
        val beforeReplay = File(store.directory, "events.v1.jsonl").readBytes()
        assertTrue(consumer(store(store.directory, clearAt)) { setOf(id) }.deliver(event))
        assertArrayEquals(beforeReplay, File(store.directory, "events.v1.jsonl").readBytes())
        val summary = FocusHistoryAggregator.summarize(store.load(), clearAt + 10 * minute, ZoneOffset.UTC, setOf(id))
        assertEquals(2, store.load().events.size)
        assertEquals(4 * minute, summary.days.last().focusedMs)
        assertEquals(1, summary.sessions.single().breaks)
        assertEquals(0, summary.days.last().completed)
        assertEquals(0, summary.days.last().endedEarly)
    }

    @Test fun mixedGlobalPassCannotAckOrPartiallyWriteWhileAnyLiveStartIsUndelivered() = runBlocking {
        val clearAt = begin + 20 * minute
        val store = store(at = clearAt)
        assertTrue(store.update { listOf(start("session-a"), start("session-removed")) })
        assertTrue(store.clear(setOf("session-a")))
        val event = pass().copy(affectedRuleIds = setOf("session-a", "session-new", "session-removed", "schedule-a"),
            endedTransientIds = setOf("session-new"), at = stamp(clearAt + 5 * minute), untilEpochMs = clearAt + 10 * minute)
        val owner = Owner(event)
        val consumer = consumer(store) { setOf("session-new", "session-a") }
        val before = File(store.directory, "events.v1.jsonl").readBytes()
        owner.deliverLifecycleEvents(consumer::deliver)
        assertEquals(setOf(event.id), owner.pending.keys)
        assertEquals(0, owner.acknowledgements)
        assertArrayEquals(before, File(store.directory, "events.v1.jsonl").readBytes())
        assertTrue(store.append(start("session-new").copy(at = clearAt + minute)))
        owner.failAck = true
        owner.deliverLifecycleEvents(consumer::deliver)
        val committed = File(store.directory, "events.v1.jsonl").readBytes()
        assertEquals(4, store.load().events.size)
        owner.failAck = false
        owner.deliverLifecycleEvents(consumer::deliver)
        assertTrue(owner.pending.isEmpty())
        assertArrayEquals(committed, File(store.directory, "events.v1.jsonl").readBytes())
        val snapshot = store.load()
        assertEquals(listOf("session-a", "session-new"), snapshot.events.single { it.knownType == HistoryEventType.PASS_USED }.sessions)
        assertEquals("session-new", snapshot.events.single { it.knownType == HistoryEventType.ENDED_EARLY }.session)
        assertFalse(committed.toString(Charsets.UTF_8).contains("session-removed"))
        val summary = FocusHistoryAggregator.summarize(snapshot, begin + 40 * minute, ZoneOffset.UTC, setOf("session-a"))
        assertEquals(35 * minute, summary.days.last().focusedMs)
        assertEquals(0, summary.days.last().completed)
        assertEquals(1, summary.days.last().endedEarly)
    }

    @Test fun unknownOwnerStateRetainsOrphanUntilRemovalIsPositivelyKnown() = runBlocking {
        val clearAt = begin + 20 * minute
        val store = store(at = clearAt)
        assertTrue(store.update { listOf(start("session-a"), start("session-b")) })
        assertTrue(store.clear(emptySet()))
        val event = breakStart(at = clearAt + minute)
        val owner = Owner(event)
        var state: StorageState<List<BlockRule>> = StorageState.Loading
        val consumer = consumer(store) { liveProtectionHistorySessions(state, clearAt) }
        owner.deliverLifecycleEvents(consumer::deliver)
        state = StorageState.Unavailable(StorageFailure.IO)
        owner.deliverLifecycleEvents(consumer::deliver)
        assertEquals(setOf(event.id), owner.pending.keys)
        assertEquals(0, owner.acknowledgements)
        assertTrue(store.load().events.isEmpty())
        assertFalse(store.load().degraded)
        state = StorageState.Ready(emptyList())
        owner.deliverLifecycleEvents(consumer::deliver)
        assertTrue(owner.pending.isEmpty())
        assertEquals(1, owner.acknowledgements)
        assertTrue(store.load().events.isEmpty())
    }

    @Test fun retainedStartsDoNotRequireAnotherLiveOwnerRead() = runBlocking {
        val store = store(at = begin + 20 * minute)
        assertTrue(store.append(start("session-a")))
        assertTrue(store.clear(setOf("session-a")))
        val event = breakStart().copy(affectedRuleIds = setOf("session-a"))
        val owner = Owner(event)
        val adapter = consumer(store) { error("Retained start already proves admission") }
        owner.deliverLifecycleEvents(adapter::deliver)
        assertTrue(owner.pending.isEmpty())
        assertEquals(2, store.load().events.size)
    }

    @Test fun failedLiveOwnerReadRetainsTheEventWithoutClaimingHistoryCorruption() = runBlocking {
        val store = store(at = begin + 20 * minute)
        assertTrue(store.clear(emptySet()))
        val event = breakStart()
        val owner = Owner(event)
        val adapter = consumer(store) { throw java.io.IOException("Owner projection unavailable") }
        owner.deliverLifecycleEvents(adapter::deliver)
        assertEquals(setOf(event.id), owner.pending.keys)
        assertEquals(0, owner.acknowledgements)
        assertTrue(store.load().events.isEmpty())
        assertFalse(store.load().degraded)
    }

    @Test fun cancellationDuringLiveAdmissionPropagatesWithoutAcknowledgement() = runBlocking {
        val store = store(at = begin + 20 * minute)
        assertTrue(store.clear(emptySet()))
        val event = breakStart()
        val owner = Owner(event)
        val adapter = consumer(store) { throw CancellationException("Cancelled public owner read") }
        try { owner.deliverLifecycleEvents(adapter::deliver); fail("Cancellation was swallowed") }
        catch (_: CancellationException) { }
        assertEquals(setOf(event.id), owner.pending.keys)
        assertEquals(0, owner.acknowledgements)
        assertTrue(store.load().events.isEmpty())
        assertFalse(store.load().degraded)
    }

    @Test fun publicLiveProjectionKeepsUnknownDistinctFromRemovedAndExcludesExpiredRules() {
        assertNull(liveProtectionHistorySessions(StorageState.Loading, begin))
        assertNull(liveProtectionHistorySessions(StorageState.Unavailable(StorageFailure.IO), begin))
        assertEquals(emptySet<String>(), liveProtectionHistorySessions(StorageState.Ready(emptyList()), begin))
        val active = BlockRule("session-live", "Focus", BlockTargets(), expiresAtEpochMs = begin + minute)
        val expired = active.copy(id = "session-expired", expiresAtEpochMs = begin)
        val disabled = active.copy(id = "session-disabled", enabled = false)
        val ordinary = active.copy(id = "schedule-a")
        val runtime = RuleRuntime("occurrence-a", stamp(begin), minute, begin + minute, "UTC", stamp(begin),
            evaluation = RuleEvaluation(true, begin + minute, begin + minute))
        // Public runtime authority wins over the stale raw template in both directions.
        val anchored = expired.copy(id = "session-anchored", runtime = runtime)
        val inactive = active.copy(id = "session-inactive", runtime = runtime.copy(
            evaluation = RuleEvaluation(false, null, null)))
        assertEquals(setOf(active.id, anchored.id), liveProtectionHistorySessions(
            StorageState.Ready(listOf(active, expired, disabled, ordinary, anchored, inactive)), begin))
        val unprojected = active.copy(runtime = runtime.copy(evaluation = null))
        assertNull(liveProtectionHistorySessions(StorageState.Ready(listOf(unprojected)), begin))
        assertEquals(HistoryRulesSnapshot.Loading, historyRulesSnapshot(StorageState.Ready(listOf(unprojected))))
        val history = historyRulesSnapshot(StorageState.Ready(listOf(active, expired, disabled, ordinary, anchored, inactive)))
            as HistoryRulesSnapshot.Ready
        assertEquals(setOf(anchored.id), history.rules.filter { it.ownerActive == true }.map { it.id }.toSet())
        assertEquals(setOf(inactive.id), history.ownerInactiveIds)
    }

    @Test fun nativeForwardClockProjectionObserverAndClearRetainStartWithoutInventingCoverage() = runBlocking {
        forwardClockObserverClearAndRetry(native = true)
    }

    @Test fun nativeForwardClockBreakWithoutOwnerUncertaintyFlagCannotInventCoverageEither() = runBlocking {
        forwardClockObserverClearAndRetry(native = true, timeUncertain = false)
    }

    @Test fun legacyForwardClockProjectionObserverUsesObservationTimeBeforeClearAndRetry() = runBlocking {
        forwardClockObserverClearAndRetry(native = false)
    }

    private suspend fun forwardClockObserverClearAndRetry(native: Boolean, timeUncertain: Boolean = true) {
        val anchor = stamp(begin)
        val now = AccessClockStamp(begin + 120 * minute, minute, anchor.bootSessionId)
        val id = if (native) "session-timer-$begin-forward-clock" else "session-forward-clock"
        val end = begin + 10 * minute
        val raw = BlockRule(id, "Focus", BlockTargets(), expiresAtEpochMs = end,
            runtime = RuleRuntime(id, anchor, 10 * minute, end, "UTC", anchor))
        val projected = RuleClockProjection.project(raw, now)
        assertTrue(projected.runtime!!.evaluation!!.active)
        assertEquals(end, projected.runtime!!.wallEndEpochMs)
        assertTrue(end < now.wallEpochMs)
        assertTrue(projected.expiresAtEpochMs!! > now.wallEpochMs)
        var state = StorageState.Ready(listOf(projected))
        assertEquals(setOf(id), liveProtectionHistorySessions(state, now.wallEpochMs))
        val store = store(at = now.wallEpochMs)
        assertTrue(store.clear(emptySet()))
        val eventAt = now.copy(wallEpochMs = now.wallEpochMs + minute, elapsedRealtimeMs = now.elapsedRealtimeMs + minute)
        val event = breakStart(at = eventAt.wallEpochMs).copy(at = eventAt, affectedRuleIds = setOf(id), timeUncertain = timeUncertain)
        val owner = Owner(event)
        val adapter = consumer(store) { liveProtectionHistorySessions(state, now.wallEpochMs) }
        owner.deliverLifecycleEvents(adapter::deliver)
        assertEquals(setOf(event.id), owner.pending.keys)
        assertEquals(0, owner.acknowledgements)
        assertTrue(store.load().events.isEmpty())
        // The actual observer supplies STARTED from public owner activity, preserving
        // the committed logical endpoint and recording no unobserved historical prefix.
        val observer = HistoryReconciler(store, Clock.fixed(Instant.ofEpochMilli(now.wallEpochMs), ZoneOffset.UTC),
            { ZoneOffset.UTC }, authoritativeSnapshot = { historyRulesSnapshot(state) })
        observer.collectSnapshots(flowOf(historyRulesSnapshot(state)))
        assertEquals(setOf(id), observer.activeSessionIds.value)
        val started = store.load().events.single()
        assertEquals("started", started.type)
        assertEquals(if (native) begin else now.wallEpochMs, started.at)
        assertEquals(end, started.plannedEnd)
        assertTrue(store.clear(observer.activeSessionIds.value))
        assertEquals(listOf(started), store.load().events)
        owner.deliverLifecycleEvents(adapter::deliver)
        assertTrue(owner.pending.isEmpty())
        assertEquals(1, owner.acknowledgements)
        val snapshot = store.load()
        assertEquals(listOf("started", "break_started"), snapshot.events.map { it.type })
        assertEquals(end, snapshot.events.first().plannedEnd)
        assertEquals(timeUncertain, snapshot.events.last().inferred)
        assertEquals(if (timeUncertain) HistoryEventTimeConfidence.Uncertain("owner_time_uncertain") else null,
            snapshot.events.last().timeConfidence)
        assertEquals(0L, FocusHistoryAggregator.sessions(snapshot.events, now.wallEpochMs + 8 * minute, setOf(id)).single().focusedMs)

        val ended = RuleClockProjection.project(raw, now.copy(elapsedRealtimeMs = 11 * minute))
        assertFalse(ended.runtime!!.evaluation!!.active)
        state = StorageState.Ready(listOf(ended))
        observer.collectSnapshots(flowOf(historyRulesSnapshot(state)))
        assertTrue(observer.activeSessionIds.value.isEmpty())
        assertTrue(store.clear(observer.activeSessionIds.value))
        assertTrue(store.load().events.isEmpty())
        assertTrue(adapter.deliver(event))
        assertTrue(store.load().events.isEmpty())
    }

    @Test fun cancellationDuringProjectionPropagatesAndDoesNotAcknowledge() = runBlocking {
        val store = store()
        val owner = Owner(pass())
        val consumer = ProtectionHistoryConsumer(store) { throw CancellationException("cancelled history write") }
        try { owner.deliverLifecycleEvents(consumer::deliver); fail("Cancellation was swallowed") }
        catch (_: CancellationException) { }
        assertTrue(store.load().events.isEmpty())
        assertEquals(setOf(pass().id), owner.pending.keys)
        assertEquals(0, owner.acknowledgements)
    }

    @Test fun invalidPayloadOrOversizedTerminalCannotAcknowledgeAPartialProjection() = runBlocking {
        for (event in listOf(
            pass().copy(untilEpochMs = null),
            pass().copy(endedTransientIds = setOf("session-unaffected")),
            breakStart().copy(grantId = null),
            breakEnd(breakStart()).copy(breakEndReason = null),
            pass().copy(affectedRuleIds = setOf("session-" + "x".repeat(70_000)),
                endedTransientIds = setOf("session-" + "x".repeat(70_000))),
        )) {
            val store = store()
            val owner = Owner(event)
            owner.deliverLifecycleEvents(consumer(store)::deliver)
            assertEquals(setOf(event.id), owner.pending.keys)
            assertTrue(store.load().events.isEmpty())
        }
    }

    @Test fun unknownObservationAtSameTimeDoesNotChangePassOutcomeOrFocusedInterval() = runBlocking {
        val store = store()
        store.update { listOf(start("session-b"), HistoryEvent(type = "ended_early", session = "session-b",
            at = pass().at.wallEpochMs, tz = "UTC", route = "unknown")) }
        consumer(store).deliver(pass())
        val events = store.load().events
        assertEquals(1, events.count { it.route == "emergency_pass" })
        val session = FocusHistoryAggregator.sessions(events, clock.millis(), emptySet()).single()
        assertEquals(SessionOutcome.ENDED_EARLY, session.outcome)
        assertEquals(10 * minute, session.focusedMs)
    }

    @Test fun ownerClockUncertaintyIsExplicitAndCannotExtendPriorTrustedPrefix() = runBlocking {
        val store = store()
        assertTrue(store.update { listOf(start("session-a"), start("session-b"),
            HistoryEvent(type = "open_intercepted", session = "session-a", at = begin + 2 * minute, tz = "UTC"),
            HistoryEvent(type = "open_intercepted", session = "session-b", at = begin + 2 * minute, tz = "UTC")) })
        assertTrue(consumer(store).deliver(pass().copy(timeUncertain = true)))
        val events = store.load().events
        val projected = events.filter { it.knownType in setOf(HistoryEventType.PASS_USED, HistoryEventType.ENDED_EARLY) }
        assertEquals(2, projected.size)
        assertTrue(projected.all { it.inferred && it.timeConfidence == HistoryEventTimeConfidence.Uncertain("owner_time_uncertain") })
        val summary = FocusHistoryAggregator.summarize(store.load(), begin + 20 * minute, ZoneOffset.UTC, setOf("session-a"))
        assertTrue(summary.sessions.all { it.outcome == SessionOutcome.UNCONFIRMED && it.focusedMs == 2 * minute })
        assertEquals(2 * minute, summary.days.last().focusedMs)
        assertEquals(0, summary.days.last().completed)
        assertEquals(0, summary.days.last().endedEarly)
        assertEquals(2, summary.days.last().unconfirmed)
        assertFalse(events.joinToString("\n", transform = HistoryCodec::encode).contains("private-boot-identity"))
    }

    @Test fun historyLossUsesTheOwnersPublicCountOrDiagnosticReason() {
        assertFalse(hasMissingProtectionEvents(ProtectionStatus.UNKNOWN))
        assertTrue(hasMissingProtectionEvents(ProtectionStatus.UNKNOWN.copy(lifecycleEventsDropped = 1)))
        assertFalse(hasMissingProtectionEvents(ProtectionStatus.UNKNOWN.copy(lifecycleEventsDropped = 0)))
        val status = ProtectionStatus.UNKNOWN.copy(detectors = ProtectionStatus.UNKNOWN.detectors.copy(
            degradedReasons = setOf("lifecycle-events-dropped")))
        assertTrue(hasMissingProtectionEvents(status))
        assertFalse(hasMissingProtectionEvents(status.copy(detectors = status.detectors.copy(
            degradedReasons = setOf("usage-access-missing")))))
    }

    /** Real C drain/ack algorithm, with only its transaction backing replaced by a fixture. */
    private inner class Owner(event: ProtectionLifecycleEvent) : ProtectionTransactionStore {
        private var snapshot = ProtectionSnapshot(
            FocusSnapshot(protection = ProtectionLedger(event.at, event.at, outbox = mapOf(event.id to event))),
            emptyList(), emptyList(), emptyList(), EditorCommitmentState(), HarderWaitPolicy.Ledger(), event.at,
        )
        override val state = MutableStateFlow<StorageState<ProtectionSnapshot>>(StorageState.Ready(snapshot))
        val pending get() = snapshot.focus.protection!!.outbox
        var failAck = false
        var acknowledgements = 0
        var beforeAck: suspend () -> Unit = {}
        override suspend fun <R> transact(transform: (ProtectionSnapshot) -> ProtectionMutation<R>): ProtectionCommit<R> {
            beforeAck()
            if (failAck) return ProtectionCommit.Unavailable(StorageFailure.IO)
            val mutation = transform(snapshot)
            snapshot = snapshot.copy(focus = mutation.focus)
            state.value = StorageState.Ready(snapshot)
            acknowledgements++
            return ProtectionCommit.Committed(snapshot, mutation.result)
        }
    }
}
