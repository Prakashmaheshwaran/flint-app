package com.flint.peakfocus.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
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
        private val mutex = Mutex()
        override val data = values.asStateFlow()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = mutex.withLock {
            transform(values.value).also { values.value = it }
        }
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
}
