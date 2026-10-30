package com.flint.peakfocus.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.flint.peakfocus.core.model.*
import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class EditorMigrationCompatibilityTest {
    private val base = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear(); set(2026, Calendar.OCTOBER, 28, 10, 0)
    }.timeInMillis
    private fun clock(elapsed: Long = 0) = AccessClockStamp(base + elapsed, 10_000 + elapsed, "boot")
    private class Disk(initial: Preferences) : DataStore<Preferences> {
        val values = MutableStateFlow(initial)
        private val lock = Mutex()
        var changedWrites = 0
        var loseAck = false
        override val data = values.asStateFlow()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = lock.withLock {
            val next = transform(values.value)
            if (next != values.value) changedWrites++
            values.value = next
            if (loseAck) throw CancellationException("simulated death after durable migration")
            next
        }
    }

    @Test fun `PC-70 concurrent first production Ready readers never expose a synthetic Easy tier`() = runBlocking {
        val literal = "one~30\ntwo~0"
        val disk = Disk(preferencesOf(PrefsKeys.TIME_LIMITS to literal, PrefsKeys.DEFAULT_BREAK_LEVEL to "HARDCORE"))
        val facades = List(2) { EditorMigratingLimitsStore(disk) { clock() } }
        val editor = DataStoreEditorCommitmentStore(disk, defaultZone = { TimeZone.getDefault().id })
        withTimeout(5_000) {
            (List(20) { index -> async(Dispatchers.Default) {
                val first = facades[index % 2].timeLimitsState.first { it !is StorageState.Loading }
                assertTrue(first is StorageState.Ready)
                val limits = (first as StorageState.Ready).value
                assertEquals(listOf(BreakLevel.HARDCORE, BreakLevel.HARDCORE), limits.map { it.breakLevel })
                assertEquals("one~30~HARDCORE\ntwo~0~HARDCORE", disk.values.value[PrefsKeys.TIME_LIMITS])
            } } + async(Dispatchers.Default) { editor.reconcileRules(clock()) }).awaitAll()
        }
        assertEquals(1, disk.changedWrites)
        assertEquals(literal, disk.values.value[stringPreferencesKey("editor_migration_time_limits_v1")])
        DataStoreFocusStateStore(disk).setDefaultBreakLevel(BreakLevel.EASY)
        assertEquals(listOf(BreakLevel.HARDCORE, BreakLevel.HARDCORE), facades.first().timeLimits.first().map { it.breakLevel })
    }

    @Test fun `PC-18 PC-70 literal global Harder count seeds active causes once and the next natural occurrence starts zero`() = runBlocking {
        val timerId = "session-timer-$base-legacy"
        val rawRules = """
            work|Work|true|HARDER|false|a~A|||540|660
            sleep|Sleep|true|EASY|false|b~B|||540|660
            later|Later|true|HARDER|false|c~C|||720|780
            always|Always|true|HARDER|false|d~D|||-1|-1
            $timerId|Timer|true|HARDER|false|e~E|||-1|-1|${base + 300_000}
        """.trimIndent()
        val rawBreak = "3~${base - 1_000}~${base + 59_000}"
        val disk = Disk(preferencesOf(PrefsKeys.BLOCK_RULES to rawRules, PrefsKeys.BREAK_SESSION to rawBreak))
        val editor = DataStoreEditorCommitmentStore(disk, defaultZone = { "UTC" })
        editor.reconcile(clock())
        val first = editor.state.snapshotOrNull()!!
        val work = editorOccurrence("rule:work", first.causes.getValue("rule:work"))
        val always = editorOccurrence("rule:always", first.causes.getValue("rule:always"))
        val timer = editorOccurrence(ruleCauseKey(timerId), first.causes.getValue(ruleCauseKey(timerId)))
        val ledger = readHarderWait(disk.values.value)
        assertEquals(mapOf(work to 3, always to 3, timer to 3), ledger.counts)
        assertNull(ledger.pending) // Old pending timestamps have no proven cause/boot scope.
        assertTrue(first.causes.getValue("rule:work").legacyRights)
        assertTrue(first.causes.getValue("rule:sleep").legacyRights)
        assertEquals(BreakLevel.EASY, DataStoreBlockRulesStore(disk).rules.first().single { it.id == "sleep" }.breakLevel)
        assertEquals(rawRules, disk.values.value[PrefsKeys.BLOCK_RULES])
        assertEquals(rawBreak, disk.values.value[PrefsKeys.BREAK_SESSION])
        editor.reconcile(clock(1_000))
        assertEquals(ledger, readHarderWait(disk.values.value))
        editor.reconcile(clock(86_400_000))
        val next = editor.state.snapshotOrNull()!!
        val newWork = editorOccurrence("rule:work", next.causes.getValue("rule:work"))
        assertNotEquals(work, newWork)
        val after = readHarderWait(disk.values.value)
        assertEquals(0, after.counts[newWork] ?: 0)
        assertFalse(after.counts.containsKey(work))
        assertEquals(3, after.counts[always])
        assertFalse(next.causes.getValue("rule:work").legacyRights)
        assertEquals(rawBreak, disk.values.value[PrefsKeys.BREAK_SESSION])
    }

    @Test fun `PC-70 lost migration acknowledgement reopens the same tier without changing fictional PIN preferences or history`() = runBlocking {
        val preserved = mapOf("fictional_pin_fixture" to "fictional protected bytes",
            "fictional_preferences_fixture" to "fictional preferences", "fictional_history_fixture" to "fictional retained events",
            "fictional_removal_consent_fixture" to "false")
        val initial = mutablePreferencesOf(PrefsKeys.TIME_LIMITS to "app~30", PrefsKeys.DEFAULT_BREAK_LEVEL to "HARDCORE")
        preserved.forEach { (key, value) -> initial[stringPreferencesKey(key)] = value }
        val disk = Disk(initial).apply { loseAck = true }
        try { DataStoreEditorCommitmentStore(disk, defaultZone = { "UTC" }).reconcile(clock()); fail("Lost acknowledgement not injected") }
        catch (_: CancellationException) { }
        disk.loseAck = false
        val committed = disk.values.value.asMap().toMap(); val writes = disk.changedWrites
        val reopened = DataStoreEditorCommitmentStore(disk, defaultZone = { "UTC" })
        repeat(2) { reopened.reconcile(clock()) }
        assertEquals(committed, disk.values.value.asMap()); assertEquals(writes, disk.changedWrites)
        assertEquals("app~30~HARDCORE", disk.values.value[PrefsKeys.TIME_LIMITS])
        assertEquals("app~30", disk.values.value[stringPreferencesKey("editor_migration_time_limits_v1")])
        preserved.forEach { (key, value) -> assertEquals(value, disk.values.value[stringPreferencesKey(key)]) }
        assertTrue(reopened.terminalEvents.snapshotOrNull()!!.isEmpty())
    }
}
