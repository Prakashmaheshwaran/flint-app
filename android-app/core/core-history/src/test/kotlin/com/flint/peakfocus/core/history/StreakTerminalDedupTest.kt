package com.flint.peakfocus.core.history

import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Terminal-event edge cases exercised through the production store: append validation,
 * id deduplication, and first-explicit-terminal-wins.
 *
 * Each case asserts both the persisted terminal count (the store rule) and the reloaded
 * aggregate (the streak the app reads), because the aggregator also applies first-wins and
 * would hide a store that kept both terminals.
 */
class StreakTerminalDedupTest {
    @get:Rule val temporary = TemporaryFolder()

    private val zone = ZoneId.of("America/New_York")
    private val hour = 3_600_000L
    private val now = LocalDateTime.parse("2026-10-28T12:00:00").atZone(zone).toInstant().toEpochMilli()
    private val start = at(0, 9)
    private val end = at(0, 10)

    private fun at(dayOffset: Long, hourOfDay: Int): Long =
        LocalDateTime.of(2026, 10, 28, hourOfDay, 0).plusDays(dayOffset).atZone(zone).toInstant().toEpochMilli()

    private fun event(type: HistoryEventType, id: String, session: String = "s", at: Long = end,
                      route: String? = null, by: String? = null) = HistoryEvent(
        id = id, type = type.wireName, session = session, at = at, tz = zone.id,
        plannedEnd = if (type == HistoryEventType.STARTED) at + hour else null,
        tier = if (type == HistoryEventType.STARTED) "easy" else null,
        source = if (type == HistoryEventType.STARTED) "manual" else null,
        name = if (type == HistoryEventType.STARTED) "Focus" else null,
        route = route, by = by,
    )

    private fun openStore(dir: File): FocusHistoryStore = FocusHistoryStore(
        dir, Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC),
    ) { zone }

    /** Reloads from disk through a fresh store: the production read path. */
    private suspend fun persisted(dir: File): HistorySnapshot = openStore(dir).load()

    private suspend fun summary(dir: File): FocusSummary =
        FocusHistoryAggregator.summarize(persisted(dir), now, zone, emptySet())

    private fun terminals(snapshot: HistorySnapshot) =
        snapshot.events.filter { it.knownType?.terminal == true }

    @Test fun duplicateTerminalDeliveryCreditsOneDay() = runBlocking {
        val dir = temporary.newFolder()
        val store = openStore(dir)
        assertTrue(store.append(event(HistoryEventType.STARTED, id = "start", at = start)))
        val completed = event(HistoryEventType.COMPLETED, id = "terminal")
        assertTrue(store.append(completed))
        store.append(completed.copy())

        assertEquals("the same event id is never stored twice", 1, terminals(persisted(dir)).size)
        val summary = summary(dir)
        assertEquals(1, summary.days.last().completed)
        assertEquals(1, summary.streak)
    }

    @Test fun differentTerminalForTheSameSessionIsRefused() = runBlocking {
        val dir = temporary.newFolder()
        val store = openStore(dir)
        store.append(event(HistoryEventType.STARTED, id = "start", at = start))
        assertTrue(store.append(event(HistoryEventType.COMPLETED, id = "completed")))
        store.append(event(HistoryEventType.ENDED_EARLY, id = "early", route = "manual_stop"))

        val persisted = terminals(persisted(dir))
        assertEquals("the store keeps exactly one terminal", 1, persisted.size)
        assertEquals(HistoryEventType.COMPLETED, persisted.single().knownType)
        val summary = summary(dir)
        assertEquals(1, summary.days.last().completed)
        assertEquals(0, summary.days.last().endedEarly)
        assertEquals(1, summary.streak)
    }

    @Test fun endedEarlyFirstThenCompletionKeepsTheEarlyEnd() = runBlocking {
        val dir = temporary.newFolder()
        val store = openStore(dir)
        store.append(event(HistoryEventType.STARTED, id = "start", at = start))
        assertTrue(store.append(event(HistoryEventType.ENDED_EARLY, id = "early", route = "manual_stop")))
        store.append(event(HistoryEventType.COMPLETED, id = "completed"))

        val persisted = terminals(persisted(dir))
        assertEquals("a later completion cannot replace the stored outcome", 1, persisted.size)
        assertEquals(HistoryEventType.ENDED_EARLY, persisted.single().knownType)
        val summary = summary(dir)
        assertEquals(0, summary.days.last().completed)
        assertEquals(1, summary.days.last().endedEarly)
        assertEquals(0, summary.streak)
    }

    @Test fun cancelledSessionDoesNotCreditADay() = runBlocking {
        val dir = temporary.newFolder()
        val store = openStore(dir)
        store.append(event(HistoryEventType.STARTED, id = "start", at = start))
        // Production cancellation records ENDED_EARLY with the "cancel" route.
        assertTrue(store.append(event(HistoryEventType.ENDED_EARLY, id = "cancel", route = "cancel")))

        val summary = summary(dir)
        assertEquals(0, summary.days.last().completed)
        assertEquals(0, summary.streak)
    }

    @Test fun supersededSessionDoesNotCreditADay() = runBlocking {
        val dir = temporary.newFolder()
        val store = openStore(dir)
        store.append(event(HistoryEventType.STARTED, id = "start", at = start))
        // The codec requires the replacement identity on a superseded terminal.
        assertTrue(store.append(event(HistoryEventType.SUPERSEDED, id = "superseded", by = "next")))

        val persisted = terminals(persisted(dir))
        assertEquals(1, persisted.size)
        assertEquals(HistoryEventType.SUPERSEDED, persisted.single().knownType)
        val summary = summary(dir)
        assertEquals(0, summary.days.last().completed)
        assertEquals(0, summary.streak)
        assertEquals(0, summary.bestRecordedStreak)
    }
}
