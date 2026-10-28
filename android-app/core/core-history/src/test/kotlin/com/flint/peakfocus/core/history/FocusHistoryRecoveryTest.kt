package com.flint.peakfocus.core.history

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FocusHistoryRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val begin = Instant.parse("2026-10-28T14:00:00Z").toEpochMilli()
    private val hour = 3_600_000L
    private val zone = ZoneId.of("America/New_York")
    private fun started(id: String = "session-a", at: Long = begin, end: Long? = begin + hour) =
        HistoryEvent(type = "started", session = id, at = at, tz = zone.id, plannedEnd = end,
            tier = "easy", source = "manual", name = "Focus")
    private class MutableClock(var value: Long) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(value)
    }
    @Test fun B1_metadataCorruptionSalvagesEventsAndAppendAndClearRecoverAcrossRestarts() = runTest {
        for (bad in listOf("truncated", "{\"v\":1,\"createdAt\":\"invalid\"}", "{\"v\":1,\"createdAt\":1,\"clearedAt\":false}")) {
            val dir = temporary.newFolder(); val clock = MutableClock(begin)
            val store = FocusHistoryStore(dir, clock)
            store.append(started())
            File(dir, "metadata.v1.json").writeText(bad)
            clock.value += 1000
            val recovered = FocusHistoryStore(dir, clock).load()
            assertTrue(recovered.degraded); assertEquals(1, recovered.events.size)
            assertEquals(clock.value, recovered.historyStart)
            assertTrue(File(dir, "quarantine").listFiles()!!.any { it.readText() == bad })
            assertTrue(FocusHistoryStore(dir, clock).append(started("session-b")))
            assertTrue(FocusHistoryStore(dir, clock).clear(emptySet()))
            val cleared = FocusHistoryStore(dir, clock).load()
            assertTrue(cleared.events.isEmpty()); assertFalse(cleared.degraded)
        }
    }
    @Test fun B2_futureGeneralJsonSurvivesRepairCompactionClearAndReopenByteForByte() = runTest {
        val dir = temporary.newFolder(); val clock = MutableClock(begin); val store = FocusHistoryStore(dir, clock)
        store.append(started())
        val lines = listOf(
            """ {"v":2,"id":"future","type":"started","session":"session-a","at":$begin,"tz":"UTC","details":{"flag":true,"array":[1.25,-2e-3,null,{"escaped":"\u2600\n\"quote\""}]}} """,
            "{\"v\":1,\"id\":\"unknown\",\"type\":\"future_event\",\"session\":\"session-a\",\"at\":$begin,\"tz\":\"UTC\",\"values\":[[],{},1E20,9223372036854775808]}",
        )
        lines.forEach { assertTrue(store.append(HistoryCodec.decode(it))) }
        File(dir, "events.v1.jsonl").appendText("broken\n{\"incomplete\":")
        assertTrue(store.append(HistoryEvent(type = "pass_used", session = "session-a", at = begin, tz = "UTC")))
        assertTrue(store.compact()); assertTrue(store.clear(setOf("session-a")))
        val reopened = FocusHistoryStore(dir, clock).load()
        lines.forEach { line -> assertTrue(reopened.events.map(HistoryCodec::encode).contains(line)) }
        assertEquals(4, reopened.events.size)
    }
    @Test fun B3_clearRecoversCoherentlyAfterEveryCommitPhase() = runTest {
        for (phase in HistoryClearPhase.entries) {
            val dir = temporary.newFolder(); val clock = MutableClock(begin)
            val store = FocusHistoryStore(dir, clock)
            store.append(started("session-old")); store.append(started("session-running"))
            clock.value += 1000
            val interrupted = FocusHistoryStore(dir, clock, clearPhase = { if (it == phase) throw IOException("interrupted $phase") })
            assertFalse(interrupted.clear(setOf("session-running")))
            val reopened = FocusHistoryStore(dir, clock).load()
            if (phase == HistoryClearPhase.BEFORE_INTENT) {
                assertEquals(2, reopened.events.size); assertEquals(begin, reopened.historyStart)
            } else {
                assertEquals(setOf("session-running"), reopened.events.map { it.session }.toSet())
                assertEquals(clock.value, reopened.historyStart)
            }
            clock.value = begin + hour
            assertTrue(FocusHistoryStore(dir, clock).append(HistoryEvent(type = "completed", session = "session-running", at = begin + hour, tz = "UTC")))
            assertFalse(FlatJson.decode(File(dir, "metadata.v1.json").readText())["pendingClear"] != null)
        }
    }
    @Test fun S4_incompleteQuarantineWithCorrectDigestIsReplacedBeforeRepair() = runTest {
        val dir = temporary.newFolder(); val clock = MutableClock(begin); val store = FocusHistoryStore(dir, clock)
        store.append(started())
        val log = File(dir, "events.v1.jsonl"); log.appendText("corrupt\n")
        val bytes = log.readBytes()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val quarantine = File(dir, "quarantine").apply { mkdirs() }
        val interrupted = File(quarantine, "$begin-$digest.jsonl").apply { writeText("partial") }
        File(quarantine, "leftover.jsonl.tmp").writeText("partial temp")
        store.load(); store.append(HistoryEvent(type = "pass_used", session = "session-a", at = begin, tz = "UTC"))
        assertArrayEquals(bytes, interrupted.readBytes())
        assertEquals(1, quarantine.listFiles()!!.count { it.extension == "jsonl" })
    }
    @Test fun S5_expiredUnrecordedNativeAndLegacyRulesDoNotManufactureSessions() = runTest {
        val clock = MutableClock(begin); val store = FocusHistoryStore(temporary.newFolder(), clock)
        val native = "session-timer-${begin - hour}-uuid"
        val bridge = HistoryReconciler(store, clock)
        bridge.observe(listOf(HistoryRule(native, "Focus timer", "easy", begin - 1), HistoryRule("session-legacy", "Legacy", "easy", begin - 1)))
        bridge.observe(emptyList())
        assertTrue(store.snapshots.value.events.isEmpty())
        store.append(started(native, begin - hour, begin - 1))
        HistoryReconciler(store, clock).observe(emptyList())
        assertEquals("completed", store.snapshots.value.events.last().type)
    }
    @Test fun FH20_deadlineExtensionAndRestartDoNotCompleteAtOldDeadline() = runTest {
        val clock = MutableClock(begin); val store = FocusHistoryStore(temporary.newFolder(), clock)
        val rule = HistoryRule("session-timer-$begin-uuid", "Focus timer", "easy", begin + hour)
        var bridge = HistoryReconciler(store, clock)
        bridge.observe(listOf(rule)); clock.value += hour / 2
        val extended = rule.copy(plannedEnd = begin + 2 * hour)
        bridge.observe(listOf(extended)); clock.value = begin + hour + 1
        bridge = HistoryReconciler(store, clock); bridge.observe(listOf(extended))
        assertEquals(listOf("started", "rescheduled"), store.snapshots.value.events.map { it.type })
        assertEquals(SessionOutcome.RUNNING, FocusHistoryAggregator.sessions(store.snapshots.value.events, clock.value, setOf(rule.id)).single().outcome)
        clock.value = begin + 2 * hour
        bridge.observe(emptyList())
        assertEquals(2 * hour, FocusHistoryAggregator.sessions(store.snapshots.value.events, clock.value, emptySet()).single().focusedMs)
    }
    @Test fun FH20_shorteningOpenEndNameTierAndDisableEditsUseEffectivePlan() = runTest {
        for (newEnd in listOf(begin + hour / 2, null)) {
            val clock = MutableClock(begin); val store = FocusHistoryStore(temporary.newFolder(), clock)
            val rule = HistoryRule("session-a", "Focus", "easy", begin + hour)
            val bridge = HistoryReconciler(store, clock); bridge.observe(listOf(rule))
            clock.value += 60_000
            bridge.observe(listOf(rule.copy(name = "Rename", tier = "harder")))
            assertEquals(1, store.snapshots.value.events.size)
            bridge.observe(listOf(rule.copy(plannedEnd = newEnd)))
            assertEquals("rescheduled", store.snapshots.value.events.last().type)
            if (newEnd != null) {
                clock.value = newEnd; bridge.observe(emptyList())
                assertEquals(hour / 2, FocusHistoryAggregator.sessions(store.snapshots.value.events, clock.value, emptySet()).single().focusedMs)
            } else {
                clock.value = begin + 2 * hour; bridge.observe(listOf(rule.copy(plannedEnd = null)))
                assertEquals(2, store.snapshots.value.events.size)
                bridge.observe(emptyList()) // enabled -> disabled is projected as disappearance
                assertEquals("ended_early", store.snapshots.value.events.last().type)
                assertEquals(2 * hour, FocusHistoryAggregator.sessions(store.snapshots.value.events, clock.value, emptySet()).single().focusedMs)
            }
        }
    }
    @Test fun S3_laterObservedBreakEndWinsAndOverlappingBreaksUnion() {
        val events = listOf(started(),
            HistoryEvent(type = "break_started", session = "session-a", at = begin + 10 * 60_000, tz = "UTC", breakEnd = begin + 30 * 60_000),
            HistoryEvent(type = "break_started", session = "session-a", at = begin + 20 * 60_000, tz = "UTC", breakEnd = begin + 35 * 60_000),
            HistoryEvent(type = "break_ended", session = "session-a", at = begin + 40 * 60_000, tz = "UTC"),
            HistoryEvent(type = "completed", session = "session-a", at = begin + hour, tz = "UTC"))
        assertEquals(30 * 60_000L, FocusHistoryAggregator.sessions(events, begin + hour, emptySet()).single().focusedMs)
    }
    @Test fun FH08_wholeDstDaysAndAdjacentMidnightsSplitCorrectly() {
        for ((day, hours) in listOf("2026-03-08" to 23L, "2026-11-01" to 25L)) {
            val date = java.time.LocalDate.parse(day)
            val a = date.atStartOfDay(zone).toInstant().toEpochMilli() - hour / 2
            val b = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() + hour / 2
            val events = listOf(started(at = a, end = b), HistoryEvent(type = "completed", session = "session-a", at = b, tz = zone.id))
            val summary = FocusHistoryAggregator.summarize(HistorySnapshot(events, a - hour, true), b, zone, emptySet())
            assertEquals(hours * hour, summary.days.first { it.date == date }.focusedMs)
            assertEquals(hour / 2, summary.days.first { it.date == date.minusDays(1) }.focusedMs)
            assertEquals(hour / 2, summary.days.first { it.date == date.plusDays(1) }.focusedMs)
        }
    }
    @Test fun readErrorAndSuppressedEmissionAfterSuccessDoNotInferDisappearance() = runTest {
        val clock = MutableClock(begin); val store = FocusHistoryStore(temporary.newFolder(), clock)
        val rule = HistoryRule("session-a", "Focus", "easy", begin + hour)
        val bridge = HistoryReconciler(store, clock)
        var attempt = 0
        bridge.collect(flow {
            if (attempt++ == 0) { emit(listOf(rule)); throw IOException("read failed") }
            assertEquals(1, store.snapshots.value.events.size)
            // A suppressed emission is no call to emit; the next successful snapshot retains A.
            emit(listOf(rule))
        })
        assertEquals(1, store.snapshots.value.events.size)
        HistoryReconciler(store, clock).observe(listOf(rule))
        assertEquals(1, store.snapshots.value.events.size)
    }
    @Test fun FH21_globalPassCoverageGapsAndAccessEventsHaveDistinctEffects() {
        val pass = HistoryEvent(type = "pass_used", session = "pass-operation", at = begin + 10 * 60_000,
            tz = zone.id, sessions = listOf("session-a", "session-b"), recoveryEnd = begin + 20 * 60_000,
            originRoute = "app_ui", observedAt = begin + 10 * 60_000 + 100, grant = "pass-operation")
        val decoded = HistoryCodec.decode(HistoryCodec.encode(pass))
        assertEquals(pass.sessions, decoded.sessions); assertEquals(pass.recoveryEnd, decoded.recoveryEnd)
        assertEquals(pass.originRoute, decoded.originRoute); assertEquals(pass.observedAt, decoded.observedAt)
        val events = listOf(started(), started("session-b"), pass,
            HistoryEvent(type = "protection_degraded", session = "session-a", at = begin + 30 * 60_000, tz = zone.id, reason = "permission_lost"),
            HistoryEvent(type = "protection_restored", session = "session-a", at = begin + 40 * 60_000, tz = zone.id),
            HistoryEvent(type = "access_started", session = "credit-operation", at = begin, tz = zone.id),
            HistoryEvent(type = "access_ended", session = "credit-operation", at = begin + hour, tz = zone.id)) +
            listOf("session-a", "session-b").map { HistoryEvent(type = "completed", session = it, at = begin + hour, tz = zone.id) }
        val sessions = FocusHistoryAggregator.sessions(events, begin + hour, emptySet())
        assertEquals(2, sessions.size)
        assertEquals(40 * 60_000L, sessions.first { it.id == "session-a" }.focusedMs)
        assertEquals(50 * 60_000L, sessions.first { it.id == "session-b" }.focusedMs)
        val openGap = events.filter { it.knownType != HistoryEventType.PROTECTION_RESTORED }
        assertEquals(20 * 60_000L, FocusHistoryAggregator.sessions(openGap, begin + hour, emptySet()).first { it.id == "session-a" }.focusedMs)
    }
    @Test fun completedIsRejectedBeforeDeadlineAndAcceptedWhenDue() = runTest {
        val clock = MutableClock(begin); val store = FocusHistoryStore(temporary.newFolder(), clock)
        store.append(started())
        val complete = HistoryEvent(type = "completed", session = "session-a", at = begin + hour, tz = zone.id)
        assertFalse(store.append(complete))
        assertEquals(1, store.snapshots.value.events.size)
        clock.value += hour
        assertTrue(store.append(complete))
    }
}
