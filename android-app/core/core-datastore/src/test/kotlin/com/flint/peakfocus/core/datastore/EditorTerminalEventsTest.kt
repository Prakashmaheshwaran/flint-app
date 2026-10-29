package com.flint.peakfocus.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.flint.peakfocus.blocking.engine.AnchoredInterval
import com.flint.peakfocus.core.model.*
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class EditorTerminalEventsTest {
    private class Disk(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        val values = MutableStateFlow(initial)
        var fail = false
        var beforeCommit: suspend () -> Unit = {}
        var changedWrites = 0
        private val lock = Mutex()
        override val data: Flow<Preferences> = values
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = lock.withLock {
            val next = transform(values.value)
            beforeCommit()
            if (fail) throw IOException("synthetic write failure")
            if (next != values.value) changedWrites++
            values.value = next
            next
        }
    }
    private val base = Instant.parse("2026-10-28T12:00:00Z").toEpochMilli()
    private fun at(ms: Long = 0, wall: Long = ms, boot: String = "boot") = AccessClockStamp(base + wall, 10_000 + ms, boot)
    private fun timer() = BlockRule(CONTRACT_TIMER_ID_PREFIX + "$base-fixture", "Timer",
        BlockTargets(apps = setOf(AppRef("app"))), expiresAtEpochMs = base + 300_000)
    private class Clock(var now: AccessClockStamp)
    private fun store(disk: Disk, clock: Clock) = DataStoreEditorCommitmentStore(disk,
        clockAtCommit = { clock.now }, defaultZone = { "UTC" })
    private suspend fun started(disk: Disk, clock: Clock): DataStoreEditorCommitmentStore = store(disk, clock).also {
        assertTrue(it.startTimer(timer(), clock.now, EditorSafety(true)))
    }
    private fun event(index: Int): EditorTerminalEvent {
        val id = CONTRACT_TIMER_ID_PREFIX + "seed-$index"
        return EditorTerminalEvent("editor:completed:$id", id, id, EditorTerminalKind.COMPLETED,
            at(-1), at(), index + 1L, false)
    }

    @Test fun `PC-17 PC-64 natural Timer end records exact logical endpoint separately from delayed observation once`() = runBlocking {
        val disk = Disk(); val clock = Clock(at()); val owner = started(disk, clock)
        clock.now = at(299_999); owner.reconcile(clock.now)
        assertTrue(owner.readTerminalEvents().isEmpty())
        clock.now = at(307_000); owner.reconcile(clock.now)
        val terminal = owner.readTerminalEvents().single()
        assertEquals("editor:completed:${timer().id}", terminal.id)
        assertEquals(timer().id, terminal.sessionId); assertEquals(timer().id, terminal.occurrenceId)
        assertEquals(EditorTerminalKind.COMPLETED, terminal.kind)
        assertEquals(at(300_000), terminal.logicalEnd)
        assertEquals(at(307_000), terminal.observedAt)
        assertEquals(1L, terminal.revision); assertFalse(terminal.timeUncertain)
        val snapshot = disk.values.value.asMap().toMap()
        val writes = disk.changedWrites
        repeat(5) { clock.now = at(308_000L + it); owner.reconcile(clock.now) }
        assertEquals(listOf(terminal), owner.readTerminalEvents())
        assertEquals(snapshot, disk.values.value.asMap()); assertEquals(writes, disk.changedWrites)
        val health = (owner.terminalDeliveryHealth.first { it is StorageState.Ready } as StorageState.Ready).value
        assertEquals(1, health.pendingCount); assertEquals(0, health.overflowCount)
        assertEquals(terminal.observedAt, health.oldestObservedAt)
    }

    @Test fun `PC-17 same-boot restart redelivers the same event and acknowledges only after durable sink success`() = runBlocking {
        val disk = Disk(); val clock = Clock(at()); val owner = started(disk, clock)
        clock.now = at(300_000); owner.reconcile(clock.now)
        val original = owner.readTerminalEvents().single()
        val reopened = store(disk, clock)
        assertEquals(listOf(original), reopened.readTerminalEvents())
        val durableHistory = mutableSetOf<String>()
        reopened.deliverTerminalEvents { event ->
            assertEquals(listOf(original), reopened.readTerminalEvents())
            durableHistory += event.id
            false // A sink that cannot confirm its append must leave delivery pending.
        }
        assertEquals(listOf(original), reopened.readTerminalEvents())
        reopened.deliverTerminalEvents { event -> durableHistory += event.id; true }
        assertEquals(setOf(original.id), durableHistory)
        assertTrue(reopened.readTerminalEvents().isEmpty())
        repeat(2) { reopened.reconcile(clock.now) }
        assertTrue(reopened.readTerminalEvents().isEmpty())
    }

    @Test fun `PC-70 concurrent expiry callbacks produce one durable terminal revision`() = runBlocking {
        val disk = Disk(); val clock = Clock(at()); val owner = started(disk, clock)
        clock.now = at(300_000)
        coroutineScope { List(8) { async { owner.reconcile(clock.now) } }.awaitAll() }
        assertEquals(1L, owner.readTerminalEvents().single().revision)
        assertEquals(1L, readEditorTerminals(disk.values.value).revision)
    }

    @Test fun `PC-70 unacknowledged delivery survives age and reboot without changing its proven endpoint`() = runBlocking {
        val disk = Disk(); val clock = Clock(at()); val owner = started(disk, clock)
        clock.now = at(300_000); owner.reconcile(clock.now)
        val terminal = owner.readTerminalEvents().single()
        clock.now = at(5, wall = 31 * 86_400_000L, boot = "reboot"); owner.reconcile(clock.now)
        assertEquals(listOf(terminal), store(disk, clock).readTerminalEvents())
    }

    @Test fun `PC-70 history committed before failed ack is replayed without a second completion identity`() = runBlocking {
        val disk = Disk(); val clock = Clock(at()); val owner = started(disk, clock)
        clock.now = at(300_000); owner.reconcile(clock.now)
        val history = mutableSetOf<String>()
        val id = owner.readTerminalEvents().single().id
        try {
            owner.deliverTerminalEvents { history += it.id; disk.fail = true; true }
            fail("failed ack reported success")
        } catch (_: IOException) { }
        disk.fail = false
        assertEquals(setOf(id), history); assertEquals(id, owner.readTerminalEvents().single().id)
        store(disk, clock).deliverTerminalEvents { history += it.id; true }
        assertEquals(setOf(id), history); assertTrue(owner.readTerminalEvents().isEmpty())
    }

    @Test fun `PC-70 sink exception or cancellation never acknowledges a pending terminal`() = runBlocking {
        val disk = Disk(); val clock = Clock(at()); val owner = started(disk, clock)
        clock.now = at(300_000); owner.reconcile(clock.now)
        val before = owner.readTerminalEvents()
        try { owner.deliverTerminalEvents { throw IOException("history write") }; fail("sink failure swallowed") }
        catch (_: IOException) { }
        assertEquals(before, owner.readTerminalEvents())
        try { owner.deliverTerminalEvents { throw CancellationException("cancelled append") }; fail("cancellation swallowed") }
        catch (_: CancellationException) { }
        assertEquals(before, owner.readTerminalEvents())
    }

    @Test fun `PC-17 early explicit exit and pass-style removal never become natural completion`() = runBlocking {
        for (explicit in listOf(true, false)) {
            val disk = Disk(); val clock = Clock(at()); val owner = started(disk, clock)
            clock.now = at(30_000)
            if (explicit) {
                val preview = owner.request(EditorChange("End", deleteIds = setOf(timer().id), timerExit = true), clock.now, EditorSafety(true)) as EditorCommitResult.Preview
                assertEquals(EditorCommitResult.Applied(), owner.confirm(preview.pending.id, clock.now, EditorSafety(true)))
            } else DataStoreBlockRulesStore(disk).delete(timer().id)
            clock.now = at(400_000); owner.reconcile(clock.now)
            assertTrue(owner.readTerminalEvents().isEmpty())
        }
    }

    @Test fun `PC-17 a disabled Timer cannot later manufacture natural completion`() = runBlocking {
        val disk = Disk(); val clock = Clock(at()); val owner = started(disk, clock)
        DataStoreBlockRulesStore(disk).setEnabled(timer().id, false)
        clock.now = at(300_000); owner.reconcile(clock.now)
        assertTrue(owner.readTerminalEvents().isEmpty())
    }

    @Test fun `PC-54 reboot across deadline omits unprovable completion`() = runBlocking {
        val disk = Disk(); val clock = Clock(at()); val owner = started(disk, clock)
        clock.now = at(5, wall = 900_000, boot = "reboot")
        owner.reconcile(clock.now)
        assertTrue(owner.readTerminalEvents().isEmpty())
        assertTrue(readEditorState(disk.values.value).causes.getValue(ruleCauseKey(timer().id)).timerTerminal!!.untrustedBoot)
    }

    @Test fun `PC-54 a later same-boot reanchor cannot turn an uncertain reboot into completed time`() = runBlocking {
        val disk = Disk(); val clock = Clock(at()); val owner = started(disk, clock)
        clock.now = at(5, wall = 270_000, boot = "reboot")
        val other = BlockRule("other", "Other", BlockTargets(apps = setOf(AppRef("other"))))
        assertTrue(owner.request(EditorChange("Other", upserts = listOf(other)), clock.now, EditorSafety(true)) is EditorCommitResult.Preview)
        assertEquals("reboot", readEditorState(disk.values.value).causes.getValue(ruleCauseKey(timer().id)).interval!!.anchor.bootSessionId)
        clock.now = at(30_005, wall = 300_000, boot = "reboot"); owner.reconcile(clock.now)
        assertTrue(owner.readTerminalEvents().isEmpty())
    }

    @Test fun `PC-54 same-boot wall discontinuity keeps exact elapsed endpoint and flags uncertain wall time`() = runBlocking {
        val disk = Disk(); val clock = Clock(at()); val owner = started(disk, clock)
        clock.now = at(300_000, wall = 2 * 86_400_000L); owner.reconcile(clock.now)
        val terminal = owner.readTerminalEvents().single()
        assertEquals(at(300_000), terminal.logicalEnd)
        assertEquals(clock.now, terminal.observedAt); assertTrue(terminal.timeUncertain)
    }

    @Test fun `PC-70 cold STARTED-only or already-expired rule without active anchored evidence produces no terminal`() = runBlocking {
        for (withExpiredRule in listOf(false, true)) {
            val initial = if (withExpiredRule) preferencesOf(PrefsKeys.BLOCK_RULES to PrefsCodec.encodeRules(listOf(timer()))) else emptyPreferences()
            val disk = Disk(initial); val clock = Clock(at(400_000)); val owner = store(disk, clock)
            repeat(2) { owner.reconcile(clock.now) }
            assertTrue(owner.readTerminalEvents().isEmpty())
        }
    }

    @Test fun `PC-70 terminal write failure retains original anchor and retries exactly once`() = runBlocking {
        val disk = Disk(); val clock = Clock(at()); val owner = started(disk, clock)
        val original = disk.values.value.asMap().toMap()
        disk.fail = true; clock.now = at(300_000)
        try { owner.reconcile(clock.now); fail("terminal write failure swallowed") } catch (_: IOException) { }
        assertEquals(original, disk.values.value.asMap()); assertTrue(owner.readTerminalEvents().isEmpty())
        disk.fail = false; owner.reconcile(clock.now); owner.reconcile(clock.now)
        assertEquals(1, owner.readTerminalEvents().size)
    }

    @Test fun `PC-70 cancelled terminal transaction leaves no partial cause marker or event`() = runBlocking {
        val disk = Disk(); val clock = Clock(at()); val owner = started(disk, clock)
        val original = disk.values.value.asMap().toMap(); val reached = CompletableDeferred<Unit>()
        disk.beforeCommit = { reached.complete(Unit); awaitCancellation() }
        clock.now = at(300_000)
        val job = launch { owner.reconcile(clock.now) }
        reached.await(); job.cancelAndJoin()
        assertEquals(original, disk.values.value.asMap())
        disk.beforeCommit = {}; owner.reconcile(clock.now)
        assertEquals(1, owner.readTerminalEvents().size)
    }

    @Test fun `PC-70 bounded pending overflow survives rule removal and drains with original first observation`() = runBlocking {
        val seed = (0 until MAX_PENDING_TERMINALS).map(::event).associateBy { it.id }
        val disk = Disk(preferencesOf(EDITOR_TERMINAL_SCHEMA to 1L,
            EDITOR_TERMINALS to EditorTerminalCodec.encode(EditorTerminalLedger(MAX_PENDING_TERMINALS.toLong(), seed))))
        val clock = Clock(at()); val owner = started(disk, clock)
        clock.now = at(300_000); owner.reconcile(clock.now)
        val overflow = (owner.terminalDeliveryHealth.first { it is StorageState.Ready } as StorageState.Ready).value
        assertEquals(MAX_PENDING_TERMINALS, overflow.pendingCount); assertEquals(1, overflow.overflowCount)
        DataStoreBlockRulesStore(disk).delete(timer().id)
        owner.reconcile(clock.now)
        assertNotNull(readEditorState(disk.values.value).causes[ruleCauseKey(timer().id)]?.timerTerminal?.pending)
        owner.ackTerminalEvents(setOf(seed.keys.first()))
        clock.now = at(307_000); owner.reconcile(clock.now)
        val delivered = owner.readTerminalEvents().single { it.sessionId == timer().id }
        assertEquals(at(300_000), delivered.logicalEnd); assertEquals(at(300_000), delivered.observedAt)
        val drained = (owner.terminalDeliveryHealth.first { it is StorageState.Ready } as StorageState.Ready).value
        assertEquals(MAX_PENDING_TERMINALS, drained.pendingCount); assertEquals(0, drained.overflowCount)
    }

    @Test fun `PC-70 acknowledged retention expires after thirty days without re-emitting the recorded cause`() = runBlocking {
        val disk = Disk(); val clock = Clock(at()); val owner = started(disk, clock)
        clock.now = at(300_000); owner.reconcile(clock.now)
        val id = owner.readTerminalEvents().single().id
        owner.ackTerminalEvents(setOf(id))
        assertTrue(id in readEditorTerminals(disk.values.value).acknowledged)
        clock.now = at(31 * 86_400_000L); owner.reconcile(clock.now)
        assertTrue(readEditorTerminals(disk.values.value).acknowledged.isEmpty())
        assertTrue(owner.readTerminalEvents().isEmpty())
    }

    @Test fun `PC-70 ack receipts retain a count bound without evicting pending deliveries`() = runBlocking {
        val pending = event(MAX_TERMINAL_ACKS)
        val acks = (0 until MAX_TERMINAL_ACKS).associate { "acked-$it" to base - MAX_TERMINAL_ACKS + it }
        val disk = Disk(preferencesOf(EDITOR_TERMINAL_SCHEMA to 1L, EDITOR_TERMINALS to EditorTerminalCodec.encode(
            EditorTerminalLedger(pending.revision, mapOf(pending.id to pending), acks))))
        val clock = Clock(at()); val owner = store(disk, clock)
        owner.ackTerminalEvents(setOf(pending.id))
        val ledger = readEditorTerminals(disk.values.value)
        assertEquals(MAX_TERMINAL_ACKS, ledger.acknowledged.size)
        assertFalse("acked-0" in ledger.acknowledged)
        assertTrue(pending.id in ledger.acknowledged); assertTrue(ledger.pending.isEmpty())
    }

    @Test fun `PC-71 unknown duplicate malformed and missing initialized outbox stays unavailable without blocking plan`() = runBlocking {
        val disk = Disk(); val clock = Clock(at()); val owner = started(disk, clock)
        clock.now = at(300_000); owner.reconcile(clock.now)
        val initial = disk.values.value
        val raw = initial[EDITOR_TERMINALS]!!
        val fixtures = listOf(
            initial.toMutablePreferences().also { it.remove(EDITOR_TERMINALS) },
            initial.toMutablePreferences().also { it[EDITOR_TERMINAL_SCHEMA] = 2L },
            initial.toMutablePreferences().also { it[EDITOR_TERMINALS] = raw.replace("T|1", "T|2") },
            initial.toMutablePreferences().also { it[EDITOR_TERMINALS] = raw + "\n" + raw.lineSequence().first { line -> line.startsWith("E|") } },
        )
        for (prefs in fixtures) {
            val corrupt = Disk(prefs); val reader = store(corrupt, clock)
            assertTrue(reader.terminalEvents.first { it !is StorageState.Loading } is StorageState.Unavailable)
            reader.reconcile(clock.now)
            assertEquals(prefs[EDITOR_TERMINALS], corrupt.values.value[EDITOR_TERMINALS])
            assertTrue(reader.state.first { it !is StorageState.Loading } is StorageState.Ready)
        }
    }
}
