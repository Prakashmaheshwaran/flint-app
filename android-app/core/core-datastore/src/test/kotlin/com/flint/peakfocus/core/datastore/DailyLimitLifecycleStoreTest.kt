package com.flint.peakfocus.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import com.flint.peakfocus.blocking.engine.CommitmentPolicy
import com.flint.peakfocus.blocking.engine.OpenCreditLedgerPolicy
import com.flint.peakfocus.blocking.engine.OpenCreditPolicy
import com.flint.peakfocus.core.model.AccessClockStamp
import com.flint.peakfocus.core.model.BreakLevel
import com.flint.peakfocus.core.model.OpenLimit
import com.flint.peakfocus.core.model.StorageFailure
import com.flint.peakfocus.core.model.TimeLimit
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

/** Existing serialized delete/recreate behavior that the enabled-state migration must retain. */
class DailyLimitLifecycleStoreTest {
    private class Disk : DataStore<Preferences> {
        val values = MutableStateFlow(emptyPreferences())
        private val lock = Mutex()
        override val data = values.asStateFlow()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = lock.withLock {
            transform(values.value).also { values.value = it }
        }
    }

    private val noon = Instant.parse("2026-10-28T12:00:00Z").toEpochMilli()
    private fun at(elapsed: Long = 0, wall: Long = elapsed) = AccessClockStamp(noon + wall, 1_000 + elapsed, "boot")
    private val safe = EditorSafety(true)
    private val remove = EditorChange("Remove limits", limits = EditorLimitsChange("app", null, null))
    private fun add(tier: BreakLevel = BreakLevel.EASY, includeTime: Boolean = false) = EditorChange("Save limits",
        limits = EditorLimitsChange("app", if (includeTime) TimeLimit("app", 30, tier) else null, OpenLimit("app", 5, tier)))
    private fun editor(disk: Disk) = DataStoreEditorCommitmentStore(disk, defaultZone = { "UTC" })
    private suspend fun apply(store: EditorCommitmentStore, change: EditorChange, now: AccessClockStamp = at()): EditorCommitResult {
        val preview = store.request(change, now, safe) as EditorCommitResult.Preview
        return store.confirm(preview.pending.id, now, safe)
    }
    private suspend fun spent(disk: Disk, tier: BreakLevel = BreakLevel.EASY): DataStoreEditorCommitmentStore {
        val store = editor(disk)
        store.reconcile(at()) // A new commitment, not an imported legacy-rights occurrence.
        assertEquals(EditorCommitResult.Applied(), apply(store, add(tier)))
        disk.edit { prefs ->
            val ledger = prepareOpenCredits(prefs, at(), "UTC")
            val credit = OpenCreditLedgerPolicy.acceptOpenCredit(ledger, "app", "operation-1", listOf(5), emptyList(), false, at())
            assertTrue(credit.acceptance is OpenCreditPolicy.Acceptance.Accepted)
            prefs.putOpenCredits(credit.next)
        }
        return store
    }

    @Test fun `PC_36 Easy delete and recreate keeps used credits live grant and occurrence`() = runBlocking {
        val disk = Disk(); val store = spent(disk)
        val before = readOpenCredits(disk.values.value)
        val cause = readEditorState(disk.values.value).causes.getValue(openCauseKey("app"))
        assertEquals(EditorCommitResult.Applied(), apply(store, remove, at(1_000)))
        assertTrue(disk.values.value.editorOpens().isEmpty())
        assertEquals(EditorCommitResult.Applied(), apply(editor(disk), add(), at(1_000)))
        val after = readOpenCredits(disk.values.value)
        assertEquals(before.periodId, after.periodId)
        // Real mutations checkpoint elapsed progress; the balance, identity and receipts stay.
        assertEquals(before.counters.mapValues { it.value.copy(reset = after.reset) }, after.counters)
        assertEquals(12 * 3_600_000L - 1_000, OpenCreditPolicy().remainingToResetMs(after.reset, at(1_000)))
        assertEquals(before.active, after.active)
        assertEquals(4, OpenCreditLedgerPolicy.remainingCredits(after, "app", listOf(5)))
        assertEquals(cause.occurrenceId, readEditorState(disk.values.value).causes.getValue(openCauseKey("app")).occurrenceId)
    }

    @Test fun `PC_36 Harder delete and recreate preserves n for both daily kinds`() = runBlocking {
        val disk = Disk(); val store = editor(disk)
        store.reconcile(at())
        assertEquals(EditorCommitResult.Applied(), apply(store, add(BreakLevel.HARDER, includeTime = true)))
        val original = readEditorState(disk.values.value)
        val occurrences = listOf(timeCauseKey("app"), openCauseKey("app")).map { editorOccurrence(it, original.causes.getValue(it)) }
        val first = store.request(remove, at(), safe) as EditorCommitResult.Waiting
        assertEquals(60_000L, first.remainingMs)
        assertEquals(EditorCommitResult.Applied(), store.confirm(first.pending.id, at(60_000), safe))
        assertTrue(store.confirm(first.pending.id, at(60_000), safe) is EditorCommitResult.Rejected)
        assertEquals(EditorCommitResult.Applied(), apply(editor(disk), add(BreakLevel.HARDER, includeTime = true), at(60_000)))
        occurrences.forEach { assertEquals(1, readHarderWait(disk.values.value).counts[it]) }
        val next = editor(disk).request(remove, at(60_000), safe) as EditorCommitResult.Waiting
        assertEquals(300_000L, next.remainingMs)
        assertEquals(occurrences.toSet(), next.pending.wait!!.causes.keys)
    }

    @Test fun `PC_36 PC_54 PC_55 clock jumps while deleted cannot refill recreated Open limit`() = runBlocking {
        for (jump in listOf(-2 * 86_400_000L, 2 * 86_400_000L)) {
            val disk = Disk(); val store = spent(disk)
            val before = readOpenCredits(disk.values.value)
            assertEquals(EditorCommitResult.Applied(), apply(store, remove, at(1_000)))
            assertEquals(EditorCommitResult.Applied(), apply(editor(disk), add(), at(2_000, jump)))
            val after = readOpenCredits(disk.values.value)
            assertEquals(before.periodId, after.periodId)
            assertEquals(before.counters.mapValues { it.value.copy(reset = after.reset) }, after.counters)
            assertEquals(12 * 3_600_000L - 2_000, OpenCreditPolicy().remainingToResetMs(after.reset, at(2_000, jump)))
        }
    }

    @Test fun `PC_37 deleted template cannot hide missing initialized counts during recreation`() = runBlocking {
        val disk = Disk(); val store = spent(disk)
        assertEquals(EditorCommitResult.Applied(), apply(store, remove, at(1_000)))
        disk.values.value = disk.values.value.toMutablePreferences().apply { remove(OPEN_CREDIT_COUNTS) }
        val corrupt = disk.values.value.asMap().toMap()
        try {
            editor(disk).request(add(), at(2_000), safe)
            fail("Recreation accepted missing initialized accounting")
        } catch (failure: StorageUnavailableException) {
            assertEquals(StorageFailure.CORRUPT, failure.reason)
        }
        assertEquals(corrupt, disk.values.value.asMap())
    }

    @Test fun `PC_16 active credit does not let Hardcore delete before trusted reset`() = runBlocking {
        val disk = Disk(); val store = spent(disk, BreakLevel.HARDCORE)
        val preview = store.request(remove, at(1_000), safe) as EditorCommitResult.Preview
        assertEquals(CommitmentPolicy.Outcome.DEFER, preview.pending.outcome)
        assertEquals(EditorCommitResult.Applied(queued = true), store.confirm(preview.pending.id, at(1_000), safe))
        assertEquals(1, disk.values.value.editorOpens().size)
        editor(disk).reconcile(at(300_000)) // Credit expiry is not a daily-commitment end.
        assertEquals(1, disk.values.value.editorOpens().size)
        assertEquals(1, readOpenCredits(disk.values.value).counters.getValue("app").used)
        assertEquals(1, readEditorState(disk.values.value).queued.size)
        editor(disk).reconcile(at(12 * 3_600_000L))
        assertTrue(disk.values.value.editorOpens().isEmpty())
        assertTrue(readEditorState(disk.values.value).queued.isEmpty())
    }
}
