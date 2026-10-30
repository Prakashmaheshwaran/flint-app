package com.flint.peakfocus.core.history

import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HistoryClearOwnerActivityTest {
    @get:Rule val temporary = TemporaryFolder()
    private val begin = Instant.parse("2026-10-28T14:00:00Z").toEpochMilli()
    private val now = begin + 7_200_000
    private val clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)
    private fun started(id: String) = HistoryEvent(type = "started", session = id, at = begin,
        tz = "UTC", plannedEnd = begin + 600_000, tier = "easy", source = "manual", name = "Focus")

    @Test fun authoritativeLiveStartSurvivesPastPlanButTerminalledAndUnstartedIdsNeverSurviveClear() = runTest {
        val store = FocusHistoryStore(temporary.newFolder(), clock)
        val live = started("session-live")
        assertTrue(store.update { listOf(live, started("session-ended"),
            HistoryEvent(type = "ended_early", session = "session-ended", at = begin + 300_000,
                tz = "UTC", route = "manual")) })
        val active = setOf("session-live", "session-ended", "session-unstarted")
        val before = FocusHistoryAggregator.sessions(store.load().events, now, active).first { it.id == live.session }
        assertEquals(SessionOutcome.UNCONFIRMED, before.outcome)
        assertEquals(0L, before.focusedMs)
        assertTrue(store.clear { active })
        val snapshot = FocusHistoryStore(store.directory, clock).load()
        assertEquals(listOf(live), snapshot.events)
        val retained = FocusHistoryAggregator.sessions(snapshot.events, now, active).single()
        assertEquals(before, retained) // Clear does not change outcomes or invent historical coverage.
        assertEquals(now, snapshot.clearedAt)
    }

    @Test fun unknownOrThrowingOwnerReadCannotStartClearOrChangeExistingFilesOrHealth() = runTest {
        for (owner in listOf<() -> Set<String>?>({ null }, { throw IOException("Owner unavailable") })) {
            val directory = temporary.newFolder()
            var clearPhases = 0
            val store = FocusHistoryStore(directory, clock, clearPhase = { clearPhases++ })
            assertTrue(store.append(started("session-live")))
            val log = File(directory, "events.v1.jsonl").readBytes()
            val metadata = File(directory, "metadata.v1.json").readBytes()
            assertFalse(store.clear(owner))
            assertEquals(0, clearPhases)
            assertArrayEquals(log, File(directory, "events.v1.jsonl").readBytes())
            assertArrayEquals(metadata, File(directory, "metadata.v1.json").readBytes())
            assertFalse(store.load().degraded)
            assertNull(store.load().clearedAt)
        }
        val emptyDirectory = temporary.newFolder()
        val unloaded = FocusHistoryStore(emptyDirectory, clock)
        assertFalse(unloaded.clear { null })
        assertTrue(emptyDirectory.listFiles()!!.isEmpty())
        assertFalse(unloaded.snapshots.value.degraded)
    }

    @Test fun cancellationReadingOwnerPropagatesBeforeClearIntentAndPreservesFiles() = runTest {
        val directory = temporary.newFolder()
        val store = FocusHistoryStore(directory, clock, clearPhase = { error("Clear cannot start") })
        assertTrue(store.append(started("session-live")))
        val before = directory.listFiles()!!.associate { it.name to it.readBytes().toList() }
        try {
            store.clear { throw CancellationException("Owner read cancelled") }
            fail("Cancellation was swallowed")
        } catch (_: CancellationException) { }
        assertEquals(before, directory.listFiles()!!.associate { it.name to it.readBytes().toList() })
        assertFalse(store.load().degraded)
        assertNull(store.load().clearedAt)
    }
}
