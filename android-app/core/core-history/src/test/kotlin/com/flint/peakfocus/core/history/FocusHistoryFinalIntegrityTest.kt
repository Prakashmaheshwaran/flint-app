package com.flint.peakfocus.core.history

import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FocusHistoryFinalIntegrityTest {
    @get:Rule val temporary = TemporaryFolder()
    private val begin = 1_793_196_000_000L
    private val minute = 60_000L
    private class MutableClock(@Volatile var now: Long) : Clock() {
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
    @Test fun N10_physicalLineFeedInOpaqueRecordRejectsWholeBatchButEscapedNewlinesRoundTrip() = runTest {
        val dir = temporary.newFolder(); val store = FocusHistoryStore(dir, MutableClock(begin))
        val initial = start(); assertTrue(store.append(initial))
        val log = File(dir, "events.v1.jsonl"); val before = log.readBytes()
        val multiline = "{\"v\":2,\"id\":\"multiline\",\"type\":\"future\",\"session\":\"session-a\",\"at\":$begin,\"tz\":\"UTC\",\"payload\":[\n1,2]}"
        val escaped = HistoryEvent(type = "future", id = "escaped-newline", session = "session-a", at = begin,
            tz = "UTC", reason = "first\nsecond")
        assertFalse(store.update { listOf(escaped, HistoryCodec.decode(multiline)) })
        assertArrayEquals(before, log.readBytes())
        assertTrue(store.append(escaped))
        val reopened = FocusHistoryStore(dir, MutableClock(begin)).load()
        assertEquals(listOf(initial.id, escaped.id), reopened.events.map { it.id })
        assertTrue(HistoryCodec.encode(reopened.events.last()).contains("first\\nsecond"))
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
        assertEquals(listOf("started", "rescheduled", "rescheduled"), events.map { it.type })
        assertNull(observer.diagnostic.value)
        assertEquals(0L, FocusHistoryAggregator.sessions(events, clock.now, emptySet()).single().focusedMs)
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
        val terminals = mutableSetOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            source.withDeadlineObservations(clock, { it in terminals }).collect { snapshot ->
                seen += snapshot
                if (snapshot is HistoryRulesSnapshot.Ready) terminals += snapshot.rules.filter {
                    it.plannedEnd?.let { end -> end <= clock.now } == true
                }.map { it.id }
            }
        }
        runCurrent(); assertEquals(1, seen.size)
        clock.now = begin + minute; advanceTimeBy(minute); runCurrent(); assertEquals(2, seen.size)
        val extended = first.copy(rules = first.rules.map { it.copy(id = "session-b", plannedEnd = begin + 3 * minute) })
        source.value = extended; runCurrent()
        source.value = HistoryRulesSnapshot.Unavailable("CORRUPT"); runCurrent()
        val count = seen.size; clock.now = begin + 4 * minute; advanceTimeBy(3 * minute); runCurrent()
        assertEquals(count, seen.size)
        source.value = first.copy(rules = first.rules.map { it.copy(id = "session-c", plannedEnd = begin + 5 * minute) }); runCurrent()
        source.value = HistoryRulesSnapshot.Loading; runCurrent()
        val loadingCount = seen.size; clock.now += 2 * minute; advanceTimeBy(2 * minute); runCurrent()
        assertEquals(loadingCount, seen.size)
    }
    @Test fun N9_rollbackDuringDueDeliveryRetriesUnchangedReadyUntilCompletionIsDurable() = runBlocking {
        val clock = MutableClock(begin); val deadline = begin + 50
        val store = FocusHistoryStore(temporary.newFolder(), clock)
        store.append(start().copy(plannedEnd = deadline))
        val observer = HistoryReconciler(store, clock)
        val source = MutableStateFlow<HistoryRulesSnapshot>(HistoryRulesSnapshot.Ready(
            listOf(HistoryRule("session-a", "Focus", "easy", deadline))))
        val observations = Channel<HistorySnapshot>(Channel.UNLIMITED)
        var rollbackPending = true
        val collector = launch {
            source.withDeadlineObservations(clock,
                { id -> store.snapshots.value.events.any { it.session == id && it.knownType?.terminal == true } },
                retryEveryMs = 25,
            ).collect { snapshot ->
                if (snapshot is HistoryRulesSnapshot.Ready) {
                    if (rollbackPending && clock.now >= deadline) { clock.now = begin; rollbackPending = false }
                    observer.observe(snapshot.rules)
                    observations.send(store.snapshots.value)
                }
            }
        }
        try {
            withTimeout(10_000) { observations.receive() }
            clock.now = deadline
            val rolledBack = withTimeout(10_000) { observations.receive() }
            assertFalse(rollbackPending)
            assertFalse(rolledBack.events.any { it.knownType == HistoryEventType.COMPLETED })
            clock.now = deadline + 1
            val stillOpen = withTimeout(10_000) { observations.receive() }
            assertFalse(stillOpen.events.any { it.knownType == HistoryEventType.COMPLETED })
            // Only a durable owner/outbox transition can confirm completion.
            assertTrue(store.append(HistoryEvent(type = "completed", session = "session-a", at = deadline, tz = "UTC", originRoute = "service", observedAt = clock.now)))
            val completed = withTimeout(10_000) { store.snapshots.first { log -> log.events.any { it.knownType == HistoryEventType.COMPLETED } } }
            assertEquals(deadline, completed.events.single { it.knownType == HistoryEventType.COMPLETED }.at)
            assertEquals(1, completed.events.count { it.knownType == HistoryEventType.COMPLETED })
        } finally { collector.cancelAndJoin(); observations.close() }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun expiredRuleWithoutStartDoesNotRetryAndFutureStartAppendCanFinish() = runTest {
        val clock = MutableClock(begin)
        val rule = HistoryRule("session-a", "Focus", "easy", begin - 1)
        val source = MutableStateFlow<HistoryRulesSnapshot>(HistoryRulesSnapshot.Ready(listOf(rule)))
        var started = false; var terminal = false; var observations = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            source.withDeadlineObservations(clock, hasTerminal = { terminal }, hasStarted = { started }).collect { snapshot ->
                observations++
                if (started && snapshot is HistoryRulesSnapshot.Ready && snapshot.rules.any { it.plannedEnd!! <= clock.now }) terminal = true
            }
        }
        runCurrent(); advanceTimeBy(90_000); runCurrent()
        assertEquals(1, observations)
        source.value = HistoryRulesSnapshot.Ready(listOf(rule.copy(plannedEnd = begin + minute)))
        runCurrent(); assertEquals(2, observations)
        started = true; clock.now = begin + minute
        advanceTimeBy(minute); runCurrent()
        assertEquals(3, observations); assertTrue(terminal)
    }
}
