package com.flint.peakfocus.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.flint.peakfocus.blocking.engine.CommitmentPolicy
import com.flint.peakfocus.blocking.engine.HarderWaitPolicy
import com.flint.peakfocus.blocking.engine.NextEvaluationBoundary
import com.flint.peakfocus.core.model.*
import java.io.IOException
import java.util.Calendar
import java.util.TimeZone
import java.time.ZoneId
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class EditorCommitmentStoreTest {
    private class Disk(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        val values = MutableStateFlow(initial)
        val lock = Mutex()
        var fail = false
        var updates = 0
        var beforeUpdate: (() -> Unit)? = null
        override val data: Flow<Preferences> = values
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = lock.withLock {
            updates++
            beforeUpdate?.also { beforeUpdate = null }?.invoke()
            val next = transform(values.value)
            if (fail) throw IOException("disk full")
            values.value = next
            next
        }
    }
    private val at = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear(); set(2026, Calendar.OCTOBER, 28, 10, 0)
    }.timeInMillis
    private fun clock(ms: Long = 0, wall: Long = ms, boot: String = "boot") = AccessClockStamp(at + wall, 10_000 + ms, boot)
    private val safe = EditorSafety(true)
    private fun rule(tier: BreakLevel = BreakLevel.HARDCORE) = BlockRule("work", "Work",
        BlockTargets(apps = setOf(AppRef("a"), AppRef("b"))), Schedule(startMinuteOfDay = 9 * 60, endMinuteOfDay = 11 * 60), tier)
    private suspend fun fresh(disk: Disk): DataStoreEditorCommitmentStore = DataStoreEditorCommitmentStore(disk) { "UTC" }.also { it.reconcile(clock()) }
    private suspend fun snapshot(store: EditorCommitmentStore) = (store.state.first { it is StorageState.Ready } as StorageState.Ready).value
    private suspend fun confirm(store: EditorCommitmentStore, change: EditorChange, now: AccessClockStamp = clock()): EditorCommitResult {
        val result = store.request(change, now, safe)
        val id = when (result) {
            is EditorCommitResult.Preview -> result.pending.id
            is EditorCommitResult.Waiting -> result.pending.id
            else -> error("No preview: $result")
        }
        return store.confirm(id, now, safe)
    }

    @Test fun `PC-11 PC-14 active Hardcore weakening is durable future-only and applies at exact end before rematerializing`() = runBlocking {
        val disk = Disk(); val store = fresh(disk); val rules = DataStoreBlockRulesStore(disk)
        val old = rule(); rules.upsert(old)
        val after = old.copy(targets = BlockTargets(apps = setOf(AppRef("a"))))
        assertEquals(EditorCommitResult.Applied(true), confirm(store, EditorChange("Weaken", upserts = listOf(after))))
        assertEquals(listOf(old), rules.rules.first())
        val deadline = snapshot(store).queued.single().boundaries.values.single().wallEndEpochMs
        val recreated = DataStoreEditorCommitmentStore(disk) { "UTC" }
        recreated.reconcile(clock(3_599_999))
        assertEquals(listOf(old), rules.rules.first())
        assertEquals(deadline, snapshot(recreated).queued.single().boundaries.values.single().wallEndEpochMs)
        recreated.reconcile(clock(3_600_000))
        assertEquals(listOf(after), rules.rules.first()); assertTrue(snapshot(recreated).queued.isEmpty())
        recreated.reconcile(clock(3_600_000))
        assertEquals(listOf(after), rules.rules.first())
    }

    @Test fun `PC-13 inactive Hardcore template can be deleted after ordinary confirmation`() = runBlocking {
        val disk = Disk(); val store = fresh(disk); val rules = DataStoreBlockRulesStore(disk)
        rules.upsert(rule().copy(schedule = Schedule(startMinuteOfDay = 12 * 60, endMinuteOfDay = 13 * 60)))
        val preview = store.request(EditorChange("Delete", deleteIds = setOf("work")), clock(), safe) as EditorCommitResult.Preview
        assertEquals(CommitmentPolicy.Outcome.ALLOW, preview.pending.outcome)
        assertEquals(EditorCommitResult.Applied(), store.confirm(preview.pending.id, clock(), safe))
        assertTrue(rules.rules.first().isEmpty())
    }

    @Test fun `PC-15 daily Hardcore Time Limit below budget still defers increase and removal`() = runBlocking {
        val disk = Disk(); val store = fresh(disk); val limits = DataStoreLimitsStore(disk)
        val limit = TimeLimit("a", 30, BreakLevel.HARDCORE)
        assertEquals(EditorCommitResult.Applied(), confirm(store, EditorChange("Create", limits = EditorLimitsChange("a", limit, null))))
        val preview = store.request(EditorChange("Increase", limits = EditorLimitsChange("a", limit.copy(dailyMinutes = 60), null)), clock(), safe) as EditorCommitResult.Preview
        assertEquals(CommitmentPolicy.Outcome.DEFER, preview.pending.outcome)
        assertEquals(EditorCommitResult.Applied(true), store.confirm(preview.pending.id, clock(), safe))
        assertEquals(listOf(limit), limits.timeLimits.first())
        store.reconcile(clock(14 * 3_600_000L)) // Midnight, adjacent daily occurrence.
        assertEquals(60, limits.timeLimits.first().single().dailyMinutes)
        assertFalse(snapshot(store).causes.getValue(timeCauseKey("a")).legacyRights)
    }

    @Test fun `PC-16 Open Limit remains active without a shield including during a credit`() = runBlocking {
        val disk = Disk(); val store = fresh(disk)
        confirm(store, EditorChange("Create", limits = EditorLimitsChange("a", null, OpenLimit("a", 3, BreakLevel.HARDCORE))))
        val preview = store.request(EditorChange("Remove", limits = EditorLimitsChange("a", null, null)), clock(), safe) as EditorCommitResult.Preview
        assertEquals(CommitmentPolicy.Outcome.DEFER, preview.pending.outcome)
    }

    @Test fun `PC-17 PC-21 Harder Timer wait survives recreation and needs final confirmation`() = runBlocking {
        val disk = Disk(); val store = fresh(disk); val rules = DataStoreBlockRulesStore(disk)
        val timer = rule(BreakLevel.HARDER).copy(id = CONTRACT_TIMER_ID_PREFIX + "new", schedule = null, expiresAtEpochMs = at + 300_000)
        assertTrue(store.startTimer(timer, clock()))
        val wait = store.request(EditorChange("End", deleteIds = setOf(timer.id), timerExit = true), clock(), safe) as EditorCommitResult.Waiting
        assertEquals(60_000, wait.remainingMs)
        val recreated = DataStoreEditorCommitmentStore(disk) { "UTC" }
        assertEquals(1, (recreated.confirm(wait.pending.id, clock(59_999), safe) as EditorCommitResult.Waiting).remainingMs)
        recreated.reconcile(clock(60_000)); assertEquals(listOf(timer), rules.rules.first())
        assertEquals(EditorCommitResult.Applied(), recreated.confirm(wait.pending.id, clock(60_000), safe))
        assertTrue(rules.rules.first().isEmpty())
        assertTrue(recreated.confirm(wait.pending.id, clock(60_000), safe) is EditorCommitResult.Rejected)
        assertEquals(listOf(1), HarderWaitCodec.decode(disk.values.value[HARDER_WAIT_KEY]).counts.values.toList())
    }

    @Test fun `PC-67 PC-68 legacy Hardcore Timer retains exit while new Hardcore denies it`() = runBlocking {
        val legacy = rule().copy(id = NATIVE_TIMER_ID_PREFIX + "old", schedule = null, expiresAtEpochMs = at + 300_000)
        val disk = Disk(preferencesOf(PrefsKeys.BLOCK_RULES to PrefsCodec.encodeRules(listOf(legacy))))
        val store = fresh(disk)
        assertEquals(EditorCommitResult.Applied(), confirm(store, EditorChange("End legacy", deleteIds = setOf(legacy.id), timerExit = true)))
        val strict = legacy.copy(id = CONTRACT_TIMER_ID_PREFIX + "new")
        assertTrue(store.startTimer(strict, clock()))
        assertFalse(DataStoreBlockRulesStore(disk).endTimerSession(strict, at))
        assertTrue(store.request(EditorChange("End new", deleteIds = setOf(strict.id), timerExit = true), clock(), safe) is EditorCommitResult.Rejected)
        assertEquals(listOf(strict), DataStoreBlockRulesStore(disk).rules.first())
    }

    @Test fun `PC-10 exact preview rejects a concurrent policy upgrade without spending wait`() = runBlocking {
        val disk = Disk(); val store = fresh(disk); val rules = DataStoreBlockRulesStore(disk)
        val old = rule(BreakLevel.EASY); rules.upsert(old)
        val preview = store.request(EditorChange("Delete", deleteIds = setOf(old.id)), clock(), safe) as EditorCommitResult.Preview
        rules.upsert(old.copy(breakLevel = BreakLevel.HARDCORE))
        assertTrue(store.confirm(preview.pending.id, clock(), safe) is EditorCommitResult.Rejected)
        assertEquals(BreakLevel.HARDCORE, rules.rules.first().single().breakLevel)
    }

    @Test fun `PC-18 counts are shared with breaks and are not reset by a rename`() = runBlocking {
        val disk = Disk(); val store = fresh(disk); val rules = DataStoreBlockRulesStore(disk)
        val old = rule(BreakLevel.HARDER); rules.upsert(old); store.reconcile(clock())
        val occurrence = editorOccurrence(ruleCauseKey(old.id), snapshot(store).causes.getValue(ruleCauseKey(old.id)))
        DataStoreHarderWaitStore(disk).transact { HarderWaitMutation(it.copy(counts = mapOf(occurrence to 2)), Unit) }
        confirm(store, EditorChange("Rename", upserts = listOf(old.copy(name = "Renamed"))))
        val wait = store.request(EditorChange("Off", upserts = listOf(old.copy(name = "Renamed", enabled = false))), clock(), safe) as EditorCommitResult.Waiting
        assertEquals(900_000, wait.remainingMs)
        assertEquals(EditorCommitResult.Applied(), store.confirm(wait.pending.id, clock(900_000), safe))
        assertEquals(3, HarderWaitCodec.decode(disk.values.value[HARDER_WAIT_KEY]).counts[occurrence])
    }

    @Test fun `PC-18 PC-54 reboot preserves daily occurrence and Harder n until trusted quota reset`() = runBlocking {
        val disk = Disk(); val store = fresh(disk)
        confirm(store, EditorChange("Create", limits = EditorLimitsChange("a", TimeLimit("a", 30, BreakLevel.HARDER), null)))
        val key = timeCauseKey("a")
        val original = snapshot(store).causes.getValue(key)
        val occurrence = editorOccurrence(key, original)
        DataStoreHarderWaitStore(disk).transact { HarderWaitMutation(it.copy(counts = mapOf(occurrence to 2)), Unit) }
        val reboot = clock(5, 3 * 86_400_000L, "reboot")
        store.reconcile(reboot)
        val resumed = snapshot(store).causes.getValue(key)
        assertEquals(original.occurrenceId, resumed.occurrenceId)
        assertEquals(14 * 3_600_000L, resumed.interval!!.remainingMs(reboot))
        assertEquals(2, readHarderWait(disk.values.value).counts[occurrence])
        store.reconcile(clock(5 + 14 * 3_600_000L, 3 * 86_400_000L + 14 * 3_600_000L, "reboot"))
        assertNotEquals(original.occurrenceId, snapshot(store).causes.getValue(key).occurrenceId)
        assertFalse(readHarderWait(disk.values.value).counts.containsKey(occurrence))
    }

    @Test fun `PC-14 PC-54 reboot cannot prematurely apply a queued daily weakening`() = runBlocking {
        val disk = Disk(); val store = fresh(disk)
        val limit = TimeLimit("a", 30, BreakLevel.HARDCORE)
        confirm(store, EditorChange("Create", limits = EditorLimitsChange("a", limit, null)))
        assertEquals(EditorCommitResult.Applied(true), confirm(store, EditorChange("Remove", limits = EditorLimitsChange("a", null, null))))
        val id = snapshot(store).queued.single().id
        store.reconcile(clock(5, 3 * 86_400_000L, "reboot"))
        assertEquals(listOf(limit), DataStoreLimitsStore(disk).timeLimits.first())
        assertEquals(id, snapshot(store).queued.single().id)
        store.reconcile(clock(5 + 14 * 3_600_000L, 3 * 86_400_000L + 14 * 3_600_000L, "reboot"))
        assertTrue(DataStoreLimitsStore(disk).timeLimits.first().isEmpty())
        assertTrue(snapshot(store).queued.isEmpty())
    }

    @Test fun `PC-70 adopting an existing C4c daily cause retains its identity and n across reboot`() = runBlocking {
        val key = timeCauseKey("a")
        val cause = EditorCause("legacy-daily-identity", com.flint.peakfocus.blocking.engine.AnchoredInterval(clock(), 14 * 3_600_000L, at + 14 * 3_600_000L), "UTC")
        val occurrence = editorOccurrence(key, cause)
        val disk = Disk(preferencesOf(PrefsKeys.TIME_LIMITS to "a~30~HARDER",
            EDITOR_COMMITMENTS_KEY to EditorCommitmentCodec.encode(EditorCommitmentState(causes = mapOf(key to cause), timeline = clock())),
            EDITOR_VERSION_KEY to 1L, HARDER_WAIT_KEY to HarderWaitCodec.encode(HarderWaitPolicy.Ledger(counts = mapOf(occurrence to 5)))))
        val store = DataStoreEditorCommitmentStore(disk) { "UTC" }
        store.reconcile(clock(5, 3 * 86_400_000L, "reboot"))
        assertEquals(cause.occurrenceId, snapshot(store).causes.getValue(key).occurrenceId)
        assertEquals(5, readHarderWait(disk.values.value).counts[occurrence])
        assertNotNull(snapshot(store).quotaPeriodId)
    }

    @Test fun `PC-56 committing first Time Limit captures the shared zone before a later device zone change`() = runBlocking {
        var zone = "UTC"
        val disk = Disk(); val store = DataStoreEditorCommitmentStore(disk, defaultZone = { zone })
        store.reconcile(clock())
        assertNull(disk.values.value[QUOTA_ZONE])
        confirm(store, EditorChange("Time", limits = EditorLimitsChange("a", TimeLimit("a", 30), null)))
        assertEquals("UTC", disk.values.value[QUOTA_ZONE])
        zone = "Asia/Tokyo"
        confirm(store, EditorChange("Open", limits = EditorLimitsChange("b", null, OpenLimit("b", 3))))
        assertEquals("UTC", disk.values.value[QUOTA_ZONE])
        val causes = snapshot(store).causes
        assertEquals(causes.getValue(timeCauseKey("a")).occurrenceId, causes.getValue(openCauseKey("b")).occurrenceId)
        assertEquals(causes.getValue(timeCauseKey("a")).interval, causes.getValue(openCauseKey("b")).interval)
    }

    @Test fun `PC-20 cancellation spends nothing and another request restarts the full wait`() = runBlocking {
        val disk = Disk(); val store = fresh(disk); DataStoreBlockRulesStore(disk).upsert(rule(BreakLevel.HARDER))
        val change = EditorChange("Delete", deleteIds = setOf("work"))
        val first = store.request(change, clock(), safe) as EditorCommitResult.Waiting
        store.cancel(first.pending.id)
        val next = store.request(change, clock(59_000), safe) as EditorCommitResult.Waiting
        assertEquals(60_000, next.remainingMs)
        assertTrue(HarderWaitCodec.decode(disk.values.value[HARDER_WAIT_KEY]).counts.isEmpty())
    }

    @Test fun `PC-53 unresolved safety floor declines new broad allow-list with honest degraded reason`() = runBlocking {
        val disk = Disk(); val store = fresh(disk)
        val broad = rule(BreakLevel.EASY).copy(targets = BlockTargets(allowListMode = true))
        val result = store.request(EditorChange("Sleep", upserts = listOf(broad)), clock(), EditorSafety(false)) as EditorCommitResult.Rejected
        assertTrue(result.reason.contains("degraded")); assertTrue(DataStoreBlockRulesStore(disk).rules.first().isEmpty())
    }

    @Test fun `PC-52 saved safety targets are rejected while original bytes remain intact`() = runBlocking {
        val disk = Disk(); val store = fresh(disk)
        val result = store.request(EditorChange("Limit", limits = EditorLimitsChange("dialer", TimeLimit("dialer", 1), null)), clock(), EditorSafety(true, setOf("dialer")))
        assertTrue(result is EditorCommitResult.Rejected)
    }

    @Test fun `PC-54 PC-55 clock jumps cannot end commitment or extend its deferred deadline`() = runBlocking {
        val disk = Disk(); val store = fresh(disk); DataStoreBlockRulesStore(disk).upsert(rule())
        confirm(store, EditorChange("Delete", deleteIds = setOf("work")))
        val original = snapshot(store).queued.single()
        store.reconcile(clock(1_000, wall = 24 * 3_600_000))
        assertEquals(original, snapshot(store).queued.single())
        store.reconcile(clock(2_000, wall = -24 * 3_600_000))
        assertEquals(original, snapshot(store).queued.single())
        assertEquals(1, DataStoreBlockRulesStore(disk).rules.first().size)
    }

    @Test fun `PC-70 tierless literal v1 import snapshots default once and preserves archive`() = runBlocking {
        val disk = Disk(preferencesOf(PrefsKeys.TIME_LIMITS to "a~30\nb~0", PrefsKeys.DEFAULT_BREAK_LEVEL to "HARDCORE"))
        val store = fresh(disk)
        assertEquals(listOf(BreakLevel.HARDCORE, BreakLevel.HARDCORE), DataStoreLimitsStore(disk).timeLimits.first().map { it.breakLevel })
        assertEquals("a~30\nb~0", disk.values.value[stringPreferencesKey("editor_migration_time_limits_v1")])
        DataStoreFocusStateStore(disk).setDefaultBreakLevel(BreakLevel.EASY)
        DataStoreEditorCommitmentStore(disk).reconcile(clock())
        assertEquals(BreakLevel.HARDCORE, DataStoreLimitsStore(disk).timeLimits.first().first().breakLevel)
        assertEquals(EditorCommitmentCodec.decode(EditorCommitmentCodec.encode(snapshot(store))), snapshot(store))
    }

    @Test fun `PC-70 failed migration or queued write keeps original readable and no success`() = runBlocking {
        val raw = "a~30"; val disk = Disk(preferencesOf(PrefsKeys.TIME_LIMITS to raw, PrefsKeys.DEFAULT_BREAK_LEVEL to "HARDER"))
        disk.fail = true
        try { fresh(disk); fail("accepted failed write") } catch (_: IOException) { }
        assertEquals(raw, disk.values.value[PrefsKeys.TIME_LIMITS]); assertNull(disk.values.value[EDITOR_COMMITMENTS_KEY])
        disk.fail = false; val store = fresh(disk)
        val change = EditorChange("New", upserts = listOf(rule(BreakLevel.EASY)))
        val preview = store.request(change, clock(), safe) as EditorCommitResult.Preview
        disk.fail = true
        try { store.confirm(preview.pending.id, clock(), safe); fail("accepted failed write") } catch (_: IOException) { }
        assertTrue(DataStoreBlockRulesStore(disk).rules.first().isEmpty()); assertNotNull(snapshot(store).pending)
    }

    @Test fun `PC-71 unknown editor schema and missing initialized wait state never become fresh policy`() = runBlocking {
        val disk = Disk(); val store = fresh(disk)
        disk.values.value = disk.values.value.toMutablePreferences().apply { this[EDITOR_COMMITMENTS_KEY] = "E|2" }
        assertEquals(StorageState.Unavailable(StorageFailure.UNSUPPORTED_VERSION), store.state.first { it is StorageState.Unavailable })
        try { store.reconcile(clock()); fail("unknown schema accepted") } catch (_: StorageUnavailableException) { }
        val other = Disk(); val initialized = fresh(other)
        other.values.value = other.values.value.toMutablePreferences().apply { remove(HARDER_WAIT_KEY) }
        try { initialized.reconcile(clock()); fail("missing wait state accepted") } catch (_: StorageUnavailableException) { }
    }

    @Test fun `PC-01 bounds reject new finite sessions over 24 hours and open-ended Hardcore`() = runBlocking {
        val disk = Disk(); val store = fresh(disk)
        val open = rule().copy(schedule = null)
        assertTrue(store.request(EditorChange("New", upserts = listOf(open)), clock(), safe) is EditorCommitResult.Rejected)
        val long = open.copy(expiresAtEpochMs = at + 24 * 3_600_000L + 1)
        assertTrue(store.request(EditorChange("New", upserts = listOf(long)), clock(), safe) is EditorCommitResult.Rejected)
    }

    @Test fun `PC-10 commit uses the clock inside the transaction after a schedule starts`() = runBlocking {
        val disk = Disk(); fresh(disk)
        val old = rule().copy(schedule = Schedule(startMinuteOfDay = 11 * 60, endMinuteOfDay = 12 * 60))
        DataStoreBlockRulesStore(disk).upsert(old)
        var liveClock = clock()
        val store = DataStoreEditorCommitmentStore(disk, clockAtCommit = { liveClock }) { "UTC" }
        val beforeStart = store.request(EditorChange("Delete", deleteIds = setOf(old.id)), clock(), safe) as EditorCommitResult.Preview
        assertEquals(CommitmentPolicy.Outcome.ALLOW, beforeStart.pending.outcome)
        liveClock = clock(3_600_000)
        // Caller still holds the old clock and preview; current occurrence wins at commit.
        assertTrue(store.confirm(beforeStart.pending.id, clock(), safe) is EditorCommitResult.Rejected)
        val active = store.request(EditorChange("Delete", deleteIds = setOf(old.id)), clock(), safe) as EditorCommitResult.Preview
        assertEquals(CommitmentPolicy.Outcome.DEFER, active.pending.outcome)
        assertEquals(listOf(old), DataStoreBlockRulesStore(disk).rules.first())
    }

    @Test fun `PC-70 first production limit read durably snapshots default before emitting a tier`() = runBlocking {
        val disk = Disk(preferencesOf(PrefsKeys.TIME_LIMITS to "a~30", PrefsKeys.DEFAULT_BREAK_LEVEL to "HARDER"))
        val store = EditorMigratingLimitsStore(disk) { clock() }
        assertEquals(BreakLevel.HARDER, store.timeLimits.first().single().breakLevel)
        assertEquals("a~30~HARDER", disk.values.value[PrefsKeys.TIME_LIMITS])
        assertNotNull(disk.values.value[EDITOR_COMMITMENTS_KEY])
        DataStoreFocusStateStore(disk).setDefaultBreakLevel(BreakLevel.EASY)
        assertEquals(BreakLevel.HARDER, store.timeLimits.first().single().breakLevel)
    }

    @Test fun `PC-54 PC-64 a shifted display deadline cannot rematerialize an elapsed Timer`() = runBlocking {
        val disk = Disk(); val store = fresh(disk); val rules = DataStoreBlockRulesStore(disk)
        val timer = rule().copy(id = CONTRACT_TIMER_ID_PREFIX + "clock", schedule = null, expiresAtEpochMs = at + 300_000)
        store.startTimer(timer, clock())
        // The existing time-change guard rewrites the raw wall estimate after a forward jump.
        rules.upsert(timer.copy(expiresAtEpochMs = timer.expiresAtEpochMs!! + 3_600_000))
        val actualEnd = clock(300_000, wall = 3_900_000)
        repeat(2) {
            val effective = store.reconcileRules(actualEnd).single()
            assertTrue(effective.expiresAtEpochMs!! <= actualEnd.wallEpochMs)
            assertEquals(0, snapshot(store).causes.getValue(ruleCauseKey(timer.id)).interval!!.remainingMs(actualEnd))
        }
    }

    @Test fun `PC-12 confirmed schedule extension arms the longer snapshot before the old boundary`() = runBlocking {
        val disk = Disk(); val store = fresh(disk); val rules = DataStoreBlockRulesStore(disk)
        val old = rule(); rules.upsert(old)
        val longer = old.copy(schedule = old.schedule!!.copy(endMinuteOfDay = 12 * 60))
        assertEquals(EditorCommitResult.Applied(), confirm(store, EditorChange("Extend", upserts = listOf(longer))))
        val effective = store.reconcileRules(clock()).single()
        assertEquals(longer.schedule, effective.schedule)
        assertEquals(2 * 3_600_000L, snapshot(store).causes.getValue(ruleCauseKey(old.id)).interval!!.remainingMs(clock()))
    }

    @Test fun `PC-10 PC-14 stale queued changes keep their draft and never delete another revision`() = runBlocking {
        val disk = Disk(); val store = fresh(disk); val rules = DataStoreBlockRulesStore(disk)
        val old = rule(); rules.upsert(old)
        confirm(store, EditorChange("Delete", deleteIds = setOf(old.id)))
        val changed = old.copy(name = "Changed elsewhere")
        rules.upsert(changed)
        store.reconcile(clock(3_600_000))
        assertEquals(listOf(changed), rules.rules.first())
        assertTrue(snapshot(store).queued.single().needsReview)
        assertEquals(setOf(old.id), snapshot(store).queued.single().change.deleteIds)
        val recreated = DataStoreEditorCommitmentStore(disk) { "UTC" }
        recreated.reconcile(clock(3_600_001))
        assertTrue(snapshot(recreated).queued.single().needsReview)
        assertEquals(listOf(changed), rules.rules.first())
        assertEquals(listOf(changed), recreated.reconcileRules(clock(3_600_001)))
    }

    @Test fun `PC-12 published unqueued schedules retain their next start boundary`() = runBlocking {
        val disk = Disk(); val store = fresh(disk)
        val future = rule().copy(schedule = Schedule(startMinuteOfDay = 12 * 60, endMinuteOfDay = 13 * 60))
        DataStoreBlockRulesStore(disk).upsert(future)
        val published = store.reconcileRules(clock())
        assertEquals(listOf(future), published)
        assertEquals(at + 2 * 3_600_000, NextEvaluationBoundary.next(at, ZoneId.of("UTC"), "a", published))
    }

    @Test fun `PC-12 confirmed Harder shortening replaces the current anchored end after its wait`() = runBlocking {
        val disk = Disk(); val store = fresh(disk); val old = rule(BreakLevel.HARDER)
        DataStoreBlockRulesStore(disk).upsert(old)
        val shorter = old.copy(schedule = old.schedule!!.copy(endMinuteOfDay = 10 * 60 + 30))
        val waiting = store.request(EditorChange("Shorten", upserts = listOf(shorter)), clock(), safe) as EditorCommitResult.Waiting
        assertEquals(60_000L, waiting.remainingMs)
        assertEquals(EditorCommitResult.Applied(), store.confirm(waiting.pending.id, clock(60_000), safe))
        assertEquals(shorter.schedule, store.reconcileRules(clock(60_000)).single().schedule)
        assertEquals(29 * 60_000L, snapshot(store).causes.getValue(ruleCauseKey(old.id)).interval!!.remainingMs(clock(60_000)))
    }

    @Test fun `PC-52 safety apps may be explicitly allowed by Sleep allow-list protection`() = runBlocking {
        val disk = Disk(); val store = fresh(disk)
        val allowed = rule().copy(targets = BlockTargets(allowListMode = true, apps = setOf(AppRef("com.flint.peakfocus"))))
        assertEquals(EditorCommitResult.Applied(), confirm(store, EditorChange("Sleep", upserts = listOf(allowed))))
        assertEquals(listOf(allowed), DataStoreBlockRulesStore(disk).rules.first())
    }

    @Test fun `PC-10 toggles derive the complete latest record inside the mutating transaction`() = runBlocking {
        val disk = Disk(); val store = fresh(disk); val old = rule(BreakLevel.EASY)
        DataStoreBlockRulesStore(disk).upsert(old)
        val newer = old.copy(name = "Concurrent save", targets = BlockTargets(apps = setOf(AppRef("c"))))
        disk.beforeUpdate = { disk.values.value = disk.values.value.toMutablePreferences().apply { putEditorRules(listOf(newer)) } }
        val preview = store.requestEnabled(setOf(old.id), false, "Turn off", clock(), safe) as EditorCommitResult.Preview
        assertEquals("Turn off Concurrent save", preview.pending.change.label)
        assertEquals(listOf(newer), preview.pending.expected.rules)
        assertEquals(EditorCommitResult.Applied(), store.confirm(preview.pending.id, clock(), safe))
        assertEquals(listOf(newer.copy(enabled = false)), DataStoreBlockRulesStore(disk).rules.first())
    }

    @Test fun `PC-70 established limit collectors validate without another migration write`() = runBlocking {
        val disk = Disk(); fresh(disk)
        val before = disk.updates
        val limits = EditorMigratingLimitsStore(disk) { clock() }
        limits.timeLimits.first(); limits.openLimits.first(); limits.timeLimits.first()
        assertEquals(before, disk.updates)
    }
}
