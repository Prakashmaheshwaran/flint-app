package com.flint.peakfocus

import com.flint.peakfocus.blocking.engine.HarderWaitPolicy
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
import com.flint.peakfocus.core.history.SessionOutcome
import com.flint.peakfocus.core.model.AccessClockStamp
import com.flint.peakfocus.core.model.FocusSnapshot
import com.flint.peakfocus.core.model.ProtectionBreakEndReason
import com.flint.peakfocus.core.model.ProtectionLedger
import com.flint.peakfocus.core.model.ProtectionLifecycleEvent
import com.flint.peakfocus.core.model.ProtectionLifecycleKind
import com.flint.peakfocus.core.model.StorageFailure
import com.flint.peakfocus.core.model.StorageState
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProtectionHistoryConsumerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val begin = 1_793_196_000_000L
    private val minute = 60_000L
    private val clock = Clock.fixed(Instant.ofEpochMilli(begin + 60 * minute), ZoneOffset.UTC)
    private fun stamp(at: Long) = AccessClockStamp(at, at - begin, "private-boot-identity")
    private fun store(directory: File = temporary.newFolder()) = FocusHistoryStore(directory, clock)
    private fun consumer(store: FocusHistoryStore) = ProtectionHistoryConsumer(store) { ZoneOffset.UTC }
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
        val wire = events.joinToString("\n", transform = HistoryCodec::encode)
        for (privateValue in listOf("schedule-a", "limit-a", "private-boot-identity", "elapsedRealtime", "affectedRuleIds"))
            assertFalse(privateValue, wire.contains(privateValue))
        assertTrue(events.all { it.v == 1 })
    }

    @Test fun globalPassWithNoFocusSessionsStillRecordsExactlyOneOperation() = runBlocking {
        val store = store()
        assertTrue(consumer(store).deliver(pass().copy(affectedRuleIds = setOf("schedule-a"), endedTransientIds = emptySet())))
        val only = store.load().events.single()
        assertEquals(HistoryEventType.PASS_USED, only.knownType)
        assertEquals(emptyList<String>(), only.sessions)
        assertTrue(FocusHistoryAggregator.sessions(listOf(only), clock.millis(), emptySet()).isEmpty())
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

    @Test fun ownerClockUncertaintyStaysInferredWithoutCopyingPrivateClockIdentity() = runBlocking {
        val store = store()
        assertTrue(consumer(store).deliver(pass().copy(timeUncertain = true)))
        val events = store.load().events
        assertTrue(events.all { it.inferred })
        assertFalse(events.joinToString("\n", transform = HistoryCodec::encode).contains("private-boot-identity"))
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
