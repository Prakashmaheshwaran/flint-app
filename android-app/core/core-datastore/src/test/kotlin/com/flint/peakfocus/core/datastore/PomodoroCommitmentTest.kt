package com.flint.peakfocus.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.flint.peakfocus.blocking.engine.RuleClockProjection
import com.flint.peakfocus.core.model.*
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

/**
 * F01 (§1/§2/§5 of `docs/architecture/pomodoro-chain.md`): the Pomodoro parent is one Timer
 * commitment. Its plan survives persistence, its tier governs the whole chain, a planned rest is
 * never an end, and a second Timer stays refused while a rest is in progress.
 */
class PomodoroCommitmentTest {
    private val minute = 60_000L
    private val base = Instant.parse("2026-10-28T10:00:00Z").toEpochMilli()
    private val plan = PomodoroPlan(focusMinutes = 25, restMinutes = 5, rounds = 4)
    private val safe = EditorSafety(true)
    private fun at(elapsed: Long = 0) = AccessClockStamp(base + elapsed, 10_000 + elapsed, "boot")

    private class Disk(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        val values = MutableStateFlow(initial)
        private val lock = Mutex()
        override val data = values.asStateFlow()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = lock.withLock {
            transform(values.value).also { values.value = it }
        }
    }

    private fun store(disk: Disk) = DataStoreEditorCommitmentStore(disk, defaultZone = { "UTC" })

    private fun parent(tier: BreakLevel = BreakLevel.HARDER, plan: PomodoroPlan = this.plan) = BlockRule(
        id = CONTRACT_TIMER_ID_PREFIX + "$base-pomodoro",
        name = "Pomodoro",
        targets = BlockTargets(apps = setOf(AppRef("com.target"))),
        breakLevel = tier,
        expiresAtEpochMs = base + plan.totalMs,
        pomodoro = plan,
    )

    private fun exit(rule: BlockRule) = EditorChange("End ${rule.name}", deleteIds = setOf(rule.id), timerExit = true)

    private fun cause(disk: Disk, rule: BlockRule) =
        readEditorState(disk.values.value).causes.getValue(ruleCauseKey(rule.id))

    private fun phase(rule: BlockRule, cause: EditorCause, now: AccessClockStamp) =
        RuleClockProjection.project(rule.copy(runtime = RuleRuntime(rule.id, cause.interval!!.anchor,
            cause.interval.remainingAtAnchorMs, cause.interval.wallEndEpochMs, cause.zoneId,
            AccessClockStamp(base, 10_000, "boot"))), now).runtime!!.evaluation!!.phase

    // MARK: persistence

    @Test fun `a plan round-trips through the positional rule codec`() {
        val plain = parent().copy(pomodoro = null)
        val local = parent(plan = PomodoroPlan(50, 10, 3, longRestMinutes = 30, longRestEvery = 2))
        assertEquals(listOf(plain, parent(), local), PrefsCodec.decodeRules(PrefsCodec.encodeRules(listOf(plain, parent(), local))))
        assertEquals(local, PrefsCodec.decodeRules(PrefsCodec.encodeRules(listOf(local))).single())
    }

    @Test fun `lines without a plan field decode as a plain Timer`() {
        val encoded = PrefsCodec.encodeRules(listOf(parent().copy(pomodoro = null)))
        // 10-field pre-expiry line and the current 11-field line both predate plans.
        val legacy = encoded.substringBefore('|' + encoded.split('|').last())
        assertEquals(10, legacy.split('|').size)
        assertNull(PrefsCodec.decodeRules(legacy).single().pomodoro)
        val current = PrefsCodec.decodeRules(encoded).single()
        assertNull(current.pomodoro)
        assertEquals(StrictPrefsCodec.rules(encoded), listOf(current))
    }

    @Test fun `a malformed plan decodes as no plan instead of losing the rule`() {
        val rule = parent()
        val planless = PrefsCodec.encodeRules(listOf(rule.copy(pomodoro = null)))
        listOf("25~5~9", "0~5~4", "25~5", "x~y~z", "25~5~4~15~1", "25~5~4~15~x", "").forEach { raw ->
            val line = planless + "|" + raw
            val decoded = PrefsCodec.decodeRules(line).singleOrNull()
            assertEquals("field '$raw'", rule.id, decoded?.id)
            assertNull("field '$raw'", decoded!!.pomodoro)
        }
    }

    @Test fun `a strictly valid plan survives the strict decoder`() {
        val rule = parent(plan = PomodoroPlan(120, 60, 8))
        val encoded = PrefsCodec.encodeRules(listOf(rule))
        assertEquals(listOf(rule), StrictPrefsCodec.rules(encoded))
        assertEquals(12, encoded.split('|').size)
    }

    // MARK: start and rights

    @Test fun `start accepts a plan whose deadline is its chain end`() = runBlocking {
        val disk = Disk(); val owner = store(disk); val rule = parent()
        assertEquals(TimerStartResult.Applying(rule.id, at()), owner.commitTimer(rule, at(), safe))
        assertEquals(base + plan.totalMs, cause(disk, rule).interval!!.wallEndEpochMs)
        assertEquals(plan.totalMs, cause(disk, rule).interval!!.remainingMs(at()))
        assertTrue(readTerminalEvents(disk.values.value).isEmpty())
    }

    @Test fun `start refuses a plan that disagrees with its deadline or exceeds the cap`() = runBlocking {
        val disk = Disk(); val owner = store(disk)
        val mismatched = parent().copy(expiresAtEpochMs = base + plan.totalMs + minute)
        assertEquals(TimerStartResult.Rejected("Choose a valid Pomodoro plan."),
            owner.commitTimer(mismatched, at(), safe))
        // 8 × 120 + 7 × 60 = 23 h is still inside the 24 h session cap.
        assertTrue(owner.commitTimer(parent(plan = PomodoroPlan(120, 60, 8)), at(), safe) is TimerStartResult.Applying)
    }

    @Test fun `a planned rest is an active commitment but never an end`() = runBlocking {
        val disk = Disk(); val owner = store(disk); val rule = parent()
        owner.commitTimer(rule, at(), safe)
        val restAt = at(26 * minute)
        val rest = cause(disk, rule)
        assertTrue("a rest is an active commitment", isActiveEditorSession(rule, rest, restAt))
        assertEquals(1, phase(rule, rest, restAt)!!.round)
        assertEquals(PomodoroPhaseKind.REST, phase(rule, rest, restAt)!!.kind)
        // No completion is captured while the chain runs through a rest.
        owner.reconcile(restAt)
        assertTrue(readTerminalEvents(disk.values.value).isEmpty())
        assertEquals(listOf(rule), disk.values.value.editorRules())
        // The chain still ends once, at the anchor-derived chain end.
        val endAt = at(plan.totalMs)
        owner.reconcile(endAt)
        val terminal = owner.readTerminalEvents().single()
        assertEquals(rule.id, terminal.sessionId)
        assertEquals(base + plan.totalMs, terminal.logicalEnd.wallEpochMs)
        assertNull(phase(rule, cause(disk, rule), endAt))
    }

    @Test fun `a second Timer is refused during a rest`() = runBlocking {
        val disk = Disk(); val owner = store(disk)
        owner.commitTimer(parent(), at(), safe)
        val second = BlockRule(id = CONTRACT_TIMER_ID_PREFIX + "$base-other", name = "Second",
            targets = BlockTargets(apps = setOf(AppRef("com.other"))),
            expiresAtEpochMs = base + 26 * minute + 5 * minute)
        assertEquals(TimerStartResult.Rejected("A Timer is already active. Finish it before starting another."),
            owner.commitTimer(second, at(26 * minute), safe))
        assertEquals(listOf(parent()), disk.values.value.editorRules())
    }

    @Test fun `Hardcore refuses an exit during focus and during a rest`() = runBlocking {
        listOf(10 * minute, 26 * minute).forEach { elapsed ->
            val disk = Disk(); val owner = store(disk); val rule = parent(BreakLevel.HARDCORE)
            owner.commitTimer(rule, at(), safe)
            val result = owner.request(exit(rule), at(elapsed), safe)
            assertTrue("elapsed $elapsed", result is EditorCommitResult.Rejected)
            assertEquals(listOf(rule), disk.values.value.editorRules())
            assertTrue(readTerminalEvents(disk.values.value).isEmpty())
        }
    }

    @Test fun `Harder waits before ending the chain during a rest`() = runBlocking {
        val disk = Disk(); val owner = store(disk); val rule = parent(BreakLevel.HARDER)
        owner.commitTimer(rule, at(), safe)
        val restAt = at(26 * minute)
        val waiting = owner.request(exit(rule), restAt, safe) as EditorCommitResult.Waiting
        assertEquals(60_000L, waiting.remainingMs)
        assertEquals(listOf(rule), disk.values.value.editorRules())
        val early = owner.confirm(waiting.pending.id, restAt.copy(elapsedRealtimeMs = restAt.elapsedRealtimeMs + 59_000,
            wallEpochMs = restAt.wallEpochMs + 59_000), safe)
        assertTrue(early is EditorCommitResult.Waiting)
        assertEquals(listOf(rule), disk.values.value.editorRules())
    }

    @Test fun `Easy confirms ending the chain during a rest`() = runBlocking {
        val disk = Disk(); val owner = store(disk); val rule = parent(BreakLevel.EASY)
        owner.commitTimer(rule, at(), safe)
        val preview = owner.request(exit(rule), at(26 * minute), safe) as EditorCommitResult.Preview
        assertTrue(disk.values.value.editorRules().isNotEmpty())
        assertEquals(EditorCommitResult.Applied(), owner.confirm(preview.pending.id, at(26 * minute), safe))
        assertTrue(disk.values.value.editorRules().isEmpty())
        assertTrue(readTerminalEvents(disk.values.value).isEmpty())
    }

    @Test fun `a plan cannot be changed after it is frozen`() = runBlocking {
        val disk = Disk(); val owner = store(disk); val rule = parent(BreakLevel.HARDCORE)
        owner.commitTimer(rule, at(), safe)
        val shortened = rule.copy(pomodoro = PomodoroPlan(5, 1, 2),
            expiresAtEpochMs = base + 26 * minute + 11 * minute)
        // A plan change is unprovable weakening, so Hardcore defers it rather than applying it.
        val deferred = owner.request(EditorChange("Edit Pomodoro", upserts = listOf(shortened)), at(26 * minute), safe)
        assertTrue(deferred is EditorCommitResult.Preview)
        assertEquals(listOf(rule), disk.values.value.editorRules())
        assertTrue(owner.request(EditorChange("Edit Pomodoro", upserts = listOf(shortened)), at(26 * minute), safe) !is EditorCommitResult.Applied)
    }

    // MARK: process restart

    @Test fun `re-decoding from storage yields the same phase`() = runBlocking {
        val disk = Disk(); val owner = store(disk); val rule = parent()
        owner.commitTimer(rule, at(), safe)
        val restAt = at(26 * minute)
        val before = phase(rule, cause(disk, rule), restAt)
        // A new process re-reads the durable anchors, never a cached projection.
        val reopened = store(disk)
        reopened.reconcile(restAt)
        val stored = disk.values.value.editorRules().single()
        val after = phase(stored, cause(disk, stored), restAt)
        assertEquals(before, after)
        assertEquals(PomodoroPhaseKind.REST, after!!.kind)
        assertEquals(1, after.round)
        assertEquals(store(disk).rulesCausePhase(rule, restAt), after)
    }

    /** Merges the raw rule with its durable cause, exactly as the editor bridge publishes it. */
    private suspend fun DataStoreEditorCommitmentStore.rulesCausePhase(rule: BlockRule, now: AccessClockStamp): PomodoroPhase? {
        val rules = reconcileRules(now)
        val projected = rules.single { it.id == rule.id }
        return projected.runtime!!.evaluation!!.phase
    }

    @Test fun `the plan survives an unrelated store read and write`() = runBlocking {
        val disk = Disk(); val owner = store(disk); val rule = parent()
        owner.commitTimer(rule, at(), safe)
        owner.reconcile(at(minute))
        val reread = StrictPrefsCodec.rules(disk.values.value[stringPreferencesKey(PrefsKeys.BLOCK_RULES.name)].orEmpty())
        assertEquals(listOf(rule), reread)
    }
}
