package com.flint.peakfocus.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.flint.peakfocus.core.model.*
import java.time.Instant
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

/** Exercise the real request/confirmation transaction before any clock receiver rewrites raw rows. */
class TimerExitClockTest {
    private val base = Instant.parse("2026-10-28T10:00:00Z").toEpochMilli()
    private fun at(elapsed: Long = 0, wall: Long = elapsed) = AccessClockStamp(base + wall, 10_000 + elapsed, "boot")
    private val safe = EditorSafety(true)
    private val day = 86_400_000L
    private fun timer(tier: BreakLevel, legacy: Boolean = false) = BlockRule(
        (if (legacy) NATIVE_TIMER_ID_PREFIX else CONTRACT_TIMER_ID_PREFIX) + "$base-clock", "Timer",
        BlockTargets(apps = setOf(AppRef("target"))), breakLevel = tier, expiresAtEpochMs = base + 300_000)
    private class Disk(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        val values = MutableStateFlow(initial)
        private val lock = Mutex()
        override val data = values.asStateFlow()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = lock.withLock {
            transform(values.value).also { values.value = it }
        }
    }
    private fun store(disk: Disk) = DataStoreEditorCommitmentStore(disk, defaultZone = { "UTC" })
    private fun exit(rule: BlockRule) = EditorChange("End Timer", deleteIds = setOf(rule.id), timerExit = true)
    private fun assertOriginal(disk: Disk, rule: BlockRule, now: AccessClockStamp, remaining: Long) {
        assertEquals(listOf(rule), disk.values.value.editorRules())
        val cause = readEditorState(disk.values.value).causes.getValue(ruleCauseKey(rule.id))
        assertEquals(rule.id, cause.occurrenceId)
        assertEquals(rule.expiresAtEpochMs, cause.interval!!.wallEndEpochMs)
        assertEquals(remaining, cause.interval.remainingMs(now))
    }
    private suspend fun assertEarlyExitHasNoCompletion(disk: Disk, owner: EditorCommitmentStore) {
        assertTrue(disk.values.value.editorRules().isEmpty())
        assertTrue(owner.readTerminalEvents().isEmpty())
        owner.reconcile(at(300_000, day))
        assertTrue(disk.values.value.editorRules().isEmpty())
        assertTrue(owner.readTerminalEvents().isEmpty())
    }

    @Test fun `PC-17 PC-54 PC-67 legacy Hardcore exit retains its original rights after forward wall edit`() = runBlocking {
        val original = timer(BreakLevel.HARDCORE, legacy = true)
        val raw = PrefsCodec.encodeRules(listOf(original))
        val disk = Disk(preferencesOf(PrefsKeys.BLOCK_RULES to raw)); val owner = store(disk)
        owner.reconcile(at())
        val now = at(10_000, day)
        assertOriginal(disk, original, now, 290_000)
        assertTrue(readEditorState(disk.values.value).causes.getValue(ruleCauseKey(original.id)).legacyRights)
        val preview = owner.request(exit(original), now, safe) as EditorCommitResult.Preview
        assertOriginal(disk, original, now, 290_000)
        assertEquals(EditorCommitResult.Applied(), owner.confirm(preview.pending.id, now, safe))
        assertEquals(raw, disk.values.value[stringPreferencesKey("editor_migration_rules_v1")])
        assertTrue(readHarderWait(disk.values.value).counts.isEmpty())
        assertEarlyExitHasNoCompletion(disk, owner)
    }

    @Test fun `PC-17 PC-54 new Easy Timer can exit after forward wall edit without replacing its plan`() = runBlocking {
        val disk = Disk(); val owner = store(disk); val original = timer(BreakLevel.EASY)
        assertEquals(TimerStartResult.Applying(original.id, at()), owner.commitTimer(original, at(), safe))
        val now = at(10_000, day)
        val preview = owner.request(exit(original), now, safe) as EditorCommitResult.Preview
        assertOriginal(disk, original, now, 290_000)
        assertTrue(owner.readTerminalEvents().isEmpty())
        assertEquals(EditorCommitResult.Applied(), owner.confirm(preview.pending.id, now, safe))
        assertTrue(readHarderWait(disk.values.value).counts.isEmpty())
        assertEarlyExitHasNoCompletion(disk, owner)
    }

    @Test fun `PC-17 PC-54 pending Harder exit keeps the same elapsed wait and explicit confirmation after forward wall edit`() = runBlocking {
        val disk = Disk(); val owner = store(disk); val original = timer(BreakLevel.HARDER)
        assertTrue(owner.startTimer(original, at(), safe))
        val waiting = owner.request(exit(original), at(), safe) as EditorCommitResult.Waiting
        assertEquals(60_000L, waiting.remainingMs)
        val reopened = store(disk)
        val beforeEnd = at(59_999, day)
        val stillWaiting = reopened.confirm(waiting.pending.id, beforeEnd, safe) as EditorCommitResult.Waiting
        assertEquals(waiting.pending.id, stillWaiting.pending.id)
        assertEquals(waiting.pending.wait, stillWaiting.pending.wait)
        assertEquals(1L, stillWaiting.remainingMs)
        assertOriginal(disk, original, beforeEnd, 240_001)
        assertTrue(readHarderWait(disk.values.value).counts.isEmpty())
        val ready = at(60_000, day + 1)
        reopened.reconcile(ready)
        assertOriginal(disk, original, ready, 240_000)
        assertTrue(reopened.readTerminalEvents().isEmpty())
        assertEquals(EditorCommitResult.Applied(), reopened.confirm(waiting.pending.id, ready, safe))
        assertEquals(listOf(1), readHarderWait(disk.values.value).counts.values.toList())
        assertTrue(reopened.confirm(waiting.pending.id, ready, safe) is EditorCommitResult.Rejected)
        assertEquals(listOf(1), readHarderWait(disk.values.value).counts.values.toList())
        assertEarlyExitHasNoCompletion(disk, reopened)
    }

    @Test fun `PC-17 PC-55 anchored end rejects direct and stale Harder exits after backward wall edit with no spend`() = runBlocking {
        val disk = Disk(); val owner = store(disk); val original = timer(BreakLevel.HARDER)
        assertTrue(owner.startTimer(original, at(), safe))
        val waiting = owner.request(exit(original), at(), safe) as EditorCommitResult.Waiting
        val ended = at(300_000, -day)
        assertTrue(owner.confirm(waiting.pending.id, ended, safe) is EditorCommitResult.Rejected)
        assertTrue(owner.request(exit(original), ended, safe) is EditorCommitResult.Rejected)
        assertEquals(listOf(original), disk.values.value.editorRules())
        assertEquals(0L, readEditorState(disk.values.value).causes.getValue(ruleCauseKey(original.id)).interval!!.remainingMs(ended))
        assertTrue(readHarderWait(disk.values.value).counts.isEmpty())
        assertNull(readHarderWait(disk.values.value).pending)
        val terminal = owner.readTerminalEvents().single()
        assertEquals(original.id, terminal.sessionId)
        assertEquals(at(300_000), terminal.logicalEnd)
        assertEquals(ended, terminal.observedAt)
        assertTrue(terminal.timeUncertain)
        repeat(2) { store(disk).reconcile(ended) }
        assertEquals(listOf(terminal), owner.readTerminalEvents())
        assertEquals(listOf(original), disk.values.value.editorRules())
        assertTrue(readHarderWait(disk.values.value).counts.isEmpty())
    }
}
