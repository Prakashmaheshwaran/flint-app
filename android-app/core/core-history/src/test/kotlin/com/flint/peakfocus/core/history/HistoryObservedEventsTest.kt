package com.flint.peakfocus.core.history

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HistoryObservedEventsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val begin = 1_793_196_000_000L
    private val minute = 60_000L
    private fun start() = HistoryEvent(type = "started", session = "session-a", at = begin, tz = "UTC",
        plannedEnd = begin + 60 * minute, tier = "easy", source = "manual", name = "Focus")
    private fun store() = FocusHistoryStore(temporary.newFolder(), Clock.fixed(Instant.ofEpochMilli(begin + 60 * minute), ZoneOffset.UTC))

    @Test fun knownSessionOpenAndOrdinaryBreakAreMeasuredAndEndRouteIsIdempotent() = runTest {
        val store = store(); store.append(start()); val writer = HistoryObservedWriter(store) { ZoneOffset.UTC }
        writer.record(HistoryObservedEvent.Open("session-a", begin + minute))
        writer.record(HistoryObservedEvent.Break("session-a", begin + 10 * minute, begin + 20 * minute))
        repeat(2) { writer.record(HistoryObservedEvent.End("session-a", begin + 30 * minute, "hold_to_end")) }
        val snapshot = store.load(); val session = FocusHistoryAggregator.sessions(snapshot.events, begin + 60 * minute, emptySet()).single()
        assertEquals(1, session.opensIntercepted); assertEquals(1, session.breaks)
        assertEquals(20 * minute, session.focusedMs); assertEquals(SessionOutcome.ENDED_EARLY, session.outcome)
        assertEquals(1, snapshot.events.count { it.knownType == HistoryEventType.ENDED_EARLY })
        val rest = snapshot.events.single { it.knownType == HistoryEventType.BREAK_STARTED }
        assertEquals("ordinary", rest.kind); assertEquals(begin + 20 * minute, rest.breakEnd)
        assertNull(rest.grant)
    }
    @Test fun unknownNonSessionExpiredAndPostTerminalEventsNeverManufactureNumbers() = runTest {
        val store = store(); store.append(start()); val writer = HistoryObservedWriter(store)
        for (id in listOf(null, "ordinary-rule", "session-unknown")) {
            writer.record(HistoryObservedEvent.Open(id, begin + minute))
            writer.record(HistoryObservedEvent.Break(id, begin + minute, begin + 2 * minute))
        }
        writer.record(HistoryObservedEvent.Open("session-a", begin + 60 * minute))
        writer.record(HistoryObservedEvent.Break("session-a", begin + minute, begin))
        writer.record(HistoryObservedEvent.End("session-a", begin + 30 * minute, "cancel"))
        writer.record(HistoryObservedEvent.Open("session-a", begin + 31 * minute))
        val session = FocusHistoryAggregator.sessions(store.load().events, begin + 60 * minute, emptySet()).single()
        assertNull(session.opensIntercepted); assertNull(session.breaks)
        assertEquals(listOf("started", "ended_early"), store.load().events.map { it.type })
    }
    @Test fun successfulTransactionTimestampPrecedesLaterUnknownRouteObservation() = runTest {
        val store = store(); store.append(start())
        store.append(HistoryEvent(type = "ended_early", session = "session-a", at = begin + 31 * minute,
            tz = "UTC", route = "unknown"))
        HistoryObservedWriter(store).record(HistoryObservedEvent.End("session-a", begin + 30 * minute, "hold_to_end"))
        val events = store.load().events
        assertEquals(1, events.count { it.knownType?.terminal == true })
        assertEquals("hold_to_end", events.sortedBy { it.at }.first { it.knownType?.terminal == true }.route)
        assertEquals(30 * minute, FocusHistoryAggregator.sessions(events, begin + 60 * minute, emptySet()).single().focusedMs)
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun synchronousHookHandoffIsBoundedAndDoesNotRunWriterInline() = runTest {
        val written = mutableListOf<HistoryObservedEvent>()
        val queue = HistoryObservedQueue(backgroundScope, { written += it; true }, StandardTestDispatcher(testScheduler))
        repeat(256) { assertTrue(queue.offer(HistoryObservedEvent.Open("session-a", begin + it))) }
        assertTrue(written.isEmpty())
        assertFalse(queue.offer(HistoryObservedEvent.Open("session-a", begin)))
        assertNotNull(queue.diagnostic)
        runCurrent(); assertEquals(256, written.size); assertNull(queue.diagnostic)
    }
}
