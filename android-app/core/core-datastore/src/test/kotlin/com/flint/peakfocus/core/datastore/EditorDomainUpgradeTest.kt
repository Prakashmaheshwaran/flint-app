package com.flint.peakfocus.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.flint.peakfocus.blocking.engine.*
import com.flint.peakfocus.core.model.*
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class EditorDomainUpgradeTest {
    private val now = AccessClockStamp(1_793_181_600_000L, 10_000, "boot")
    private class Disk(initial: Preferences) : DataStore<Preferences> {
        val values = MutableStateFlow(initial)
        private val mutex = Mutex()
        var beforeCommit: (Preferences) -> Unit = {}
        var loseAck = false
        var changedWrites = 0
        override val data = values.asStateFlow()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = mutex.withLock {
            val next = transform(values.value)
            beforeCommit(next)
            if (next != values.value) changedWrites++
            values.value = next
            if (loseAck) throw CancellationException("simulated exit after durable upgrade")
            next
        }
    }

    private fun literalV1() = preferencesOf(
        EDITOR_VERSION_KEY to 1L,
        EDITOR_COMMITMENTS_KEY to "E|1\nN|1|false\nK|${now.wallEpochMs}|10000|boot",
        HARDER_WAIT_KEY to "H|1",
        PrefsKeys.TIME_LIMITS to "", // Always written by the old editor, even when unused.
        stringPreferencesKey("fictional_pin_fixture") to "retained fictional bytes",
        stringPreferencesKey("fictional_history_fixture") to "retained fictional history",
    )
    private fun editor(disk: Disk) = DataStoreEditorCommitmentStore(disk, defaultZone = { "UTC" })
    private suspend fun unavailable(disk: Disk) {
        val before = disk.values.value.asMap().toMap(); val writes = disk.changedWrites
        val store = editor(disk)
        assertEquals(StorageState.Unavailable(StorageFailure.CORRUPT), store.state.first { it !is StorageState.Loading })
        try { store.reconcile(now); fail("Missing referenced domain accepted") }
        catch (failure: StorageUnavailableException) { assertEquals(StorageFailure.CORRUPT, failure.reason) }
        assertEquals(before, disk.values.value.asMap()); assertEquals(writes, disk.changedWrites)
    }

    @Test fun `R2-N03 PC-70 literal unreferenced v1 absences normalize once without changing prior bytes or rights`() = runBlocking {
        val disk = Disk(literalV1()); val before = disk.values.value.asMap().toMap()
        val store = editor(disk)
        store.reconcile(now)
        assertEquals(2L, disk.values.value[EDITOR_VERSION_KEY])
        for (key in listOf(PrefsKeys.BLOCK_RULES, PrefsKeys.APP_GROUPS, PrefsKeys.TIME_LIMITS, PrefsKeys.OPEN_LIMITS))
            assertEquals("", disk.values.value[key])
        assertEquals("EASY", disk.values.value[PrefsKeys.DEFAULT_BREAK_LEVEL])
        for ((key, value) in before) if (key != EDITOR_VERSION_KEY) assertEquals(value, disk.values.value.asMap()[key])
        val after = disk.values.value.asMap().toMap(); val writes = disk.changedWrites
        repeat(2) { editor(disk).reconcile(now) }
        assertEquals(after, disk.values.value.asMap()); assertEquals(writes, disk.changedWrites)
        assertTrue(readEditorState(disk.values.value).causes.isEmpty())
        assertTrue(readHarderWait(disk.values.value).counts.isEmpty())
        assertNull(disk.values.value[OPEN_CREDIT_SCHEMA])
        assertTrue(store.readTerminalEvents().isEmpty())
    }

    @Test fun `R2-N03 PC-37 PC-71 missing v1 Time Limits is corrupt even with no references`() = runBlocking {
        unavailable(Disk(literalV1().toMutablePreferences().apply { remove(PrefsKeys.TIME_LIMITS) }))
    }

    @Test fun `R2-N03 PC-11 PC-71 active inactive and tombstone rule or Open causes forbid absence before upgrade`() = runBlocking {
        for (key in listOf("rule:work", "open:com.target")) {
            for (cause in listOf(EditorCause("inactive", AnchoredInterval(now, 0, now.wallEpochMs), "UTC"),
                EditorCause("old", AnchoredInterval(now, 60_000, now.wallEpochMs + 60_000), "UTC"),
                EditorCause("old", AnchoredInterval(now, 0, now.wallEpochMs), "UTC"))) {
                val initial = literalV1().toMutablePreferences().apply {
                    this[EDITOR_COMMITMENTS_KEY] = EditorCommitmentCodec.encode(EditorCommitmentState(
                        causes = mapOf(key to cause), timeline = now))
                }
                unavailable(Disk(initial))
            }
        }
    }

    @Test fun `R2-N03 PC-71 pending and queued editor ownership is checked before applying or pruning`() = runBlocking {
        val change = EditorChange("Delete", deleteIds = setOf("work"))
        val expected = EditorExpected(emptyList(), null, null, mapOf("rule:work" to "old"))
        val pending = PendingEditorChange(1, change, expected, CommitmentPolicy.Outcome.ALLOW)
        val queued = QueuedEditorChange(1, change, expected, mapOf("rule:work" to AnchoredInterval(now, 0, now.wallEpochMs)))
        for (state in listOf(EditorCommitmentState(nextId = 2, pending = pending, timeline = now),
            EditorCommitmentState(nextId = 2, queued = listOf(queued), timeline = now))) {
            unavailable(Disk(literalV1().toMutablePreferences().apply {
                this[EDITOR_COMMITMENTS_KEY] = EditorCommitmentCodec.encode(state)
            }))
        }
    }

    @Test fun `R2-N03 PC-18 PC-71 Harder count or pending wait keeps its referenced domain unavailable`() = runBlocking {
        for (key in listOf("rule:work", "open:com.target", "group:saved")) {
            val occurrence = HarderWaitPolicy.Occurrence(key, "old")
            val wait = HarderWaitPolicy.PendingWait(HarderWaitPolicy.Operation(HarderWaitPolicy.OperationKind.DELETE,
                mapOf(occurrence to "revision")), now, 60_000)
            for (ledger in listOf(HarderWaitPolicy.Ledger(counts = mapOf(occurrence to 3)),
                HarderWaitPolicy.Ledger(pending = wait))) {
                unavailable(Disk(literalV1().toMutablePreferences().apply { this[HARDER_WAIT_KEY] = HarderWaitCodec.encode(ledger) }))
            }
        }
    }

    @Test fun `R2-N03 PC-37 PC-71 surviving receipts legacy counts and credit ledger rows forbid a fresh Open domain`() = runBlocking {
        val receipt = PersistedExemption("old", "com.target", "legacy-open", ExemptionKind.OPEN_LIMIT,
            now.wallEpochMs + 60_000, "boot")
        unavailable(Disk(literalV1().toMutablePreferences().apply {
            this[PrefsKeys.FOCUS_LEDGER] = FocusLedgerCodec.encode(FocusLedgerCodec.Ledger(exemptions = mapOf("old" to receipt)))
        }))
        unavailable(Disk(literalV1().toMutablePreferences().apply { this[PrefsKeys.OPEN_COUNTS] = "com.target~2" }))
        val disk = Disk(literalV1().toMutablePreferences().apply {
            this[PrefsKeys.OPEN_LIMITS] = ""
            this[PrefsKeys.OPEN_COUNTS] = "com.target~2"
            this[PrefsKeys.OPEN_COUNTS_DAY] = OpenCreditLedgerPolicy.epochDay(now.wallEpochMs, "UTC")
        })
        disk.edit { prepareOpenCredits(it, now, "UTC") }
        disk.values.value = disk.values.value.toMutablePreferences().apply {
            remove(PrefsKeys.OPEN_LIMITS); remove(PrefsKeys.OPEN_COUNTS)
        }
        unavailable(disk)
    }

    @Test fun `R2-N03 PC-71 opaque surviving recovery cannot prove optional domains unused`() = runBlocking {
        unavailable(Disk(literalV1().toMutablePreferences().apply {
            this[stringPreferencesKey("protection_pass_outbox_v1")] = "opaque surviving recovery bytes"
        }))
    }

    @Test fun `R2-N03 PC-70 crash or write failure after upgrade transformation keeps all original bytes and marker`() = runBlocking {
        for (crash in listOf(false, true)) {
            val disk = Disk(literalV1())
            val before = disk.values.value.asMap().toMap()
            disk.beforeCommit = { next ->
                assertEquals(2L, next[EDITOR_VERSION_KEY])
                assertEquals("", next[PrefsKeys.APP_GROUPS])
                if (crash) throw CancellationException("simulated exit before durable upgrade")
                else throw IOException("simulated disk failure before durable upgrade")
            }
            try { editor(disk).reconcile(now); fail("Failure not injected") }
            catch (failure: Exception) { assertTrue(failure is IOException || failure is CancellationException) }
            assertEquals(before, disk.values.value.asMap()); assertEquals(0, disk.changedWrites)
            disk.beforeCommit = {}
            repeat(2) { editor(disk).reconcile(now) }
            assertEquals(2L, disk.values.value[EDITOR_VERSION_KEY]); assertEquals(1, disk.changedWrites)
        }
    }

    @Test fun `R2-N03 PC-70 lost acknowledgement reopens the same complete upgrade without extra writes`() = runBlocking {
        val disk = Disk(literalV1()).apply { loseAck = true }
        try { editor(disk).reconcile(now); fail("Lost acknowledgement not injected") }
        catch (_: CancellationException) { }
        val committed = disk.values.value.asMap().toMap(); val writes = disk.changedWrites
        disk.loseAck = false
        repeat(2) { editor(disk).reconcile(now) }
        assertEquals(2L, disk.values.value[EDITOR_VERSION_KEY])
        assertEquals(committed, disk.values.value.asMap()); assertEquals(writes, disk.changedWrites)
    }
}
