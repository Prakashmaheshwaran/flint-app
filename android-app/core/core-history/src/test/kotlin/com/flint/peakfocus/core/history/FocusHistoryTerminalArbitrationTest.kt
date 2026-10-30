package com.flint.peakfocus.core.history

import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FocusHistoryTerminalArbitrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val begin = 1_793_196_000_000L
    private val minute = 60_000L
    private val now = begin + 120 * minute
    private val clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)
    private fun start() = HistoryEvent(type = "started", session = "session-a", at = begin, tz = "UTC",
        plannedEnd = begin + 60 * minute, tier = "easy", source = "manual", name = "Focus")
    private fun unknown(at: Long = begin + 31 * minute) = HistoryEvent(type = "ended_early",
        session = "session-a", at = at, tz = "UTC", route = "unknown")
    private fun explicit(type: String) = HistoryEvent(type = type, session = "session-a", tz = "UTC",
        at = begin + (if (type == "completed") 60 else 30) * minute,
        route = if (type == "ended_early") "hold_to_end" else null,
        by = if (type == "superseded") "session-b" else null, originRoute = "app_ui")
    private fun raw(store: FocusHistoryStore) = File(store.directory, "events.v1.jsonl")
        .readLines().map { HistoryCodec.decode(it) }

    @Test fun explicitTerminalsWinBothArrivalOrdersAndBothBatchOrdersWithoutChangingTotals() = runTest {
        for (type in listOf("completed", "ended_early", "superseded")) {
            for (unknownFirst in listOf(true, false)) for (sameBatch in listOf(true, false)) {
                val store = FocusHistoryStore(temporary.newFolder(), clock)
                val known = explicit(type)
                val observation = unknown()
                assertTrue(store.append(start()))
                val ordered = if (unknownFirst) listOf(observation, known) else listOf(known, observation)
                if (sameBatch) assertTrue(store.update { ordered })
                else ordered.forEach { assertTrue(store.append(it)) }
                val baseline = FocusHistoryAggregator.sessions(listOf(start(), known), now, emptySet()).single()
                // A new store must enforce the same arbitration after process restart;
                // distinct-ID replays cannot insert a second terminal either.
                val reopened = FocusHistoryStore(store.directory, clock)
                assertTrue(reopened.append(observation.copy(id = "replayed-unknown")))
                assertTrue(reopened.append(known.copy(id = "replayed-explicit")))
                val events = reopened.load().events
                assertEquals(known.id, events.single { it.knownType?.terminal == true }.id)
                assertEquals(known.route, raw(reopened).single { it.knownType?.terminal == true }.route)
                assertEquals(2, raw(reopened).size)
                val session = FocusHistoryAggregator.sessions(events, now, emptySet()).single()
                assertEquals(baseline.focusedMs, session.focusedMs)
                assertEquals(baseline.outcome, session.outcome)
            }
        }
    }

    @Test fun delayedAppEndOverridesEarlierOrLaterDisappearanceObservation() = runTest {
        for (unknownFirst in listOf(true, false)) for (offset in listOf(-minute, minute)) {
            val mutableClock = object : Clock() {
                var time = begin
                override fun instant() = Instant.ofEpochMilli(time)
                override fun getZone() = ZoneOffset.UTC
                override fun withZone(zone: ZoneId): Clock = this
            }
            val store = FocusHistoryStore(temporary.newFolder(), mutableClock)
            val observer = HistoryReconciler(store, mutableClock, { ZoneOffset.UTC })
            observer.observe(listOf(HistoryRule("session-a", "Focus", "easy", begin + 60 * minute)))
            suspend fun disappear() {
                mutableClock.time = begin + 30 * minute + offset
                observer.observe(emptyList())
            }
            if (unknownFirst) {
                disappear()
                assertEquals("unknown", raw(store).single { it.knownType?.terminal == true }.route)
            }
            assertTrue(HistoryObservedWriter(store).record(
                HistoryObservedEvent.End("session-a", begin + 30 * minute, "hold_to_end")))
            if (!unknownFirst) disappear()
            val events = FocusHistoryStore(store.directory, clock).load().events
            assertEquals("hold_to_end", events.single { it.knownType?.terminal == true }.route)
            assertEquals("app_ui", raw(store).single { it.knownType?.terminal == true }.originRoute)
            assertEquals(30 * minute, FocusHistoryAggregator.sessions(events, now, emptySet()).single().focusedMs)
        }
    }

    @Test fun firstExplicitOutcomeSurvivesConflictingLaterDeliveryRegardlessOfTimestamp() = runTest {
        for (firstType in listOf("completed", "ended_early", "superseded")) {
            val store = FocusHistoryStore(temporary.newFolder(), clock)
            assertTrue(store.append(start()))
            val first = explicit(firstType)
            assertTrue(store.append(first))
            val bytes = File(store.directory, "events.v1.jsonl").readBytes()
            for (type in listOf("completed", "ended_early", "superseded")) assertTrue(store.append(explicit(type)))
            assertArrayEquals(bytes, File(store.directory, "events.v1.jsonl").readBytes())
            assertEquals(first.id, raw(store).single { it.knownType?.terminal == true }.id)
        }
    }

    @Test fun invalidReplacementBatchLeavesOriginalTerminalAndBytesIntact() = runTest {
        val store = FocusHistoryStore(temporary.newFolder(), clock)
        assertTrue(store.update { listOf(start(), unknown()) })
        val before = File(store.directory, "events.v1.jsonl").readBytes()
        assertFalse(store.update { listOf(explicit("ended_early"), start().copy(id = "bad", tier = "invalid")) })
        assertArrayEquals(before, File(store.directory, "events.v1.jsonl").readBytes())
        assertEquals("unknown", raw(store).single { it.knownType?.terminal == true }.route)
    }

    @Test fun legacyDuplicatesAndForwardFieldsRemainReadableAndVerbatim() = runTest {
        val store = FocusHistoryStore(temporary.newFolder(), clock)
        store.load()
        val known = explicit("ended_early")
        val lines = listOf(start(), unknown(), known).map {
            HistoryCodec.encode(it).dropLast(1) + ", \"futureField\": {\"nested\": true}}"
        }
        File(store.directory, "events.v1.jsonl").writeText(lines.joinToString("\n", postfix = "\n"))
        val snapshot = FocusHistoryStore(store.directory, clock).load()
        assertEquals(2, snapshot.events.count { it.knownType?.terminal == true })
        assertEquals(30 * minute, FocusHistoryAggregator.sessions(snapshot.events, now, emptySet()).single().focusedMs)
        assertTrue(store.compact())
        assertEquals(lines, File(store.directory, "events.v1.jsonl").readLines())
    }

    @Test fun replacementPreservesUnrelatedAndUnsupportedRecordsVerbatim() = runTest {
        val store = FocusHistoryStore(temporary.newFolder(), clock)
        val future = unknown().copy(v = 2, id = "future")
        val unrelated = explicit("ended_early").copy(session = "session-other")
        assertTrue(store.update { listOf(start(), unknown(), future, unrelated) })
        val preserved = raw(store).filter { it.id in setOf(future.id, unrelated.id) }.map { it.originalLine }
        assertTrue(store.append(explicit("completed")))
        assertEquals(preserved, raw(store).filter { it.id in setOf(future.id, unrelated.id) }.map { it.originalLine })
        assertEquals(1, raw(store).count { it.session == "session-a" && it.knownType?.terminal == true })
    }
}
