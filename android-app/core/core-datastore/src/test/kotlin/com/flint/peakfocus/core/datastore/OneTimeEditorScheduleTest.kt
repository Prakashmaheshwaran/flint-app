package com.flint.peakfocus.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.flint.peakfocus.blocking.engine.CommitmentPolicy
import com.flint.peakfocus.core.model.*
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

/**
 * A dated one-time rule through the editor commitment store: pending → active → finished, on the
 * TRUSTED timeline (same-boot `elapsedRealtime` plus the persisted wall anchor), never on a
 * rewindable wall clock, and never replayed or repeated afterwards (PC-81).
 */
class OneTimeEditorScheduleTest {
    private class Disk(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        val values = MutableStateFlow(initial)
        private val lock = Mutex()
        override val data: Flow<Preferences> = values
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = lock.withLock {
            transform(values.value).also { values.value = it }
        }
    }

    private val zone = ZoneId.of("UTC")
    private val base = LocalDate.parse("2026-11-20").atStartOfDay(zone).plusHours(8).toInstant().toEpochMilli()
    private val date = LocalDate.parse("2026-11-23")
    private val startsAt = date.atStartOfDay(zone).plusMinutes(9 * 60L).toInstant().toEpochMilli()
    private val endsAt = date.atStartOfDay(zone).plusMinutes(11 * 60L).toInstant().toEpochMilli()
    private val safe = EditorSafety(true)

    /** [wall] and [elapsed] are deltas from the fixed base instant, so boot identity stays visible. */
    private fun at(elapsed: Long = 0, wall: Long = elapsed, boot: String = "boot") =
        AccessClockStamp(base + wall, 10_000 + elapsed, boot)

    private fun rule(onDate: LocalDate = date, tier: BreakLevel = BreakLevel.HARDCORE) = BlockRule(
        id = "one",
        name = "One time",
        targets = BlockTargets(apps = setOf(AppRef("a"))),
        schedule = Schedule(startMinuteOfDay = 9 * 60, endMinuteOfDay = 11 * 60, date = onDate),
        breakLevel = tier,
    )

    private fun store(disk: Disk) = DataStoreEditorCommitmentStore(disk, defaultZone = { "UTC" })

    private suspend fun snapshot(store: EditorCommitmentStore) =
        (store.state.first { it is StorageState.Ready } as StorageState.Ready).value

    private suspend fun create(store: DataStoreEditorCommitmentStore, now: AccessClockStamp, rule: BlockRule) {
        val preview = store.request(EditorChange("Save", upserts = listOf(rule)), now, safe)
        assertTrue("create preview: $preview", preview is EditorCommitResult.Preview)
        assertEquals(EditorCommitResult.Applied(),
            store.confirm((preview as EditorCommitResult.Preview).pending.id, now, safe))
    }

    private suspend fun projection(store: EditorCommitmentStore, now: AccessClockStamp): RuleEvaluation =
        checkNotNull(store.reconcileRules(now).single().runtime?.evaluation)

    @Test fun `a dated rule three days ahead is pending and arms its exact start`() = runBlocking {
        val disk = Disk()
        val store = store(disk)
        store.reconcile(at())
        create(store, at(), rule())
        val cause = snapshot(store).causes.getValue(ruleCauseKey("one"))
        assertEquals("inactive", cause.occurrenceId)
        assertEquals(0L, cause.interval!!.remainingMs(at()))
        assertEquals("UTC", cause.zoneId)
        val evaluation = projection(store, at())
        assertFalse(evaluation.active)
        assertEquals(startsAt, evaluation.nextBoundaryEpochMs)
        assertEquals(listOf(rule()), DataStoreBlockRulesStore(disk).rules.first())
    }

    @Test fun `trusted elapsed reaching the start activates it without any wall movement`() = runBlocking {
        val disk = Disk()
        val store = store(disk)
        store.reconcile(at())
        create(store, at(), rule())
        // Same boot: elapsedRealtime advances three days, the wall clock stays where it was.
        val now = at(elapsed = (startsAt - base) + 60_000, wall = 0)
        assertTrue(projection(store, now).active)
        store.reconcile(now)
        val cause = snapshot(store).causes.getValue(ruleCauseKey("one"))
        assertEquals("$startsAt", cause.occurrenceId)
        assertEquals(endsAt - (startsAt + 60_000), cause.interval!!.remainingMs(now))
    }

    @Test fun `moving the wall clock forward in the same boot cannot start it early`() = runBlocking {
        val disk = Disk()
        val store = store(disk)
        store.reconcile(at())
        create(store, at(), rule())
        // The wall jumps into the window while elapsedRealtime has not moved at all.
        val jumped = at(elapsed = 0, wall = (startsAt - base) + 3_600_000)
        val evaluation = projection(store, jumped)
        assertFalse(evaluation.active)
        // Arming is measured on the trusted timeline: the remaining delay, not the skewed wall.
        assertEquals(startsAt - base, evaluation.nextBoundaryEpochMs!! - jumped.wallEpochMs)
        store.reconcile(jumped)
        assertEquals("inactive", snapshot(store).causes.getValue(ruleCauseKey("one")).occurrenceId)
    }

    @Test fun `moving the wall clock back after the start neither ends nor extends it`() = runBlocking {
        val disk = Disk()
        val store = store(disk)
        store.reconcile(at())
        create(store, at(), rule())
        val elapsed = (startsAt - base) + 60_000
        store.reconcile(at(elapsed = elapsed, wall = 0))
        val rolledBack = at(elapsed = elapsed, wall = -86_400_000)
        val evaluation = projection(store, rolledBack)
        assertTrue(evaluation.active)
        // A full two-hour window minus the one trusted minute that already ran.
        assertEquals(2 * 3_600_000L - 60_000, evaluation.endsAtEpochMs!! - rolledBack.wallEpochMs)
        store.reconcile(rolledBack)
        assertTrue(projection(store, rolledBack).active)
    }

    @Test fun `the trusted end makes it inactive and leaves no next boundary`() = runBlocking {
        val disk = Disk()
        val store = store(disk)
        store.reconcile(at())
        create(store, at(), rule())
        store.reconcile(at(elapsed = (startsAt - base) + 60_000, wall = 0))
        val afterEnd = at(elapsed = (endsAt - base) + 1_000, wall = 0)
        val evaluation = projection(store, afterEnd)
        assertFalse(evaluation.active)
        assertNull(evaluation.nextBoundaryEpochMs)
        store.reconcile(afterEnd)
        assertEquals(0L, snapshot(store).causes.getValue(ruleCauseKey("one")).interval!!.remainingMs(afterEnd))
        // Still listed: a finished one-time rule is history, not a silent deletion.
        assertEquals(listOf(rule()), DataStoreBlockRulesStore(disk).rules.first())
    }

    @Test fun `a reboot rolled back into the ended window cannot replay it`() = runBlocking {
        val disk = Disk()
        val store = store(disk)
        store.reconcile(at())
        create(store, at(), rule())
        store.reconcile(at(elapsed = (startsAt - base) + 60_000, wall = 0))
        val materialized = snapshot(store).causes.getValue(ruleCauseKey("one")).occurrenceId
        store.reconcile(at(elapsed = (endsAt - base) + 1_000, wall = 0))
        // New boot whose wall clock sits inside the already-materialized window.
        val reboot = AccessClockStamp(startsAt + 60_000, 1_000, "boot-b")
        val evaluation = projection(store(disk), reboot)
        assertFalse(evaluation.active)
        assertNull(evaluation.nextBoundaryEpochMs)
        assertEquals(materialized, snapshot(store).causes.getValue(ruleCauseKey("one")).occurrenceId)
    }

    @Test fun `a week later the one time rule never becomes active again`() = runBlocking {
        val disk = Disk()
        val store = store(disk)
        store.reconcile(at())
        create(store, at(), rule())
        store.reconcile(at(elapsed = (startsAt - base) + 60_000, wall = 0))
        store.reconcile(at(elapsed = (endsAt - base) + 1_000, wall = 0))
        for (wall in listOf(endsAt + 7 * 86_400_000L, endsAt + 364 * 86_400_000L)) {
            val reboot = AccessClockStamp(wall, 1_000, "boot-b")
            val evaluation = projection(store(disk), reboot)
            assertFalse("$wall", evaluation.active)
            assertNull("$wall", evaluation.nextBoundaryEpochMs)
        }
    }

    @Test fun `editing the date of an active Hardcore one time rule is deferred until its end`() = runBlocking {
        val disk = Disk()
        val store = store(disk)
        store.reconcile(at())
        create(store, at(), rule())
        val activeAt = at(elapsed = (startsAt - base) + 60_000, wall = 0)
        store.reconcile(activeAt)
        assertTrue(projection(store, activeAt).active)
        val moved = rule(onDate = LocalDate.parse("2026-11-25"))
        val preview = store.request(EditorChange("Move", upserts = listOf(moved)), activeAt, safe)
        assertEquals(CommitmentPolicy.Outcome.DEFER, (preview as EditorCommitResult.Preview).pending.outcome)
        assertEquals(EditorCommitResult.Applied(queued = true), store.confirm(preview.pending.id, activeAt, safe))
        assertEquals(listOf(rule()), DataStoreBlockRulesStore(disk).rules.first())
        assertEquals(LocalDate.parse("2026-11-25"),
            snapshot(store).queued.single().change.upserts.single().schedule!!.date)
        // The queued edit applies at the exact occurrence end, not before.
        store.reconcile(at(elapsed = (endsAt - base) - 1_000, wall = 0))
        assertEquals(listOf(rule()), DataStoreBlockRulesStore(disk).rules.first())
        store.reconcile(at(elapsed = (endsAt - base) + 1_000, wall = 0))
        assertEquals(listOf(moved), DataStoreBlockRulesStore(disk).rules.first())
        assertTrue(snapshot(store).queued.isEmpty())
    }

    @Test fun `editing the date of a pending one time rule is an ordinary save`() = runBlocking {
        val disk = Disk()
        val store = store(disk)
        store.reconcile(at())
        create(store, at(), rule())
        val moved = rule(onDate = LocalDate.parse("2026-11-25"))
        val preview = store.request(EditorChange("Move", upserts = listOf(moved)), at(), safe)
        assertEquals(CommitmentPolicy.Outcome.ALLOW, (preview as EditorCommitResult.Preview).pending.outcome)
        assertEquals(EditorCommitResult.Applied(), store.confirm(preview.pending.id, at(), safe))
        assertEquals(listOf(moved), DataStoreBlockRulesStore(disk).rules.first())
        assertTrue(snapshot(store).queued.isEmpty())
    }

    @Test fun `a past start is rejected and nothing is written`() = runBlocking {
        val disk = Disk()
        val store = store(disk)
        store.reconcile(at())
        assertEquals(EditorCommitResult.Rejected("Choose a start time in the future."),
            store.request(EditorChange("Save", upserts = listOf(rule(onDate = LocalDate.parse("2026-11-19")))), at(), safe))
        assertTrue(DataStoreBlockRulesStore(disk).rules.first().isEmpty())
    }

    @Test fun `a weekday gate on a one time date is rejected`() = runBlocking {
        val disk = Disk()
        val store = store(disk)
        store.reconcile(at())
        val invalid = (rule().schedule!!).copy(daysOfWeek = setOf(1))
        assertEquals(EditorCommitResult.Rejected("A one-time session runs on its date only."),
            store.request(EditorChange("Save", upserts = listOf(rule().copy(schedule = invalid))), at(), safe))
        assertTrue(DataStoreBlockRulesStore(disk).rules.first().isEmpty())
    }

    @Test fun `a wall rewind cannot make an already past start look authorable`() = runBlocking {
        val disk = Disk()
        val store = store(disk)
        store.reconcile(at())
        // Same boot: two trusted hours pass, so the trusted timeline is 10:00 on the date while
        // the wall clock is dragged back to 05:00. A 09:00 start is in the past on the timeline
        // the engine would arm it against, so authoring must be refused even though the wall
        // clock still reads before 09:00.
        val rewound = at(elapsed = 2 * 3_600_000, wall = -3 * 3_600_000)
        store.reconcile(rewound)
        val today = rule(onDate = LocalDate.parse("2026-11-20")).copy(id = "two")
        assertEquals(EditorCommitResult.Rejected("Choose a start time in the future."),
            store.request(EditorChange("Save", upserts = listOf(today)), rewound, safe))
        assertTrue(DataStoreBlockRulesStore(disk).rules.first().isEmpty())
        // The same rewind still allows a start the trusted timeline has not reached.
        val later = today.copy(schedule = today.schedule!!.copy(startMinuteOfDay = 11 * 60, endMinuteOfDay = 12 * 60))
        val preview = store.request(EditorChange("Save", upserts = listOf(later)), rewound, safe)
        assertTrue("$preview", preview is EditorCommitResult.Preview)
        assertEquals(EditorCommitResult.Applied(),
            store.confirm((preview as EditorCommitResult.Preview).pending.id, rewound, safe))
        assertEquals(listOf(later), DataStoreBlockRulesStore(disk).rules.first())
    }

    @Test fun `a one-time window stretched past 24 elapsed hours by a DST fold is refused like iOS`() = runBlocking {
        val disk = Disk()
        val store = DataStoreEditorCommitmentStore(disk, defaultZone = { "America/New_York" })
        val now = AccessClockStamp(java.time.Instant.parse("2026-10-30T12:00:00Z").toEpochMilli(), 10_000, "boot")
        store.reconcile(now)
        // 01:30 Oct 31 -> 01:15 Nov 1 is 23 h 45 m on the wall, but the 2026-11-01 fold repeats
        // 01:00-02:00 and the later ambiguous end is taken (§9): 24 h 45 m elapsed.
        val stretched = rule(onDate = LocalDate.parse("2026-10-31"), tier = BreakLevel.EASY).copy(
            schedule = Schedule(startMinuteOfDay = 90, endMinuteOfDay = 75, date = LocalDate.parse("2026-10-31")))
        assertEquals(EditorCommitResult.Rejected("A one-time session must end within 24 hours of its start."),
            store.request(EditorChange("Save", upserts = listOf(stretched)), now, safe))
        assertTrue(DataStoreBlockRulesStore(disk).rules.first().isEmpty())
        // 05:00 Oct 31 -> 03:00 Nov 1 crosses the same fold but stays under 24 elapsed hours (23 h).
        val within = stretched.copy(schedule = stretched.schedule!!.copy(startMinuteOfDay = 5 * 60, endMinuteOfDay = 3 * 60))
        val preview = store.request(EditorChange("Save", upserts = listOf(within)), now, safe)
        assertTrue("$preview", preview is EditorCommitResult.Preview)
    }
}
