package com.flint.peakfocus.core.history

import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FocusHistoryIntegrityTest {
    @get:Rule val temporary = TemporaryFolder()
    private val begin = 1_793_196_000_000L
    private val minute = 60_000L
    private fun clock(at: Long) = Clock.fixed(Instant.ofEpochMilli(at), ZoneOffset.UTC)
    private fun start(end: Long = begin + 60 * minute) = HistoryEvent(type = "started", session = "session-a",
        at = begin, tz = "UTC", plannedEnd = end, tier = "easy", source = "manual", name = "Focus")
    private fun focus(snapshot: HistorySnapshot, now: Long) =
        FocusHistoryAggregator.sessions(snapshot.events, now, setOf("session-a")).single().focusedMs

    @Test fun N1_malformedPendingClearNeverAuthorizesDestructiveRecovery() = runTest {
        for (field in listOf("", ",\"keepSessions\":null", ",\"keepSessions\":false",
            ",\"keepSessions\":\"session-a\"", ",\"keepSessions\":[\"session-a\",null]", ",\"keepSessions\":[\"\"]")) {
            val dir = temporary.newFolder()
            FocusHistoryStore(dir, clock(begin)).append(start())
            val original = File(dir, "events.v1.jsonl").readText()
            val bad = "{\"v\":1,\"createdAt\":$begin,\"pendingClear\":${begin + minute}$field}"
            File(dir, "metadata.v1.json").writeText(bad)
            val recovered = FocusHistoryStore(dir, clock(begin + minute)).load()
            assertTrue(recovered.degraded); assertEquals(1, recovered.events.size)
            assertEquals(original, File(dir, "events.v1.jsonl").readText())
            assertTrue(File(dir, "quarantine").listFiles()!!.any { it.readText() == bad })
        }
    }
    @Test fun FH21_N2_clearAndRecoveryAndAutomaticCompactionPreserveRunningPassTotal() = runTest {
        for (phase in listOf<HistoryClearPhase?>(null, HistoryClearPhase.INTENT_COMMITTED, HistoryClearPhase.LOG_REPLACED)) {
            val dir = temporary.newFolder(); val now = begin + 30 * minute
            val store = FocusHistoryStore(dir, clock(now))
            store.append(start())
            store.append(HistoryEvent(type = "pass_used", session = "pass-operation", at = begin + 10 * minute,
                tz = "UTC", sessions = listOf("session-a"), recoveryEnd = begin + 20 * minute))
            assertEquals(20 * minute, focus(store.load(), now))
            FocusHistoryStore(dir, clock(now), clearPhase = { if (it == phase) error("interrupted") }).clear(setOf("session-a"))
            val reopened = FocusHistoryStore(dir, clock(now))
            assertEquals(20 * minute, focus(reopened.load(), now))
            reopened.compact()
            assertEquals(20 * minute, focus(FocusHistoryStore(dir, clock(now)).load(), now))
        }
    }
    @Test fun FH21_N2_hardCapTrimsOldOwnerWithoutDroppingSharedPassOfRetainedOwner() = runTest {
        val dir = temporary.newFolder(); val now = begin + 30 * minute
        val store = FocusHistoryStore(dir, clock(now))
        store.append(start())
        store.append(HistoryEvent(type = "pass_used", session = "old-pass", at = begin + 10 * minute,
            tz = "UTC", sessions = listOf("session-a"), recoveryEnd = begin + 20 * minute))
        File(dir, "events.v1.jsonl").appendText("{\"v\":2,\"id\":\"large\",\"type\":\"future\",\"session\":\"older\",\"at\":${begin - 1},\"tz\":\"UTC\",\"payload\":\"${"x".repeat(FocusHistoryStore.HARD_CAP)}\"}\n")
        store.compact()
        assertEquals(20 * minute, focus(FocusHistoryStore(dir, clock(now)).load(), now))
        assertTrue(File(dir, "events.v1.jsonl").length() <= FocusHistoryStore.HARD_CAP)
    }
    @Test fun FH20_N3_lateRecoveredExtensionAndShorteningOrderPlanBeforeTerminalAcrossRestart() = runTest {
        for (newDuration in listOf(30L, 120L)) {
            val dir = temporary.newFolder(); val old = start()
            FocusHistoryStore(dir, clock(begin)).append(old)
            val deadline = begin + newDuration * minute; val observed = begin + 180 * minute
            val store = FocusHistoryStore(dir, clock(observed))
            HistoryReconciler(store, clock(observed)).observe(listOf(HistoryRule("session-a", "Focus", "easy", deadline)))
            val records = store.load().events
            assertEquals(listOf("started", "rescheduled", "completed"), records.map { it.type })
            assertEquals(deadline, records[1].at); assertEquals(observed, records[1].observedAt)
            assertTrue(records[1].inferred); assertEquals(observed, records[2].observedAt)
            val reopened = FocusHistoryStore(dir, clock(observed))
            HistoryReconciler(reopened, clock(observed)).observe(emptyList())
            val session = FocusHistoryAggregator.sessions(reopened.load().events, observed, emptySet()).single()
            assertEquals(deadline, session.plannedEnd); assertEquals(newDuration * minute, session.focusedMs)
            assertEquals(SessionOutcome.COMPLETED, session.outcome); assertEquals(3, reopened.load().events.size)
        }
    }
    @Test fun FH20_N4_completionUsesEffectiveDeadlineUnderLockAndRemainsIdempotent() = runTest {
        val dir = temporary.newFolder(); val now = begin + 180 * minute
        val store = FocusHistoryStore(dir, clock(now)); store.append(start())
        fun complete(at: Long) = HistoryEvent(type = "completed", session = "session-a", at = at, tz = "UTC")
        assertFalse(store.append(complete(begin + 30 * minute)))
        store.append(HistoryEvent(type = "rescheduled", session = "session-a", at = begin + 30 * minute,
            tz = "UTC", plannedEnd = begin + 120 * minute))
        assertFalse(store.append(complete(begin + 60 * minute)))
        assertFalse(store.append(complete(now + 1)))
        val due = complete(begin + 120 * minute)
        assertTrue(store.append(due)); assertTrue(store.append(due))
        assertEquals(3, store.load().events.size)
    }
    @Test fun N5_deepOpaqueFutureAndKnownEnvelopesSurviveRepairCompactionAndReopen() = runTest {
        for (depth in listOf(127, 128, 129, 130, 4096)) for ((version, type) in listOf(1 to "future", 2 to "started", 1 to "open_intercepted")) {
            val dir = temporary.newFolder(); val store = FocusHistoryStore(dir, clock(begin))
            store.append(start())
            val line = " {\"v\":$version,\"id\":\"deep\",\"type\":\"$type\",\"session\":\"session-a\",\"at\":$begin,\"tz\":\"UTC\",\"payload\":${"[".repeat(depth)}0${"]".repeat(depth)}} "
            File(dir, "events.v1.jsonl").appendText("$line\nbroken\n")
            assertTrue(store.compact())
            assertTrue(FocusHistoryStore(dir, clock(begin)).load().events.map(HistoryCodec::encode).contains(line))
        }
    }
    @Test fun N6_oversizedFileAndMetadataAreStreamQuarantinedAndRecoverDegraded() = runTest {
        val dir = temporary.newFolder(); val store = FocusHistoryStore(dir, clock(begin)); store.load()
        val log = File(dir, "events.v1.jsonl")
        log.outputStream().buffered().use { out -> repeat(1281) { out.write(ByteArray(8192) { 'x'.code.toByte() }) } }
        val length = log.length(); assertTrue(length > FocusHistoryStore.READ_CAP)
        val snapshot = store.load(); assertTrue(snapshot.degraded); assertTrue(snapshot.events.isEmpty())
        assertEquals(0, log.length()); assertTrue(File(dir, "quarantine").listFiles()!!.any { it.length() == length })
        File(dir, "metadata.v1.json").outputStream().use { it.write(ByteArray(128 * 1024)) }
        assertTrue(FocusHistoryStore(dir, clock(begin)).load().degraded)
    }
    @Test fun N6_shallowHugePayloadLoadsWithinConstrainedHeap() {
        val dir = temporary.newFolder()
        val classes = listOf(FocusHistoryStore::class.java, HistoryResourceProbe::class.java,
            kotlin.Unit::class.java, kotlinx.coroutines.Job::class.java)
        val classpath = classes.map { File(requireNotNull(it.protectionDomain).codeSource.location.toURI()).absolutePath }.distinct().joinToString(File.pathSeparator)
        val process = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").path,
            "-Xmx64m", "-cp", classpath, HistoryResourceProbe::class.java.name, dir.path).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(output, 0, process.waitFor()); assertTrue(output, output.contains("BOUNDED_LOAD_OK"))
    }
}

/** Separate JVM prevents the normal Gradle worker heap from masking allocation amplification. */
object HistoryResourceProbe {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val dir = File(args.single()); val now = 1_793_196_000_000L
        val store = FocusHistoryStore(dir, Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)); store.load()
        File(dir, "events.v1.jsonl").bufferedWriter().use { writer ->
            writer.write("{\"v\":2,\"id\":\"huge\",\"type\":\"future\",\"session\":\"session-huge\",\"at\":$now,\"tz\":\"UTC\",\"payload\":[")
            repeat(2_000_000) { if (it != 0) writer.write(","); writer.write("[]") }; writer.write("]}\n")
        }
        val snapshot = store.load()
        check(snapshot.readable); check(File(dir, "events.v1.jsonl").length() <= FocusHistoryStore.HARD_CAP)
        File(dir, "events.v1.jsonl").outputStream().buffered().use { out ->
            val chunk = ByteArray(8192) { 'x'.code.toByte() }; repeat(1281) { out.write(chunk) }
        }
        check(store.load().degraded)
        check(File(dir, "quarantine").listFiles()!!.any { it.length() > FocusHistoryStore.READ_CAP })
        println("BOUNDED_LOAD_OK")
    }
}
