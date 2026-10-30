package com.flint.peakfocus.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.flint.peakfocus.blocking.engine.HarderWaitPolicy
import com.flint.peakfocus.core.model.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class TimerStartResultTest {
    private val base = 1_793_181_600_000L
    private fun at(elapsed: Long = 0, wall: Long = elapsed) = AccessClockStamp(base + wall, 10_000 + elapsed, "boot")
    private fun timer(id: String, start: Long = base) = BlockRule(CONTRACT_TIMER_ID_PREFIX + "$start-$id", "Timer",
        BlockTargets(apps = setOf(AppRef("app"))), expiresAtEpochMs = start + 300_000)
    private class Disk : DataStore<Preferences> {
        val values = MutableStateFlow(emptyPreferences())
        var writes = 0
        private val mutex = Mutex()
        override val data = values.asStateFlow()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = mutex.withLock {
            transform(values.value).also { if (it != values.value) writes++; values.value = it }
        }
    }

    private suspend fun installRecovery(disk: Disk) {
        val start = at()
        val committed = DataStoreProtectionTransactionStore(disk, { start }) { "UTC" }.transact { snapshot ->
            val ledger = checkNotNull(snapshot.focus.protection)
            ProtectionMutation(snapshot.focus.copy(emergencyPass = snapshot.focus.emergencyPass.copy(usedAtEpochMs = start.wallEpochMs),
                protection = ledger.copy(
                    cooldown = ProtectionCooldown("pass", start.wallEpochMs, start, 604_800_000),
                    recovery = ProtectionRecovery("pass", start, setOf("rule:work"), setOf("work"), emptySet(), 3_600_000),
                    operations = mapOf("pass" to ProtectionPassReceipt("pass", start, start, "scope")),
                    lastUsedAtEpochMs = start.wallEpochMs)), Unit)
        }
        assertTrue(committed is ProtectionCommit.Committed)
    }

    @Test fun `PC-01 saved result is Applying with the actual transaction clock and leaves unrelated causes`() = runBlocking {
        val disk = Disk(); var now = at()
        val store = DataStoreEditorCommitmentStore(disk, clockAtCommit = { now }, defaultZone = { "UTC" })
        val other = BlockRule("work", "Work", BlockTargets(apps = setOf(AppRef("work"))))
        DataStoreBlockRulesStore(disk).upsert(other)
        now = at(10)
        val rule = timer("new", now.wallEpochMs)
        assertEquals(TimerStartResult.Applying(rule.id, now), store.commitTimer(rule, at(), EditorSafety(true)))
        assertEquals(setOf(other, rule), DataStoreBlockRulesStore(disk).rules.first().toSet())
    }

    @Test fun `PC-54 forward wall edit cannot replace a logically active Timer at commit`() = runBlocking {
        val disk = Disk(); val store = DataStoreEditorCommitmentStore(disk, defaultZone = { "UTC" })
        val original = timer("first")
        assertTrue(store.startTimer(original, at(), EditorSafety(true)))
        val now = at(10_000, 86_400_000)
        val replacement = timer("second", now.wallEpochMs)
        assertTrue(store.commitTimer(replacement, now, EditorSafety(true)) is TimerStartResult.Rejected)
        assertEquals(listOf(original), DataStoreBlockRulesStore(disk).rules.first())
    }

    @Test fun `PC-55 backward wall edit cannot make a naturally ended Timer occupy new capacity forever`() = runBlocking {
        val disk = Disk(); val store = DataStoreEditorCommitmentStore(disk, defaultZone = { "UTC" })
        val original = timer("first")
        assertTrue(store.startTimer(original, at(), EditorSafety(true)))
        val now = at(300_000, -86_400_000)
        val second = timer("second", now.wallEpochMs)
        assertTrue(store.commitTimer(second, now, EditorSafety(true)) is TimerStartResult.Applying)
        assertTrue(original in DataStoreBlockRulesStore(disk).rules.first())
    }

    @Test fun `PC-44 active recovery rejects a manual Timer before any editor or quota write`() = runBlocking {
        val disk = Disk(); installRecovery(disk)
        val before = disk.values.value; val writes = disk.writes
        val now = at(10_000)
        val store = DataStoreEditorCommitmentStore(disk, clockAtCommit = { now }, defaultZone = { "UTC" })
        assertEquals(TimerStartResult.Rejected("Resume protection first."), store.commitTimer(timer("new", now.wallEpochMs), at(), EditorSafety(true)))
        assertEquals(before, disk.values.value); assertEquals(writes, disk.writes)
    }

    @Test fun `PC-44 ended recovery permits a new Timer even while the spent pass remains cooling down`() = runBlocking {
        val disk = Disk(); installRecovery(disk)
        val now = at(3_600_000)
        val store = DataStoreEditorCommitmentStore(disk, clockAtCommit = { now }, defaultZone = { "UTC" })
        val next = timer("new", now.wallEpochMs)
        assertEquals(TimerStartResult.Applying(next.id, now), store.commitTimer(next, at(), EditorSafety(true)))
        assertEquals(listOf(next), DataStoreBlockRulesStore(disk).rules.first())
        assertNotNull(readFocus(disk.values.value).protection!!.cooldown)
    }

    @Test fun `PC-37 initialized protection cannot recreate deleted editor and Harder children`() = runBlocking {
        val disk = Disk()
        assertTrue(DataStoreProtectionTransactionStore(disk, { at() }) { "UTC" }.transact { ProtectionMutation(it.focus, Unit) } is ProtectionCommit.Committed)
        disk.values.value = disk.values.value.toMutablePreferences().apply {
            remove(EDITOR_COMMITMENTS_KEY); remove(EDITOR_VERSION_KEY); remove(HARDER_WAIT_KEY)
        }
        val before = disk.values.value; val writes = disk.writes
        val store = DataStoreEditorCommitmentStore(disk, defaultZone = { "UTC" })
        assertEquals(StorageState.Unavailable(StorageFailure.CORRUPT), store.state.drop(1).first())
        try { store.commitTimer(timer("new"), at(), EditorSafety(true)); fail("Missing initialized children accepted") }
        catch (failure: StorageUnavailableException) { assertEquals(StorageFailure.CORRUPT, failure.reason) }
        try { store.reconcile(at()); fail("Editor-only reconciliation recreated initialized children") }
        catch (failure: StorageUnavailableException) { assertEquals(StorageFailure.CORRUPT, failure.reason) }
        assertEquals(before, disk.values.value); assertEquals(writes, disk.writes)
    }

    @Test fun `PC-70 v1 and initialized v2 round trip Timer and editor writes without losing domain markers or rights`() = runBlocking {
        for (version in listOf(1L, 2L)) {
            val disk = Disk(); val owner = DataStoreEditorCommitmentStore(disk, defaultZone = { "UTC" })
            val original = BlockRule("work", "Legacy", BlockTargets(apps = setOf(AppRef("work"))),
                breakLevel = BreakLevel.HARDER, expiresAtEpochMs = base + 3_600_000)
            DataStoreBlockRulesStore(disk).upsert(original)
            owner.reconcile(at())
            val currentVersion = checkNotNull(disk.values.value[EDITOR_VERSION_KEY])
            val cause = readEditorState(disk.values.value).causes.getValue(ruleCauseKey(original.id))
            assertTrue(cause.legacyRights)
            val occurrence = editorOccurrence(ruleCauseKey(original.id), cause)
            disk.values.value = disk.values.value.toMutablePreferences().apply {
                this[EDITOR_VERSION_KEY] = version
                putHarderWait(HarderWaitPolicy.Ledger(counts = mapOf(occurrence to 4)))
            }
            val rawArchive = disk.values.value[androidx.datastore.preferences.core.stringPreferencesKey("editor_migration_rules_v1")]
            val next = timer("marker-$version")
            assertEquals(TimerStartResult.Applying(next.id, at()), owner.commitTimer(next, at(), EditorSafety(true)))
            assertEquals(currentVersion, disk.values.value[EDITOR_VERSION_KEY])
            val domains = listOf(PrefsKeys.BLOCK_RULES, PrefsKeys.APP_GROUPS, PrefsKeys.TIME_LIMITS,
                PrefsKeys.OPEN_LIMITS, PrefsKeys.DEFAULT_BREAK_LEVEL)
            assertTrue(domains.all { it in disk.values.value })
            val reopened = DataStoreEditorCommitmentStore(disk, defaultZone = { "UTC" })
            val renamed = original.copy(name = "Renamed")
            val preview = reopened.request(EditorChange("Rename", upserts = listOf(renamed)), at(1), EditorSafety(true)) as EditorCommitResult.Preview
            assertEquals(EditorCommitResult.Applied(), reopened.confirm(preview.pending.id, at(1), EditorSafety(true)))
            reopened.reconcile(at(2))
            assertEquals(currentVersion, disk.values.value[EDITOR_VERSION_KEY])
            assertTrue(domains.all { it in disk.values.value })
            assertEquals(setOf(renamed, next), disk.values.value.editorRules().toSet())
            val retained = readEditorState(disk.values.value).causes.getValue(ruleCauseKey(original.id))
            assertEquals(cause.occurrenceId, retained.occurrenceId)
            assertEquals(cause.legacyRights, retained.legacyRights)
            assertEquals(cause.zoneId, retained.zoneId)
            assertEquals(cause.interval!!.wallEndEpochMs, retained.interval!!.wallEndEpochMs)
            assertEquals(cause.interval.remainingMs(at(2)), retained.interval.remainingMs(at(2)))
            assertEquals(4, readHarderWait(disk.values.value).counts[occurrence])
            assertEquals(rawArchive, disk.values.value[androidx.datastore.preferences.core.stringPreferencesKey("editor_migration_rules_v1")])
        }
    }

    @Test fun `PC-71 future editor marker refuses Timer and editor writes without downgrading or changing bytes`() = runBlocking {
        val disk = Disk(); val owner = DataStoreEditorCommitmentStore(disk, defaultZone = { "UTC" })
        owner.reconcile(at())
        val futureVersion = checkNotNull(disk.values.value[EDITOR_VERSION_KEY]) + 1
        disk.values.value = disk.values.value.toMutablePreferences().apply { this[EDITOR_VERSION_KEY] = futureVersion }
        val before = disk.values.value; val writes = disk.writes
        assertEquals(StorageState.Unavailable(StorageFailure.UNSUPPORTED_VERSION), owner.state.first { it !is StorageState.Loading })
        try { owner.commitTimer(timer("future"), at(), EditorSafety(true)); fail("Future marker accepted") }
        catch (failure: StorageUnavailableException) { assertEquals(StorageFailure.UNSUPPORTED_VERSION, failure.reason) }
        try { owner.request(EditorChange("Create", upserts = listOf(timer("future"))), at(), EditorSafety(true)); fail("Future editor write accepted") }
        catch (failure: StorageUnavailableException) { assertEquals(StorageFailure.UNSUPPORTED_VERSION, failure.reason) }
        assertEquals(before, disk.values.value); assertEquals(writes, disk.writes)
    }
}
