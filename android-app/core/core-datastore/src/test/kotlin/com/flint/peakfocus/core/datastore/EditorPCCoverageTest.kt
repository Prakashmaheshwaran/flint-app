package com.flint.peakfocus.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.flint.peakfocus.core.model.*
import java.time.Instant
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

/** Acceptance gaps exercised through real stores; no new enforcement behavior or device claim. */
class EditorPCCoverageTest {
    private class Disk : DataStore<Preferences> {
        private val values = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override val data = values.asStateFlow()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = mutex.withLock {
            transform(values.value).also { values.value = it }
        }
    }

    private val now = AccessClockStamp(Instant.parse("2026-10-28T10:00:00Z").toEpochMilli(), 10_000, "boot")
    private suspend fun snapshot(editor: EditorCommitmentStore) =
        (editor.state.first { it !is StorageState.Loading } as StorageState.Ready).value

    private suspend fun groupSnapshot(delete: Boolean) {
        for (tier in BreakLevel.entries) {
            val disk = Disk()
            val editor = DataStoreEditorCommitmentStore(disk, defaultZone = { "UTC" })
            editor.reconcile(now)
            val groups = DataStoreGroupsStore(disk)
            val original = AppGroup("saved", "Saved selection", linkedSetOf(AppRef("com.old", "Old")),
                linkedSetOf(DomainRef("old.example")))
            groups.upsert(original)
            val selected = groups.groups.first().single()
            val timer = BlockRule(CONTRACT_TIMER_ID_PREFIX + "${now.wallEpochMs}-group", "Timer",
                BlockTargets(selected.apps, selected.domains), breakLevel = tier,
                expiresAtEpochMs = now.wallEpochMs + 300_000)
            assertTrue(editor.startTimer(timer, now, EditorSafety(true)))
            val before = snapshot(editor).causes.getValue(ruleCauseKey(timer.id))
            if (delete) groups.delete(original.id)
            else groups.upsert(original.copy(name = "Changed", apps = setOf(AppRef("com.new")),
                domains = setOf(DomainRef("new.example"))))
            val later = now.copy(wallEpochMs = now.wallEpochMs + 1_000, elapsedRealtimeMs = now.elapsedRealtimeMs + 1_000)
            val reopened = DataStoreEditorCommitmentStore(disk, defaultZone = { "UTC" })
            reopened.reconcile(later)
            assertEquals(listOf(timer), DataStoreBlockRulesStore(disk).rules.first())
            val retained = snapshot(reopened).causes.getValue(ruleCauseKey(timer.id))
            assertEquals(before.occurrenceId, retained.occurrenceId)
            assertEquals(before.legacyRights, retained.legacyRights)
            assertEquals(299_000L, retained.interval!!.remainingMs(later))
            if (delete) assertTrue(groups.groups.first().isEmpty())
            else assertEquals(setOf(AppRef("com.new")), groups.groups.first().single().apps)
        }
    }

    @Test fun `PC-80 editing a copied saved group cannot mutate active Timer targets tier identity or deadline`() = runBlocking {
        groupSnapshot(delete = false)
    }

    @Test fun `PC-80 deleting a copied saved group preserves every tier active snapshot across store recreation`() = runBlocking {
        groupSnapshot(delete = true)
    }

    @Test fun `PC-57 persisted spring gap moves missing start forward once and retains resolved end`() = runBlocking {
        val first = AccessClockStamp(Instant.parse("2026-03-08T07:40:00Z").toEpochMilli(), 1_000, "boot")
        val disk = Disk()
        val editor = DataStoreEditorCommitmentStore(disk, defaultZone = { "America/New_York" })
        editor.reconcile(first)
        val rule = BlockRule("gap", "Spring gap", BlockTargets(apps = setOf(AppRef("com.target"))),
            Schedule(setOf(7), 150, 240), BreakLevel.HARDCORE) // 02:30 resolves to 03:30; end is 04:00.
        DataStoreBlockRulesStore(disk).upsert(rule)
        repeat(2) { editor.reconcile(first) }
        val original = snapshot(editor).causes.getValue(ruleCauseKey(rule.id))
        assertEquals(Instant.parse("2026-03-08T07:30:00Z").toEpochMilli().toString(), original.occurrenceId)
        assertEquals(20 * 60_000L, original.interval!!.remainingMs(first))
        assertEquals(Instant.parse("2026-03-08T08:00:00Z").toEpochMilli(), original.interval.wallEndEpochMs)
        val reopened = DataStoreEditorCommitmentStore(disk, defaultZone = { "Asia/Tokyo" })
        repeat(2) { reopened.reconcile(first) }
        assertEquals(original, snapshot(reopened).causes.getValue(ruleCauseKey(rule.id)))
        val end = first.copy(wallEpochMs = first.wallEpochMs + 20 * 60_000,
            elapsedRealtimeMs = first.elapsedRealtimeMs + 20 * 60_000)
        repeat(2) { reopened.reconcile(end) }
        val ended = snapshot(reopened).causes.getValue(ruleCauseKey(rule.id))
        assertEquals(original.occurrenceId, ended.occurrenceId)
        assertEquals(0L, ended.interval!!.remainingMs(end))
        assertEquals(rule, DataStoreBlockRulesStore(disk).rules.first().single())
    }
}
