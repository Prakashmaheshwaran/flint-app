package com.flint.peakfocus

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.flint.peakfocus.blocking.engine.BlockDecisionEngine
import com.flint.peakfocus.blocking.engine.RuleClockProjection
import com.flint.peakfocus.core.datastore.*
import com.flint.peakfocus.core.model.*
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Production store -> production bridge -> detector-shared holder, with serialized in-memory persistence. */
class EditorDomainCompletenessTest {
    private class Disk : DataStore<Preferences> {
        val values = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        var changedWrites = 0
        override val data = values.asStateFlow()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = mutex.withLock {
            transform(values.value).also { if (it != values.value) changedWrites++; values.value = it }
        }
    }

    private val base = Instant.parse("2026-10-28T10:00:00Z").toEpochMilli()
    private var now = AccessClockStamp(base, 10_000, "boot")
    private val target = "com.target"
    private val safe = EditorSafety(true)

    @After fun resetHolder() {
        ActiveRulesHolder.configureProjection { it }
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, emptyList())
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_LEGACY, emptyList())
    }

    private suspend fun apply(editor: EditorCommitmentStore, change: EditorChange) {
        val preview = editor.request(change, now, safe) as EditorCommitResult.Preview
        assertEquals(EditorCommitResult.Applied(), editor.confirm(preview.pending.id, now, safe))
    }

    private suspend fun active(disk: Disk, tier: BreakLevel = BreakLevel.HARDCORE): DataStoreEditorCommitmentStore {
        val editor = DataStoreEditorCommitmentStore(disk, defaultZone = { "UTC" })
        editor.reconcile(now)
        DataStoreGroupsStore(disk).upsert(AppGroup("saved", "Saved", setOf(AppRef("com.saved"))))
        apply(editor, EditorChange("Schedule", upserts = listOf(BlockRule("work", "Work",
            BlockTargets(apps = setOf(AppRef(target))), Schedule(startMinuteOfDay = 540, endMinuteOfDay = 660), tier))))
        apply(editor, EditorChange("Limits", limits = EditorLimitsChange("com.limited",
            TimeLimit("com.limited", 30, BreakLevel.HARDCORE), OpenLimit("com.limited", 3, BreakLevel.HARDCORE))))
        ActiveRulesHolder.configureProjection { RuleClockProjection.project(it, now) }
        ActiveRulesBridge.reconcileAndPublish(editor, now)
        assertTrue(ActiveRulesHolder.dataStoreState is StorageState.Ready)
        assertTrue(verdict() is Verdict.Block)
        return editor
    }

    private fun verdict(): Verdict = BlockDecisionEngine().decide(target, null, ActiveRulesHolder.rules,
        "com.flint.peakfocus", 600, 3, now.wallEpochMs)

    private suspend fun assertUnavailableWithoutWrite(disk: Disk, editor: EditorCommitmentStore) {
        val raw = disk.values.value.asMap().toMap()
        val writes = disk.changedWrites
        assertEquals(StorageState.Unavailable(StorageFailure.CORRUPT), editor.state.first { it !is StorageState.Loading })
        assertEquals(EDITOR_RETRY_MS, ActiveRulesBridge.reconcileAndPublish(editor, now))
        assertEquals(StorageState.Unavailable(StorageFailure.CORRUPT), ActiveRulesHolder.dataStoreState)
        assertTrue(verdict() is Verdict.Block)
        assertEquals(raw, disk.values.value.asMap())
        assertEquals(writes, disk.changedWrites)
    }

    @Test fun `R2-N03 PC-11 PC-37 PC-62 PC-71 missing rules never publishes empty or drops the finite Hardcore input`() = runBlocking {
        val disk = Disk(); val editor = active(disk)
        val original = ActiveRulesHolder.rules.single()
        assertEquals(BreakLevel.HARDCORE, original.breakLevel)
        assertFalse((editor.state.first { it is StorageState.Ready } as StorageState.Ready).value
            .causes.getValue(ruleCauseKey("work")).legacyRights)
        disk.values.value = disk.values.value.toMutablePreferences().apply { remove(stringPreferencesKey("block_rules_v1")) }
        now = now.copy(wallEpochMs = base + 8 * 3_600_000L, elapsedRealtimeMs = 11_000)
        assertUnavailableWithoutWrite(disk, editor)
        assertEquals(original.id, ActiveRulesHolder.rules.single().id)
        assertEquals(now.wallEpochMs + 3_599_000L, ActiveRulesHolder.rules.single().expiresAtEpochMs)
        // The retained input used by both detectors is still finite even with corrupt storage.
        now = now.copy(wallEpochMs = base - 86_400_000L, elapsedRealtimeMs = 3_610_000)
        assertEquals(Verdict.Allow, verdict())
        assertEquals(StorageState.Unavailable(StorageFailure.CORRUPT), ActiveRulesHolder.dataStoreState)
    }

    @Test fun `R2-N03 PC-37 PC-71 every missing established groups time Open and default domain is unavailable`() = runBlocking {
        for (key in listOf("app_groups_v1", "time_limits_v1", "open_limits_v1", "default_break_level_v1")) {
            val disk = Disk(); val editor = active(disk)
            disk.values.value = disk.values.value.toMutablePreferences().apply { remove(stringPreferencesKey(key)) }
            assertUnavailableWithoutWrite(disk, editor)
        }
    }

    @Test fun `R2-N03 PC-62 a genuinely fresh empty installation publishes Ready empty with explicit domains`() = runBlocking {
        val disk = Disk(); val editor = DataStoreEditorCommitmentStore(disk, defaultZone = { "UTC" })
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, listOf(BlockRule("old", "Old", BlockTargets())))
        assertNull(ActiveRulesBridge.reconcileAndPublish(editor, now))
        assertEquals(StorageState.Ready(emptyList<BlockRule>()), ActiveRulesHolder.dataStoreState)
        assertTrue(ActiveRulesHolder.rules.isEmpty())
        val first = disk.values.value.asMap().toMap(); val writes = disk.changedWrites
        assertEquals(2L, disk.values.value[longPreferencesKey("editor_commitments_version")])
        for (key in listOf("block_rules_v1", "app_groups_v1", "time_limits_v1", "open_limits_v1"))
            assertEquals("", disk.values.value[stringPreferencesKey(key)])
        assertEquals("EASY", disk.values.value[stringPreferencesKey("default_break_level_v1")])
        repeat(2) { ActiveRulesBridge.reconcileAndPublish(editor, now) }
        assertEquals(first, disk.values.value.asMap()); assertEquals(writes, disk.changedWrites)
    }

    @Test fun `R2-N03 a valid explicitly empty rule list can still clear an ended Easy selection`() = runBlocking {
        val disk = Disk(); val editor = active(disk, BreakLevel.EASY)
        apply(editor, EditorChange("Delete", deleteIds = setOf("work")))
        assertEquals("", disk.values.value[stringPreferencesKey("block_rules_v1")])
        ActiveRulesBridge.reconcileAndPublish(editor, now)
        assertEquals(StorageState.Ready(emptyList<BlockRule>()), ActiveRulesHolder.dataStoreState)
        assertEquals(Verdict.Allow, verdict())
    }

    @Test fun `R2-N03 malformed or wrong-typed groups retain the last finite input and original bytes`() = runBlocking {
        for (wrongType in listOf(false, true)) {
            val disk = Disk(); val editor = active(disk)
            disk.values.value = disk.values.value.toMutablePreferences().apply {
                remove(stringPreferencesKey("app_groups_v1"))
                if (wrongType) this[intPreferencesKey("app_groups_v1")] = 7
                else this[stringPreferencesKey("app_groups_v1")] = "malformed original"
            }
            assertUnavailableWithoutWrite(disk, editor)
        }
    }

    @Test fun `R2-N03 cancelling publication cannot turn the old acknowledged input into an empty success`() = runBlocking {
        val disk = Disk(); val editor = active(disk)
        val previous = ActiveRulesHolder.dataStoreState
        val cancelled = object : EditorCommitmentStore by editor {
            override suspend fun reconcileRules(now: AccessClockStamp): List<BlockRule> = throw CancellationException("cancelled")
        }
        try { ActiveRulesBridge.reconcileAndPublish(cancelled, now); fail("Cancellation swallowed") }
        catch (_: CancellationException) { }
        assertEquals(previous, ActiveRulesHolder.dataStoreState)
        assertTrue(verdict() is Verdict.Block)
    }
}
