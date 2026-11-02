package com.flint.peakfocus.core.history

import com.flint.peakfocus.core.model.ActiveRulesHolder
import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.BlockTargets
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryOwnerSnapshotsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun startAndCancelBetweenClockTicksKeepOneStartedAndEarlyOutcome() = runTest {
        val begin = 1_793_196_000_000L
        val clock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = Instant.ofEpochMilli(millis())
            override fun millis(): Long = begin + testScheduler.currentTime
        }
        val original = ActiveRulesHolder.dataStoreState
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, emptyList())
        val store = FocusHistoryStore(temporary.newFolder(), clock)
        val observer = HistoryReconciler(store, clock, authoritativeSnapshot = {
            historyRulesSnapshot(ActiveRulesHolder.dataStoreState)
        })
        val collecting = backgroundScope.launch {
            historyOwnerSnapshots().collect { snapshot ->
                // Keep real fsync work from auto-advancing virtual ticker time while
                // runTest waits for Dispatchers.IO. Publications still use the real owner.
                runBlocking { observer.collectSnapshots(flowOf(snapshot)) }
            }
        }
        try {
            runCurrent()
            advanceTimeBy(100)
            val id = "session-timer-v2-${clock.millis()}-short"
            ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, listOf(
                BlockRule(id, "Focus timer", BlockTargets(), expiresAtEpochMs = begin + 300_100),
            ))
            runCurrent()
            advanceTimeBy(600)
            assertTrue(runBlocking {
                HistoryObservedWriter(store).record(HistoryObservedEvent.End(id, clock.millis(), "cancel"))
            })
            ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, emptyList())
            runCurrent()
            assertEquals(700L, testScheduler.currentTime)
            val events = runBlocking { store.load().events }
            assertEquals(listOf("started", "ended_early"), events.map { it.type })
            val result = FocusHistoryAggregator.sessions(events, clock.millis(), emptySet()).single()
            assertEquals(SessionOutcome.ENDED_EARLY, result.outcome)
            assertEquals(600L, result.focusedMs)
        } finally {
            collecting.cancel()
            runCurrent()
            ActiveRulesHolder.publishState(ActiveRulesHolder.SOURCE_DATASTORE, original)
        }
    }

    @Test fun clockTicksRefreshWithoutPublicationAndCancellationUnsubscribes() = runTest {
        var current: HistoryRulesSnapshot = HistoryRulesSnapshot.Loading
        var listener: (() -> Unit)? = null
        var unsubscribed = false
        val seen = mutableListOf<HistoryRulesSnapshot>()
        val collecting = backgroundScope.launch {
            historyOwnerSnapshots(read = { current }, subscribe = { callback ->
                listener = callback
                val unsubscribe: () -> Unit = { unsubscribed = true; listener = null }
                unsubscribe
            }).collect { seen += it }
        }
        runCurrent()
        current = HistoryRulesSnapshot.Ready(emptyList())
        advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf(HistoryRulesSnapshot.Loading, current), seen)
        current = HistoryRulesSnapshot.Unavailable("CORRUPT")
        listener!!(); runCurrent()
        assertEquals(current, seen.last())
        collecting.cancel(); runCurrent()
        assertTrue(unsubscribed)
        assertNull(listener)
    }
}
