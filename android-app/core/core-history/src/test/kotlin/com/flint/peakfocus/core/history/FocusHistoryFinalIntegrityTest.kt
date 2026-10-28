package com.flint.peakfocus.core.history

import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FocusHistoryFinalIntegrityTest {
    @get:Rule val temporary = TemporaryFolder()
    private val begin = 1_793_196_000_000L
    private val minute = 60_000L
    private class MutableClock(var now: Long) : Clock() {
        override fun instant() = Instant.ofEpochMilli(now)
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
    private fun start() = HistoryEvent(type = "started", session = "session-a", at = begin, tz = "UTC",
        plannedEnd = begin + 60 * minute, tier = "easy", source = "manual", name = "Focus")

    @Test fun N7_rawCarriageReturnAndCrlfRecordsRoundTripThroughAppendRepairAndCompaction() = runTest {
        val dir = temporary.newFolder(); val store = FocusHistoryStore(dir, MutableClock(begin))
        val raw = "{\"v\":2,\"id\":\"raw-cr\",\"type\":\"future\",\"session\":\"session-a\",\"at\":$begin,\"tz\":\"UTC\",\"payload\":[\r1,2]}"
        val crlf = raw.replace("raw-cr", "crlf") + "\r"
        assertTrue(store.append(HistoryCodec.decode(raw))); assertTrue(store.append(HistoryCodec.decode(crlf)))
        val expected = "$raw\n$crlf\n"
        assertEquals(expected, File(dir, "events.v1.jsonl").readText())
        File(dir, "events.v1.jsonl").appendText("bad\n")
        assertTrue(store.compact())
        val reopened = FocusHistoryStore(dir, MutableClock(begin)).load()
        assertEquals(listOf(raw, crlf), reopened.events.map(HistoryCodec::encode))
        assertEquals(expected, File(dir, "events.v1.jsonl").readText())
    }
    @Test fun N8_oversizedSingleRecordAndBatchAreRejectedBeforeAnyWrite() = runTest {
        val dir = temporary.newFolder(); val store = FocusHistoryStore(dir, MutableClock(begin)); store.append(start())
        val log = File(dir, "events.v1.jsonl"); val before = log.readBytes()
        fun future(id: String, size: Int) = HistoryEvent(type = "future", session = id, at = begin, tz = "UTC", reason = "x".repeat(size))
        assertFalse(store.update { listOf(future("valid-first", 1), future("too-big", 64 * 1024)) })
        assertArrayEquals(before, log.readBytes())
        assertFalse(store.update { (1..18).map { future("batch-$it", 60 * 1024) } })
        assertArrayEquals(before, log.readBytes())
        assertFalse(store.update { (1..3).map { future("huge-$it", 4 * 1024 * 1024) } })
        assertArrayEquals(before, log.readBytes()); assertEquals(1, FocusHistoryStore(dir, MutableClock(begin)).load().events.size)
    }
    @Test fun N4_multiEditRecoveredShorteningIsAtomicAndIdempotent() = runTest {
        val dir = temporary.newFolder(); val clock = MutableClock(begin); val store = FocusHistoryStore(dir, clock)
        val observer = HistoryReconciler(store, clock)
        val rule = HistoryRule("session-a", "Focus", "easy", begin + 60 * minute)
        observer.observe(listOf(rule)); clock.now += 30 * minute
        observer.observe(listOf(rule.copy(plannedEnd = begin + 120 * minute)))
        clock.now = begin + 180 * minute
        repeat(2) { observer.observe(listOf(rule.copy(plannedEnd = begin + 20 * minute))) }
        val events = store.load().events
        assertEquals(listOf("started", "rescheduled", "rescheduled", "completed"), events.map { it.type })
        assertNull(observer.diagnostic.value)
        assertEquals(20 * minute, FocusHistoryAggregator.sessions(events, clock.now, emptySet()).single().focusedMs)
        val bytes = File(dir, "events.v1.jsonl").readBytes()
        assertFalse(store.update { listOf(HistoryEvent(type = "rescheduled", session = "session-a", at = begin,
            tz = "UTC", plannedEnd = begin + 500 * minute),
            HistoryEvent(type = "completed", session = "session-a", at = begin + minute, tz = "UTC")) })
        assertArrayEquals(bytes, File(dir, "events.v1.jsonl").readBytes())
    }
    @Test fun N5_envelopeStringsAboveOldLimitSurviveRepairCompactionAndReopen() = runTest {
        for (length in listOf(4096, 4105, 65_000, 65_536)) {
            val dir = temporary.newFolder(); val store = FocusHistoryStore(dir, MutableClock(begin)); store.load()
            val line = "{\"v\":2,\"id\":\"long\",\"type\":\"future\",\"session\":\"${"s".repeat(length)}\",\"at\":$begin,\"tz\":\"UTC\"}"
            File(dir, "events.v1.jsonl").writeText("$line\nbad\n")
            assertTrue(store.compact())
            assertEquals(line, FocusHistoryStore(dir, MutableClock(begin)).load().events.single().originalLine)
        }
    }
    @Test fun N6_passReferenceBoundsRejectAppendAndReadUnsupportedReferencesOpaquely() = runTest {
        val dir = temporary.newFolder(); val store = FocusHistoryStore(dir, MutableClock(begin)); store.append(start())
        fun pass(count: Int) = HistoryEvent(type = "pass_used", session = "operation", at = begin, tz = "UTC",
            sessions = (1..count).map { "session-$it" }, recoveryEnd = begin + minute)
        val before = File(dir, "events.v1.jsonl").readBytes()
        assertFalse(store.append(pass(257))); assertArrayEquals(before, File(dir, "events.v1.jsonl").readBytes())
        assertTrue(store.append(pass(256)))
        val external = HistoryCodec.encode(pass(7001))
        File(dir, "events.v1.jsonl").appendText("$external\n")
        val parsed = store.load().events.last()
        assertNull(parsed.knownType); assertNull(parsed.sessions); assertEquals(external, parsed.originalLine)
        assertTrue(store.compact())
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun deadlineWakeupReobservesStableReadyAndCancelsOnEditLoadingAndUnavailable() = runTest {
        val clock = MutableClock(begin)
        val first = HistoryRulesSnapshot.Ready(listOf(HistoryRule("session-a", "Focus", "easy", begin + minute)))
        val source = MutableStateFlow<HistoryRulesSnapshot>(first)
        val seen = mutableListOf<HistoryRulesSnapshot>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            source.withDeadlineObservations(clock).collect { seen += it }
        }
        runCurrent(); assertEquals(1, seen.size)
        clock.now = begin + minute; advanceTimeBy(minute); runCurrent(); assertEquals(2, seen.size)
        val extended = first.copy(rules = first.rules.map { it.copy(plannedEnd = begin + 3 * minute) })
        source.value = extended; runCurrent()
        source.value = HistoryRulesSnapshot.Unavailable("CORRUPT"); runCurrent()
        val count = seen.size; clock.now = begin + 4 * minute; advanceTimeBy(3 * minute); runCurrent()
        assertEquals(count, seen.size)
        source.value = first.copy(rules = first.rules.map { it.copy(plannedEnd = begin + 5 * minute) }); runCurrent()
        source.value = HistoryRulesSnapshot.Loading; runCurrent()
        val loadingCount = seen.size; clock.now += 2 * minute; advanceTimeBy(2 * minute); runCurrent()
        assertEquals(loadingCount, seen.size)
    }
}
