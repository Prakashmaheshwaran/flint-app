package com.flint.peakfocus.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.flint.peakfocus.blocking.engine.*
import com.flint.peakfocus.core.model.*
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Real editor transactions and enforcement publication, with deterministic wall/elapsed/boot clocks. */
class AnchoredEditorClockTest {
    private class Disk(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        val values = MutableStateFlow(initial)
        var beforeUpdate: (() -> Unit)? = null
        private val lock = Mutex()
        override val data: Flow<Preferences> = values
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = lock.withLock {
            beforeUpdate?.also { beforeUpdate = null }?.invoke()
            transform(values.value).also { values.value = it }
        }
    }
    private val hour = 3_600_000L
    private val base = Instant.parse("2026-10-28T10:00:00Z").toEpochMilli()
    private fun clock(elapsed: Long = 0, wall: Long = elapsed, boot: String = "boot-a") =
        AccessClockStamp(base + wall, 10_000 + elapsed, boot)
    private fun rule() = BlockRule("work", "Work", BlockTargets(apps = setOf(AppRef("com.target"))),
        Schedule(startMinuteOfDay = 9 * 60, endMinuteOfDay = 17 * 60), BreakLevel.HARDCORE)
    private fun timer(tier: BreakLevel = BreakLevel.HARDCORE, now: AccessClockStamp = clock()) =
        rule().copy(id = CONTRACT_TIMER_ID_PREFIX + "clock", schedule = null, breakLevel = tier,
            expiresAtEpochMs = now.wallEpochMs + 300_000)
    private suspend fun fresh(disk: Disk) = DataStoreEditorCommitmentStore(disk, defaultZone = { "UTC" }).also { it.reconcile(clock()) }
    private suspend fun state(store: EditorCommitmentStore) = (store.state.first { it !is StorageState.Loading } as StorageState.Ready).value
    private fun shift(now: AccessClockStamp) = ClockShift(base + now.elapsedRealtimeMs - 10_000, now.wallEpochMs, 0, 0)
    private fun blocked(rules: List<BlockRule>, now: AccessClockStamp, zone: ZoneId = ZoneId.of("UTC")): Boolean {
        val local = Instant.ofEpochMilli(now.wallEpochMs).atZone(zone)
        return BlockDecisionEngine().decide("com.target", null, rules, "com.flint",
            local.hour * 60 + local.minute, local.dayOfWeek.value, now.wallEpochMs) is Verdict.Block
    }

    @After fun clearHolder() {
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, emptyList())
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_LEGACY, emptyList())
        ActiveRulesHolder.configureProjection { it }
    }

    @Test fun `R-18 PC-54 PC-55 real publication keeps active Hardcore across wall and zone changes until elapsed end`() = runBlocking {
        val disk = Disk(); val store = fresh(disk)
        DataStoreBlockRulesStore(disk).upsert(rule())
        var now = clock()
        ActiveRulesHolder.configureProjection { RuleClockProjection.project(it, now) }
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, store.reconcileRules(now))
        val id = state(store).causes.getValue(ruleCauseKey("work")).occurrenceId
        now = clock(60_000, 8 * hour)
        assertTrue(blocked(ActiveRulesHolder.rules, now, ZoneId.of("Pacific/Auckland")))
        assertEquals(now.wallEpochMs + 7 * hour - 60_000, ActiveRulesHolder.rules.single().expiresAtEpochMs)
        store.guardSessionClock(now, shift(now))
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, store.reconcileRules(now))
        assertEquals(id, state(store).causes.getValue(ruleCauseKey("work")).occurrenceId)
        assertEquals(rule(), DataStoreBlockRulesStore(disk).rules.first().single())
        now = clock(120_000, -4 * hour)
        assertTrue(blocked(ActiveRulesHolder.rules, now))
        assertEquals(now.wallEpochMs + 7 * hour - 120_000,
            NextEvaluationBoundary.next(now.wallEpochMs, ZoneId.of("UTC"), "com.target", ActiveRulesHolder.rules))
        now = clock(7 * hour, hour)
        assertFalse(blocked(ActiveRulesHolder.rules, now))
        assertFalse(blocked(store.reconcileRules(now), now))
        assertEquals(id, state(store).causes.getValue(ruleCauseKey("work")).occurrenceId)
    }

    @Test fun `R-18 PC-56 future schedule uses saved zone and logical timeline after device zone change`() = runBlocking {
        val disk = Disk(); var zone = "UTC"
        val store = DataStoreEditorCommitmentStore(disk, defaultZone = { zone })
        store.reconcile(clock())
        val future = rule().copy(schedule = Schedule(startMinuteOfDay = 12 * 60, endMinuteOfDay = 13 * 60))
        DataStoreBlockRulesStore(disk).upsert(future)
        store.reconcile(clock())
        zone = "Pacific/Auckland"
        val before = clock(hour, 9 * hour)
        val published = store.reconcileRules(before)
        assertFalse(blocked(published, before, ZoneId.of(zone)))
        assertEquals(before.wallEpochMs + hour,
            NextEvaluationBoundary.next(before.wallEpochMs, ZoneId.of(zone), "com.target", published))
        val start = clock(2 * hour, 10 * hour)
        assertTrue(blocked(store.reconcileRules(start), start, ZoneId.of(zone)))
        val cause = state(store).causes.getValue(ruleCauseKey("work"))
        assertEquals("UTC", cause.zoneId)
        assertEquals((base + 2 * hour).toString(), cause.occurrenceId)
        assertEquals(hour, cause.interval!!.remainingMs(start))
    }

    @Test fun `R-18 PC-58 persisted reboot bound cannot regrow after rollback or replay same occurrence`() = runBlocking {
        val disk = Disk(); val store = fresh(disk)
        DataStoreBlockRulesStore(disk).upsert(rule()); store.reconcile(clock())
        val id = state(store).causes.getValue(ruleCauseKey("work")).occurrenceId
        val reboot = clock(5, 6 * hour, "boot-b")
        store.reconcileRules(reboot)
        val persisted = state(store).causes.getValue(ruleCauseKey("work")).interval!!
        assertEquals(reboot, persisted.anchor); assertEquals(hour, persisted.remainingAtAnchorMs)
        val recreated = DataStoreEditorCommitmentStore(disk, defaultZone = { "UTC" })
        val rollback = clock(1_005, 0, "boot-b")
        val effective = recreated.reconcileRules(rollback).single()
        assertEquals(rollback.wallEpochMs + hour - 1_000, effective.expiresAtEpochMs)
        val end = clock(5 + hour, hour, "boot-b")
        assertFalse(blocked(recreated.reconcileRules(end), end))
        assertEquals(0L, state(recreated).causes.getValue(ruleCauseKey("work")).interval!!.remainingAtAnchorMs)
        val nextBoot = clock(1, 0, "boot-c")
        repeat(2) { assertFalse(blocked(recreated.reconcileRules(nextBoot), nextBoot)) }
        assertEquals(id, state(recreated).causes.getValue(ruleCauseKey("work")).occurrenceId)
        assertEquals(0L, state(recreated).causes.getValue(ruleCauseKey("work")).interval!!.remainingAtAnchorMs)
    }

    @Test fun `R-18 clock transaction preserves concurrent stronger record and keeps old confirmation stale`() = runBlocking {
        val disk = Disk(); val store = fresh(disk); val old = timer(BreakLevel.EASY)
        assertTrue(store.startTimer(old, clock()))
        val preview = store.request(EditorChange("Delete", deleteIds = setOf(old.id)), clock(), EditorSafety(true)) as EditorCommitResult.Preview
        val stronger = old.copy(name = "Concurrent protection", breakLevel = BreakLevel.HARDCORE,
            targets = BlockTargets(apps = setOf(AppRef("com.other"))))
        disk.beforeUpdate = { disk.values.value = disk.values.value.toMutablePreferences().apply { putEditorRules(listOf(stronger)) } }
        val now = clock(60_000, 8 * hour)
        store.guardSessionClock(now, shift(now))
        val saved = DataStoreBlockRulesStore(disk).rules.first().single()
        assertEquals(stronger.copy(expiresAtEpochMs = now.wallEpochMs + 240_000), saved)
        assertTrue(store.confirm(preview.pending.id, now, EditorSafety(true)) is EditorCommitResult.Rejected)
        DataStoreBlockRulesStore(disk).delete(saved.id)
        store.guardSessionClock(now, shift(now))
        assertTrue(DataStoreBlockRulesStore(disk).rules.first().isEmpty())
    }

    @Test fun `R-18 duplicate clock callbacks do not extend migrated finite duration or resurrect expired records`() = runBlocking {
        val old = timer(); val expired = old.copy(id = "expired", expiresAtEpochMs = base - 1)
        val disk = Disk(preferencesOf(PrefsKeys.BLOCK_RULES to PrefsCodec.encodeRules(listOf(old, expired))))
        val store = DataStoreEditorCommitmentStore(disk, defaultZone = { "UTC" })
        val now = clock(60_000, 8 * hour)
        val jump = shift(now)
        store.guardSessionClock(now, jump)
        val deadline = DataStoreBlockRulesStore(disk).rules.first().single { it.id == old.id }.expiresAtEpochMs
        repeat(2) { store.guardSessionClock(now, jump) }
        val later = now.copy(wallEpochMs = now.wallEpochMs + 60_000, elapsedRealtimeMs = now.elapsedRealtimeMs + 60_000)
        store.guardSessionClock(later, jump)
        assertEquals(now.wallEpochMs + 240_000, deadline)
        assertEquals(deadline, DataStoreBlockRulesStore(disk).rules.first().single { it.id == old.id }.expiresAtEpochMs)
        assertEquals(180_000L, state(store).causes.getValue(ruleCauseKey(old.id)).interval!!.remainingMs(later))
        assertFalse(store.reconcileRules(later).single { it.id == expired.id }.runtime!!.evaluation!!.active)
    }

    @Test fun `R-18 queued schedule deletion keeps old elapsed end after clock guards`() = runBlocking {
        val disk = Disk(); val store = fresh(disk); DataStoreBlockRulesStore(disk).upsert(rule())
        val preview = store.request(EditorChange("Delete", deleteIds = setOf("work")), clock(), EditorSafety(true)) as EditorCommitResult.Preview
        assertEquals(EditorCommitResult.Applied(true), store.confirm(preview.pending.id, clock(), EditorSafety(true)))
        val jumped = clock(60_000, 8 * hour)
        store.guardSessionClock(jumped, shift(jumped))
        val beforeEnd = clock(7 * hour - 1, hour)
        assertTrue(blocked(store.reconcileRules(beforeEnd), beforeEnd))
        val end = clock(7 * hour, hour)
        assertTrue(store.reconcileRules(end).isEmpty())
        assertTrue(state(store).queued.isEmpty())
    }

    @Test fun `R-18 finite Timer started after wall jump keeps requested duration`() = runBlocking {
        val disk = Disk(); val store = fresh(disk)
        val now = clock(60_000, 8 * hour)
        val newTimer = timer(now = now)
        assertTrue(store.startTimer(newTimer, now))
        assertEquals(300_000L, state(store).causes.getValue(ruleCauseKey(newTimer.id)).interval!!.remainingMs(now))
        assertEquals(newTimer.expiresAtEpochMs, store.reconcileRules(now).single().expiresAtEpochMs)
    }

    @Test fun `R-18 explicit confirmation can reactivate an inactive template without read-time replay`() = runBlocking {
        val disk = Disk(); val store = fresh(disk)
        val old = rule().copy(breakLevel = BreakLevel.EASY, schedule = Schedule(startMinuteOfDay = 9 * 60, endMinuteOfDay = 11 * 60))
        DataStoreBlockRulesStore(disk).upsert(old); store.reconcile(clock())
        val now = clock(hour)
        assertFalse(blocked(store.reconcileRules(now), now))
        val extended = old.copy(schedule = old.schedule!!.copy(endMinuteOfDay = 12 * 60))
        val preview = store.request(EditorChange("Extend", upserts = listOf(extended)), now, EditorSafety(true)) as EditorCommitResult.Preview
        assertEquals(EditorCommitResult.Applied(), store.confirm(preview.pending.id, now, EditorSafety(true)))
        assertTrue(blocked(store.reconcileRules(now), now))
        assertEquals(hour, state(store).causes.getValue(ruleCauseKey(old.id)).interval!!.remainingMs(now))
    }

    @Test fun `R-18 clock display rekey preserves a current Harder wait without weakening its exact revision check`() = runBlocking {
        val disk = Disk(); val store = fresh(disk); val timer = timer(BreakLevel.HARDER)
        assertTrue(store.startTimer(timer, clock()))
        val wait = store.request(EditorChange("End", deleteIds = setOf(timer.id), timerExit = true), clock(), EditorSafety(true)) as EditorCommitResult.Waiting
        val started = readHarderWait(disk.values.value).pending!!.startedAt
        val now = clock(1_000, 8 * hour)
        store.guardSessionClock(now, shift(now))
        assertEquals(started, readHarderWait(disk.values.value).pending!!.startedAt)
        val early = clock(59_999, 8 * hour + 58_999)
        assertEquals(1L, (store.confirm(wait.pending.id, early, EditorSafety(true)) as EditorCommitResult.Waiting).remainingMs)
        val end = clock(60_000, 8 * hour + 59_000)
        assertEquals(EditorCommitResult.Applied(), store.confirm(wait.pending.id, end, EditorSafety(true)))
        assertTrue(DataStoreBlockRulesStore(disk).rules.first().isEmpty())
    }

    @Test fun `R-18 PC-57 persisted DST overlap uses earlier start and one identity across duplicate callbacks`() = runBlocking {
        val first = AccessClockStamp(Instant.parse("2026-11-01T05:20:00Z").toEpochMilli(), 1_000, "boot-a")
        val disk = Disk(); val store = DataStoreEditorCommitmentStore(disk, defaultZone = { "America/New_York" })
        store.reconcile(first)
        DataStoreBlockRulesStore(disk).upsert(rule().copy(schedule = Schedule(startMinuteOfDay = 75, endMinuteOfDay = 105)))
        repeat(2) { store.reconcile(first) }
        val original = state(store).causes.getValue(ruleCauseKey("work"))
        assertEquals(Instant.parse("2026-11-01T05:15:00Z").toEpochMilli().toString(), original.occurrenceId)
        val repeatedHour = first.copy(wallEpochMs = first.wallEpochMs + hour, elapsedRealtimeMs = first.elapsedRealtimeMs + hour)
        repeat(2) { store.reconcile(repeatedHour) }
        val retained = state(store).causes.getValue(ruleCauseKey("work"))
        assertEquals(original, retained)
        assertEquals(25 * 60_000L, retained.interval!!.remainingMs(repeatedHour))
    }

    @Test fun `R-18 session clock transaction cannot reset initialized Open-credit spend or daily occurrence`() = runBlocking {
        val disk = Disk(preferencesOf(PrefsKeys.OPEN_LIMITS to "com.target~3~HARDER"))
        val store = fresh(disk)
        disk.edit { prefs ->
            val ledger = prepareOpenCredits(prefs, clock(), "UTC")
            prefs.putOpenCredits(OpenCreditLedgerPolicy.acceptOpenCredit(ledger, "com.target", "spent", listOf(3), emptyList(), false, clock()).next)
        }
        val before = readOpenCredits(disk.values.value)
        val cause = state(store).causes.getValue(openCauseKey("com.target")).occurrenceId
        val now = clock(60_000, 8 * 24 * hour)
        store.guardSessionClock(now, shift(now))
        val after = readOpenCredits(disk.values.value)
        assertEquals(before.periodId, after.periodId)
        assertEquals(1, after.counters.getValue("com.target").used)
        assertEquals(before.counters.getValue("com.target").consumedOperationIds,
            after.counters.getValue("com.target").consumedOperationIds)
        assertEquals(before.reset.remainingToResetMs - 60_000, after.reset.remainingToResetMs)
        assertEquals(cause, state(store).causes.getValue(openCauseKey("com.target")).occurrenceId)
    }
}
