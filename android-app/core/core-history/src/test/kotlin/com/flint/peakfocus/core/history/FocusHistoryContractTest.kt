package com.flint.peakfocus.core.history

import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FocusHistoryContractTest {
    @get:Rule val temporary = TemporaryFolder()
    private val zone = ZoneId.of("America/New_York")
    private val start = time("2026-10-28T10:00:00")
    private val hour = 3_600_000L
    private fun time(local: String, inZone: ZoneId = zone) = java.time.LocalDateTime.parse(local).atZone(inZone).toInstant().toEpochMilli()
    private fun event(type: HistoryEventType, at: Long, session: String = "a", inferred: Boolean = false) = HistoryEvent(
        type = type.wireName, session = session, at = at, tz = zone.id, inferred = inferred,
        plannedEnd = if (type == HistoryEventType.STARTED) at + hour else null,
        tier = if (type == HistoryEventType.STARTED) "easy" else null,
        source = if (type == HistoryEventType.STARTED) "manual" else null,
        name = if (type == HistoryEventType.STARTED) "Focus" else null,
        route = if (type == HistoryEventType.ENDED_EARLY) "unknown" else null,
    )
    private fun started(at: Long = start, id: String = "a") = event(HistoryEventType.STARTED, at, id)
    private fun complete(at: Long = start + hour, id: String = "a") = event(HistoryEventType.COMPLETED, at, id)
    private fun summary(events: List<HistoryEvent>, now: Long = start + 2 * hour, inZone: ZoneId = zone,
        active: Set<String> = emptySet(), historyStart: Long = start - 10 * 24 * hour): FocusSummary =
        FocusHistoryAggregator.summarize(HistorySnapshot(events, historyStart, true), now, inZone, active)
    private fun focused(events: List<HistoryEvent>) = summary(events).sessions.single().focusedMs
    private fun store(clock: Clock = Clock.fixed(Instant.ofEpochMilli(start), ZoneOffset.UTC), dir: File = temporary.newFolder()) =
        FocusHistoryStore(dir, clock) { zone }

    @Test fun FH01_completedCreditsPlannedDuration() {
        val result = summary(listOf(started(), complete()))
        assertEquals(SessionOutcome.COMPLETED, result.sessions.single().outcome)
        assertEquals(hour, result.days.last().focusedMs)
        assertEquals(1, result.days.last().completed)
    }
    @Test fun FH02_endedEarlyCreditsObservedHalf() {
        val result = summary(listOf(started(), event(HistoryEventType.ENDED_EARLY, start + hour / 2)))
        assertEquals(hour / 2, result.sessions.single().focusedMs)
        assertEquals(1, result.days.last().endedEarly)
    }
    @Test fun FH03_duplicateTerminalsCountOnce() {
        val result = summary(listOf(started(), complete(), complete().copy(inferred = true)))
        assertEquals(hour, result.days.last().focusedMs)
        assertEquals(1, result.days.last().completed)
    }
    @Test fun FH04_firstTerminalWinsWithFileOrderTies() {
        val stop = event(HistoryEventType.ENDED_EARLY, start + hour)
        assertEquals(SessionOutcome.COMPLETED, summary(listOf(started(), complete(), stop)).sessions.single().outcome)
        assertEquals(SessionOutcome.ENDED_EARLY, summary(listOf(started(), stop, complete())).sessions.single().outcome)
    }
    @Test fun FH05_expiredMissingSessionInferredCompleted() {
        val session = summary(listOf(started())).sessions.single()
        assertEquals(SessionOutcome.COMPLETED, session.outcome)
        assertTrue(session.inferred)
        assertEquals(hour, session.focusedMs)
    }
    @Test fun FH06_coldMissingFutureSessionGetsZero() {
        val result = summary(listOf(started()), start + hour / 2).sessions.single()
        assertEquals(SessionOutcome.ENDED_EARLY, result.outcome)
        assertEquals(0L, result.focusedMs)
        assertEquals(0L, focused(listOf(started(), event(HistoryEventType.ENDED_EARLY, start + hour / 2, inferred = true))))
    }
    @Test fun FH07_midnightSplitsFocusedTimeAndCountsEndDay() {
        val begin = time("2026-10-27T23:30:00")
        val result = summary(listOf(started(begin), complete(begin + hour)))
        assertEquals(hour / 2, result.days[result.days.lastIndex - 1].focusedMs)
        assertEquals(hour / 2, result.days.last().focusedMs)
        assertEquals(0, result.days[result.days.lastIndex - 1].completed)
        assertEquals(1, result.days.last().completed)
    }
    @Test fun FH08_dstUsesCalendarDayBoundaries() {
        for ((begin, end, duration, dayHours) in listOf(
            listOf("2026-03-08T01:30:00", "2026-03-08T03:30:00", "1", "23"),
            listOf("2026-11-01T00:30:00", "2026-11-01T02:30:00", "3", "25"),
        )) {
            val a = time(begin); val b = time(end)
            val result = summary(listOf(started(a).copy(plannedEnd = b), complete(b)), b, historyStart = a - hour)
            assertEquals(duration.toLong() * hour, result.days.last().focusedMs)
            val date = LocalDate.parse(begin.take(10))
            assertEquals(dayHours.toLong() * hour, date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - date.atStartOfDay(zone).toInstant().toEpochMilli())
        }
    }
    @Test fun FH09_travelRebucketsCurrentZone() {
        val begin = time("2026-10-28T00:00:00")
        val events = listOf(started(begin), complete(begin + hour))
        val la = summary(events, begin + 2 * hour, ZoneId.of("America/Los_Angeles"))
        assertEquals(LocalDate.parse("2026-10-27"), la.days.last().date)
        assertEquals(hour, la.days.last().focusedMs)
    }
    @Test fun FH10_overlapsUnionWithoutDoubleCounting() {
        val result = summary(listOf(started(), complete(), started(start + hour / 2, "b"), complete(start + 3 * hour / 2, "b")))
        assertEquals(3 * hour / 2, result.days.last().focusedMs)
    }
    @Test fun FH11_breaksSubtractAndClipAndUsePlannedEnd() {
        val breakStart = event(HistoryEventType.BREAK_STARTED, start + 10 * 60_000).copy(breakEnd = start + 30 * 60_000)
        assertEquals(40 * 60_000L, focused(listOf(started(), breakStart, complete())))
        assertEquals(50 * 60_000L, focused(listOf(started(), breakStart, event(HistoryEventType.BREAK_ENDED, start + 20 * 60_000), complete())))
        assertEquals(10 * 60_000L, focused(listOf(started(), breakStart.copy(breakEnd = start + 2 * hour), complete())))
    }
    @Test fun FH12_corruptAndTruncatedLinesQuarantineOnce() = runTest {
        val dir = temporary.newFolder(); val clock = MutableClock(start); val store = store(clock, dir)
        store.append(started())
        File(dir, "events.v1.jsonl").appendText("not json\n{\"truncated\":")
        val read = store.load()
        assertEquals(1, read.events.size)
        assertTrue(read.degraded)
        store.load()
        assertEquals(1, File(dir, "quarantine").listFiles()!!.size)
        clock.value += hour
        store.append(complete())
        val reopened = store(clock, dir).load()
        assertEquals(2, reopened.events.size)
        assertTrue(reopened.degraded)
    }
    @Test fun FH13_futureTypeAndVersionPreservedIgnored() = runTest {
        val store = store()
        val future = HistoryCodec.decode("{\"v\":9,\"id\":\"f\",\"type\":\"started\",\"session\":\"z\",\"at\":$start,\"tz\":\"UTC\",\"future\":true}")
        val unknown = future.copy(v = 1, id = "u", type = "new_event", originalLine = null)
        assertTrue(store.append(future)); assertTrue(store.append(unknown)); store.compact()
        assertEquals(listOf(HistoryCodec.encode(future), HistoryCodec.encode(unknown)), store.snapshots.value.events.map(HistoryCodec::encode))
        assertTrue(summary(store.snapshots.value.events).sessions.isEmpty())
    }
    @Test fun FH14_retentionBoundaryKeepsWholeSessions() = runTest {
        val store = store()
        val boundary = start - FocusHistoryStore.RETENTION_MS
        store.update { listOf(started(boundary - hour, "keep"), complete(boundary, "keep"),
            started(boundary - hour - 1, "drop"), complete(boundary - 1, "drop")) }
        assertTrue(store.compact())
        assertEquals(listOf("keep", "keep"), store.snapshots.value.events.map { it.session })
    }
    @Test fun FH15_clearRetainsRunningEventsAndUpdatesMetadata() = runTest {
        val clock = MutableClock(start); val dir = temporary.newFolder(); val store = store(clock, dir)
        store.append(started(at = start - hour, id = "old")); store.append(complete(at = start, id = "old")); store.append(started(id = "running"))
        clock.value += hour / 2
        assertTrue(store.clear(setOf("running")))
        val reopened = store(clock, dir).load()
        assertEquals(setOf("running"), reopened.events.map { it.session }.toSet())
        assertEquals(clock.value, reopened.historyStart)
        clock.value = start + hour
        assertTrue(store.append(complete(id = "running")))
    }
    @Test fun FH16_absentEmptyZeroDegradedUnavailableDiffer() {
        val result = summary(emptyList(), historyStart = start)
        assertEquals(HistoryAvailability.EMPTY, result.availability)
        assertNull(result.days[result.days.lastIndex - 1].focusedMs)
        assertEquals(0L, result.days.last().focusedMs)
        assertEquals(HistoryAvailability.UNAVAILABLE, FocusHistoryAggregator.summarize(HistorySnapshot(), start, zone, emptySet()).availability)
        assertEquals(HistoryAvailability.DEGRADED, FocusHistoryAggregator.summarize(HistorySnapshot(listOf(started()), start, true, true), start, zone, setOf("a")).availability)
    }
    @Test fun FH17_supersededCreditsUntilReplacement() {
        val superseded = event(HistoryEventType.SUPERSEDED, start + hour / 2).copy(by = "b")
        val result = summary(listOf(started(), superseded, started(start + hour / 2, "b"), complete(start + 3 * hour / 2, "b")))
        assertEquals(hour / 2, result.sessions.first().focusedMs)
        assertEquals(3 * hour / 2, result.days.last().focusedMs)
    }
    @Test fun FH18_clockBackwardsCreditsZeroAndOrphanIgnored() {
        assertEquals(0L, focused(listOf(started(), complete(start - 1))))
        assertTrue(summary(listOf(complete())).sessions.isEmpty())
    }
    @Test fun FH19_streakTodayOrYesterdayAndStopsAtHistoryStart() {
        val events = (-2L..0L).flatMap { day -> listOf(started(start + day * 24 * hour, "$day"), complete(start + day * 24 * hour + hour, "$day")) }
        assertEquals(3, summary(events).streak)
        assertEquals(2, summary(events.dropLast(2)).streak)
        assertEquals(1, summary(events, historyStart = start).streak)
    }

    @Test fun codecEscapesAllFlatValuesAndRejectsMalformedRecords() {
        val event = started().copy(name = "Deep \"study\"\n\\\t\b\u0000 💡")
        assertEquals(event.name, HistoryCodec.decode(HistoryCodec.encode(event)).name)
        val values = linkedMapOf<String, Any?>("s" to "a/b", "n" to Long.MIN_VALUE, "b" to true, "z" to null)
        assertEquals(values, FlatJson.decode(FlatJson.encode(values)))
        for (bad in listOf("{}", "{\"a\":1,}", "{\"a\":1,\"a\":2}", "{\"a\":01}", "{\"a\":1}garbage", "{\"a\":\"\\q\"}")) {
            assertThrows(Exception::class.java) { HistoryCodec.decode(bad) }
        }
    }
    @Test fun concurrentSyncedAppendsAndMultipleInstancesRoundTrip() = runTest {
        val dir = temporary.newFolder(); val a = store(dir = dir); val b = store(dir = dir)
        (1..80).map { id -> async { (if (id % 2 == 0) a else b).append(started(id = "$id")) } }.awaitAll().forEach { assertTrue(it) }
        val loaded = a.load()
        assertEquals(80, loaded.events.size)
        assertEquals(start, loaded.historyStart)
        assertTrue(File(dir, "events.v1.jsonl").readText().endsWith("\n"))
    }
    @Test fun unreadableStoreIsUnavailableAndDoesNotThrow() = runTest {
        val file = temporary.newFile(); val store = store(dir = file)
        assertFalse(store.append(started()))
        assertFalse(store.load().readable)
    }
    @Test fun allCorruptIsUnavailableAndNewWritesCanRecover() = runTest {
        val dir = temporary.newFolder(); val store = store(dir = dir); store.load()
        File(dir, "events.v1.jsonl").writeText("broken\n")
        val snapshot = store.load()
        assertEquals(HistoryAvailability.UNAVAILABLE, FocusHistoryAggregator.summarize(snapshot, start, zone, emptySet()).availability)
        assertTrue(store.append(started()))
        assertEquals(1, store.load().events.size)
    }
    @Test fun hardCapDropsOldestWholeSessionsAndDailyCompactionSurvivesRestart() = runTest {
        val dir = temporary.newFolder(); val store = store(dir = dir)
        store.load()
        val external = (1..6).flatMap { id -> listOf(
            started(start + id, "$id"),
            HistoryEvent(type = "future", session = "$id", at = start + id, tz = "UTC", reason = "x".repeat(1_048_576)),
        ) }
        File(dir, "events.v1.jsonl").bufferedWriter().use { writer -> external.forEach {
            writer.write(HistoryCodec.encode(it)); writer.write("\n")
        } }
        assertTrue(store.compact())
        assertTrue(File(dir, "events.v1.jsonl").length() <= FocusHistoryStore.HARD_CAP)
        val grouped = store.snapshots.value.events.groupBy { it.session }
        assertFalse(grouped.containsKey("1"))
        assertTrue(grouped.values.all { it.size == 2 })
        // Once under the soft cap a second process incarnation must not compact again today.
        store.clear(emptySet())
        val log = File(dir, "events.v1.jsonl")
        assertTrue(log.setLastModified(100))
        store(dir = dir).load()
        assertEquals(100L, log.lastModified())
    }
    @Test fun quarantineKeepsAtMostThreeAndMetadataCorruptionIsUnavailable() = runTest {
        val dir = temporary.newFolder(); val store = store(dir = dir); store.load()
        repeat(5) { index ->
            File(dir, "events.v1.jsonl").appendText("bad-$index\n")
            store.load()
            store.compact()
        }
        assertEquals(3, File(dir, "quarantine").listFiles()!!.size)
        File(dir, "metadata.v1.json").writeText("invalid metadata")
        val recovered = store(dir = dir).load()
        assertTrue(recovered.readable)
        assertTrue(recovered.degraded)
    }
    @Test fun bridgeDiffRestartIdempotenceAndObservedVsColdEnds() = runTest {
        val clock = MutableClock(start); val store = store(clock)
        val id = "session-timer-$start-uuid"
        val rule = HistoryRule(id, "Focus timer", "easy", start + hour)
        val bridge = HistoryReconciler(store, clock) { zone }
        bridge.observe(listOf(rule)); bridge.observe(listOf(rule))
        assertEquals(1, store.snapshots.value.events.size)
        assertEquals(start, store.snapshots.value.events.single().at)
        clock.value += hour / 2
        bridge.observe(emptyList())
        assertFalse(store.snapshots.value.events.last().inferred)
        assertEquals(hour / 2, FocusHistoryAggregator.sessions(store.snapshots.value.events, clock.value, emptySet()).single().focusedMs)
        HistoryReconciler(store, clock) { zone }.observe(emptyList())
        assertEquals(2, store.snapshots.value.events.size)
        val coldId = "session-cold"
        store.append(started(id = coldId))
        HistoryReconciler(store, clock) { zone }.observe(emptyList())
        assertTrue(store.snapshots.value.events.last().inferred)
        assertEquals(0L, FocusHistoryAggregator.sessions(store.snapshots.value.events, clock.value, emptySet()).first { it.id == coldId }.focusedMs)
    }
    @Test fun bridgeReadFailureDoesNotMeanMissingAndFirstSuccessfulSnapshotReconciles() = runTest {
        val clock = MutableClock(start); val store = store(clock)
        store.append(started(id = "session-existing"))
        var attempts = 0
        HistoryReconciler(store, clock) { zone }.collect(flow {
            if (attempts++ == 0) throw java.io.IOException("read failed")
            assertEquals(1, store.snapshots.value.events.size)
            emit(emptyList())
        })
        assertEquals(2, attempts)
        assertTrue(store.snapshots.value.events.last().inferred)
    }
    @Test fun bridgeReconcilesExpiredRuleAndLegacyObservationTime() = runTest {
        val clock = MutableClock(start); val store = store(clock)
        val bridge = HistoryReconciler(store, clock) { zone }
        bridge.observe(listOf(HistoryRule("session-legacy", "Old focus", "harder", start + hour)))
        assertEquals("legacy", store.snapshots.value.events.single().source)
        clock.value += 2 * hour
        bridge.observe(emptyList())
        assertEquals(start + hour, store.snapshots.value.events.last().at)
        assertEquals(HistoryEventType.COMPLETED, store.snapshots.value.events.last().knownType)
    }
    @Test fun runningAndOptionalMeasuredCountersStayTruthful() {
        val result = summary(listOf(started()), start + hour / 2, active = setOf("a"))
        assertTrue(result.days.last().inProgress)
        assertNull(result.sessions.single().breaks)
        assertNull(result.sessions.single().opensIntercepted)
        val intercepted = event(HistoryEventType.OPEN_INTERCEPTED, start + 1)
        assertEquals(1, summary(listOf(started(), intercepted, intercepted, complete())).sessions.single().opensIntercepted)
    }
    private class MutableClock(var value: Long) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(value)
    }
}
