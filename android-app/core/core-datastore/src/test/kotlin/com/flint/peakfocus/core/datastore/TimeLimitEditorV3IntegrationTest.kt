package com.flint.peakfocus.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.flint.peakfocus.blocking.engine.*
import com.flint.peakfocus.core.model.*
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class TimeLimitEditorV3IntegrationTest {
    private class Disk(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        val values = MutableStateFlow(initial)
        var fail = false
        var changedWrites = 0
        private val mutex = Mutex()
        override val data: Flow<Preferences> = values
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = mutex.withLock {
            val next = transform(values.value)
            if (fail) throw IOException("synthetic failed migration commit")
            if (next != values.value) changedWrites++
            values.value = next
            next
        }
    }
    private val hour = 3_600_000L
    private val base = AccessClockStamp(Instant.parse("2026-10-28T23:00:00Z").toEpochMilli(), 10_000, "boot")
    private var now = base
    private var zone = "Asia/Tokyo"
    private val safe = EditorSafety(true)
    private fun editor(disk: Disk) = DataStoreEditorCommitmentStore(disk, { now }, { zone })
    private fun usage(disk: Disk) = DataStoreTimeLimitUsageStore(disk, { now }, { zone },
        prepareEditor = { prefs, stamp -> editor(disk).prepare(prefs, stamp, checkpointQuota = false) })
    private fun literalV2() = preferencesOf(
        EDITOR_VERSION_KEY to 2L,
        EDITOR_COMMITMENTS_KEY to "E|1\nN|1|false\nK|${base.wallEpochMs}|10000|boot\n" +
            "C|time:app|old-exact-id|UTC|false|I|${base.wallEpochMs}|10000|boot|3600000|${base.wallEpochMs + hour}",
        HARDER_WAIT_KEY to "H|1\nC|time:app|old-exact-id|2",
        PrefsKeys.BLOCK_RULES to "", PrefsKeys.APP_GROUPS to "", PrefsKeys.OPEN_LIMITS to "",
        PrefsKeys.TIME_LIMITS to "app~30~HARDER", PrefsKeys.DEFAULT_BREAK_LEVEL to "EASY",
        stringPreferencesKey("unrelated_history") to "exact retained bytes",
    )

    @Test fun PC_56_PC_70_literalV2PreservesActiveIdentityEndAndNAndUpgradesAtomicallyOnce() = runBlocking {
        val disk = Disk(literalV2()); val store = editor(disk)
        store.reconcile(now)
        assertEquals(3L, disk.values.value[EDITOR_VERSION_KEY])
        assertEquals("Asia/Tokyo", disk.values.value.editorTimes().single().zoneId)
        val day = readTimeLimitUsage(disk.values.value).targets.getValue("app")
        val state = readEditorState(disk.values.value)
        val cause = state.causes.getValue(timeCauseKey("app"))
        assertEquals("old-exact-id", cause.occurrenceId)
        assertEquals(hour, cause.interval!!.remainingMs(now))
        assertEquals("UTC", cause.zoneId)
        assertEquals("UTC", day.zoneId)
        assertEquals(hour, day.reset.remainingToResetMs)
        assertEquals(2, readHarderWait(disk.values.value).counts.getValue(editorOccurrence(timeCauseKey("app"), cause)))
        assertEquals("app~30~HARDER" to "EASY", TimeLimitUsageCodec.readArchive(disk.values.value[TIME_USAGE_ARCHIVE]!!))
        val bytes = disk.values.value
        store.reconcile(now)
        assertEquals(bytes, disk.values.value)
        val writes = disk.changedWrites
        repeat(10) {
            now = now.copy(wallEpochMs = now.wallEpochMs + 1_000, elapsedRealtimeMs = now.elapsedRealtimeMs + 1_000)
            store.reconcile(now)
        }
        assertEquals("healthy publications cannot create a checkpoint feedback loop", writes, disk.changedWrites)
        assertEquals("exact retained bytes", disk.values.value[stringPreferencesKey("unrelated_history")])
    }

    @Test fun PC_70_PC_71_v2MissingDomainAndFutureMarkerRemainUnavailableAndFailedUpgradeRollsBack() = runBlocking {
        for ((broken, failure) in listOf(
            literalV2().toMutablePreferences().apply { remove(PrefsKeys.APP_GROUPS) }.toPreferences() to StorageFailure.CORRUPT,
            literalV2().toMutablePreferences().apply { this[EDITOR_VERSION_KEY] = 4L }.toPreferences() to StorageFailure.UNSUPPORTED_VERSION)) {
            val disk = Disk(broken)
            assertEquals(StorageState.Unavailable(failure), editor(disk).state.first { it !is StorageState.Loading })
            try { editor(disk).reconcile(now); fail() } catch (_: StorageUnavailableException) { }
            assertEquals(broken, disk.values.value)
        }
        val disk = Disk(literalV2()); disk.fail = true
        try { editor(disk).reconcile(now); fail() } catch (_: IOException) { }
        assertEquals(literalV2(), disk.values.value)
        disk.fail = false
        editor(disk).reconcile(now)
        assertEquals(3L, disk.values.value[EDITOR_VERSION_KEY])
    }

    @Test fun PC_56_exactLegacyPendingWaitRebindsAndFinalConfirmationSpendsNOnlyOnce() = runBlocking {
        val raw = literalV2().toMutablePreferences()
        val old = raw.editorTimes().single()
        val before = EditorCommitmentCodec.decode(raw[EDITOR_COMMITMENTS_KEY])
        val cause = before.causes.getValue(timeCauseKey("app"))
        val occurrence = editorOccurrence(timeCauseKey("app"), cause)
        val change = EditorChange("increase budget", limits = EditorLimitsChange("app", old.copy(dailyMinutes = 60), null))
        val operation = HarderWaitPolicy.Operation(HarderWaitPolicy.OperationKind.EDIT_WEAKEN,
            mapOf(occurrence to "30:HARDER"), setOf(legacyTimeLimitEditorScope(change)))
        val expected = EditorExpected(emptyList(), old, null, mapOf(timeCauseKey("app") to cause.occurrenceId, openCauseKey("app") to "inactive"))
        raw[EDITOR_COMMITMENTS_KEY] = EditorCommitmentCodec.encode(before.copy(nextId = 8,
            pending = PendingEditorChange(7, change, expected, CommitmentPolicy.Outcome.WAIT, operation)))
        raw.putHarderWait(HarderWaitPolicy.Ledger(mapOf(occurrence to 2), HarderWaitPolicy.PendingWait(operation, base, 900_000)))
        val disk = Disk(raw.toPreferences()); val store = editor(disk)
        now = base.copy(wallEpochMs = base.wallEpochMs + 15_000, elapsedRealtimeMs = base.elapsedRealtimeMs + 15_000)
        store.reconcile(now)
        val migrated = readEditorState(disk.values.value).pending!!
        assertEquals(7L, migrated.id)
        assertEquals("Asia/Tokyo", migrated.change.limits!!.time!!.zoneId)
        assertEquals(base, readHarderWait(disk.values.value).pending!!.startedAt)
        assertEquals(2, readHarderWait(disk.values.value).counts[occurrence])
        now = base.copy(wallEpochMs = base.wallEpochMs + 900_000, elapsedRealtimeMs = base.elapsedRealtimeMs + 900_000)
        assertEquals(EditorCommitResult.Applied(false), store.confirm(7, now, safe))
        assertEquals(3, readHarderWait(disk.values.value).counts[occurrence])
        assertEquals(60, disk.values.value.editorTimes().single().dailyMinutes)
        assertTrue(store.confirm(7, now, safe) is EditorCommitResult.Rejected)
        assertEquals(3, readHarderWait(disk.values.value).counts[occurrence])
    }

    @Test fun PC_54_PC_56_queuedZoneEditAppliesAtTheTrustedOldEndBeforeTheNextTimeOccurrence() = runBlocking {
        val disk = Disk(literalV2().toMutablePreferences().apply {
            this[PrefsKeys.TIME_LIMITS] = "app~30~HARDCORE"
        }.toPreferences())
        val store = editor(disk); store.reconcile(now)
        val oldDay = readTimeLimitUsage(disk.values.value).targets.getValue("app")
        val old = disk.values.value.editorTimes().single()
        val change = EditorChange("move saved zone", limits = EditorLimitsChange("app", old.copy(dailyMinutes = 60, zoneId = "Pacific/Auckland"), null))
        val preview = store.request(change, now, safe) as EditorCommitResult.Preview
        assertEquals(CommitmentPolicy.Outcome.DEFER, preview.pending.outcome)
        assertEquals(EditorCommitResult.Applied(true), store.confirm(preview.pending.id, now, safe))
        now = base.copy(wallEpochMs = base.wallEpochMs + 7 * 86_400_000L, elapsedRealtimeMs = base.elapsedRealtimeMs + hour - 1)
        store.reconcile(now)
        assertEquals(old, disk.values.value.editorTimes().single())
        now = now.copy(elapsedRealtimeMs = base.elapsedRealtimeMs + hour)
        store.reconcile(now)
        assertTrue(readEditorState(disk.values.value).queued.isEmpty())
        val day = readTimeLimitUsage(disk.values.value).targets.getValue("app")
        assertEquals(oldDay.periodId + 1, day.periodId)
        assertEquals("Pacific/Auckland", day.zoneId)
        assertEquals("Pacific/Auckland", readEditorState(disk.values.value).causes.getValue(timeCauseKey("app")).zoneId)
        assertEquals(60, disk.values.value.editorTimes().single().dailyMinutes)
    }

    @Test fun PC_56_newTimeDraftCapturesZoneAtRequestAndRegistersItsDayAtConfirmation() = runBlocking {
        val disk = Disk(); val store = editor(disk)
        val request = store.request(EditorChange("new Time", limits = EditorLimitsChange("new", TimeLimit("new", 30), null)), now, safe)
        val pending = (request as EditorCommitResult.Preview).pending
        assertEquals("Asia/Tokyo", pending.change.limits!!.time!!.zoneId)
        zone = "Pacific/Auckland"
        assertEquals(EditorCommitResult.Applied(false), store.confirm(pending.id, now, safe))
        assertEquals("Asia/Tokyo", disk.values.value.editorTimes().single().zoneId)
        assertEquals("Asia/Tokyo", readTimeLimitUsage(disk.values.value).targets.getValue("new").zoneId)
    }

    @Test fun PC_54_PC_56_firstProductionMeasurementUsesSerializedEditorUpgradeBeforeAnyZoneRewrite() = runBlocking {
        val disk = Disk(literalV2())
        val ticket = usage(disk).measurementContext("app", now)
        assertEquals(3L, disk.values.value[EDITOR_VERSION_KEY])
        assertEquals("UTC", ticket.zoneId)
        assertEquals("Asia/Tokyo", disk.values.value.editorTimes().single().zoneId)
        val state = readEditorState(disk.values.value)
        assertEquals("old-exact-id", state.causes.getValue("time:app").occurrenceId)
        assertEquals(hour, state.causes.getValue("time:app").interval!!.remainingMs(now))
        assertEquals(2, readHarderWait(disk.values.value).counts.getValue(HarderWaitPolicy.Occurrence("time:app", "old-exact-id")))
        assertEquals("app~30~HARDER" to "EASY", TimeLimitUsageCodec.readArchive(disk.values.value[TIME_USAGE_ARCHIVE]!!))
    }

    @Test fun PC_54_PC_56_foregroundCrossingQueuedZoneBoundaryChargesOnlyTheObservedNewDaySuffix() = runBlocking {
        val disk = Disk(literalV2().toMutablePreferences().apply { this[PrefsKeys.TIME_LIMITS] = "app~30~HARDCORE" }.toPreferences())
        val store = editor(disk); store.reconcile(now)
        val oldPeriod = readTimeLimitUsage(disk.values.value).targets.getValue("app").periodId
        val old = disk.values.value.editorTimes().single()
        val preview = store.request(EditorChange("next zone", limits = EditorLimitsChange("app", old.copy(zoneId = "Pacific/Auckland"), null)), now, safe) as EditorCommitResult.Preview
        assertEquals(EditorCommitResult.Applied(true), store.confirm(preview.pending.id, now, safe))
        now = base.copy(wallEpochMs = base.wallEpochMs + hour - 15_000, elapsedRealtimeMs = base.elapsedRealtimeMs + hour - 15_000)
        val observer = usage(disk)
        observer.observeForeground(TimeLimitForegroundObservation("owner", null, "visit", "app", now))
        now = base.copy(wallEpochMs = base.wallEpochMs + hour + 5_000, elapsedRealtimeMs = base.elapsedRealtimeMs + hour + 5_000)
        observer.observeForeground(TimeLimitForegroundObservation("owner", "visit", "visit", "app", now))
        val row = readTimeLimitUsage(disk.values.value).targets.getValue("app")
        assertEquals(oldPeriod + 1, row.periodId)
        assertEquals("Pacific/Auckland", row.zoneId)
        assertEquals(5_000L, row.observedUsedMs)
        assertEquals(5_000L, row.usedMs)
        assertTrue(readEditorState(disk.values.value).queued.isEmpty())
    }

    @Test fun PC_60_timeOwnedCorruptionLeavesProductionOpenAndSharedRecoveryMetadataReady() = runBlocking {
        val disk = Disk(literalV2()); editor(disk).reconcile(now)
        disk.updateData { it.toMutablePreferences().apply { this[TIME_USAGE_STATE] = "corrupt" }.toPreferences() }
        val limits = EditorMigratingLimitsStore(disk, { now })
        assertEquals(StorageState.Unavailable(StorageFailure.CORRUPT), limits.timeLimitsState.first { it !is StorageState.Loading })
        assertEquals(StorageState.Ready(emptyList<OpenLimit>()), limits.openLimitsState.first { it !is StorageState.Loading })
        assertTrue(editor(disk).state.first { it !is StorageState.Loading } is StorageState.Ready)
        val shared = DataStoreProtectionTransactionStore(disk, { now }, { zone })
        assertTrue(shared.transact { ProtectionMutation(it.focus, true) } is ProtectionCommit.Committed)
        assertTrue(shared.state.first { it !is StorageState.Loading } is StorageState.Ready)
        assertTrue(editor(disk).request(EditorChange("change Time", limits = EditorLimitsChange("app", disk.values.value.editorTimes().single().copy(dailyMinutes = 60), null)), now, safe) is EditorCommitResult.Rejected)
    }

    @Test fun PC_56_productionLimitMutatorsCaptureAndRetainTheRegisteredUsedFloor() = runBlocking {
        val disk = Disk(); val limits = EditorMigratingLimitsStore(disk, { now })
        limits.setTimeLimit(TimeLimit("app", 30))
        val originalZone = disk.values.value.editorTimes().single().zoneId
        assertNotNull(originalZone)
        val observer = usage(disk)
        val ticket = observer.measurementContext("app", now)
        observer.record(ticket, 25 * 60_000L, now)
        limits.clearTimeLimit("app")
        limits.setLimits("app", TimeLimit("app", 30), OpenLimit("app", 2))
        assertEquals(originalZone, disk.values.value.editorTimes().single().zoneId)
        assertEquals(25 * 60_000L, readTimeLimitUsage(disk.values.value).targets.getValue("app").usedMs)
        assertEquals(2, disk.values.value.editorOpens().single().dailyOpens)
    }

    @Test fun PC_54_independentEditorRolloverPreservesTheContinuouslyObservedNewDaySuffix() = runBlocking {
        val disk = Disk(literalV2()); val store = editor(disk); store.reconcile(now)
        now = base.copy(wallEpochMs = base.wallEpochMs + hour - 15_000, elapsedRealtimeMs = base.elapsedRealtimeMs + hour - 15_000)
        val observer = usage(disk)
        observer.observeForeground(TimeLimitForegroundObservation("owner", null, "visit", "app", now))
        now = base.copy(wallEpochMs = base.wallEpochMs + hour + 3_000, elapsedRealtimeMs = base.elapsedRealtimeMs + hour + 3_000)
        store.reconcile(now)
        now = now.copy(wallEpochMs = now.wallEpochMs + 2_000, elapsedRealtimeMs = now.elapsedRealtimeMs + 2_000)
        observer.observeForeground(TimeLimitForegroundObservation("owner", "visit", "visit", "app", now))
        assertEquals(5_000L, readTimeLimitUsage(disk.values.value).targets.getValue("app").usedMs)
    }

    @Test fun PC_54_queuedFloorDepartureBeforeAnIndependentRollCannotChargeTheNewDayOrKeepItsSpan() = runBlocking {
        val disk = Disk(literalV2()); val store = editor(disk); store.reconcile(now)
        now = base.copy(wallEpochMs = base.wallEpochMs + hour - 15_000, elapsedRealtimeMs = base.elapsedRealtimeMs + hour - 15_000)
        val observer = usage(disk)
        observer.observeForeground(TimeLimitForegroundObservation("owner", null, "visit", "app", now))
        val departed = base.copy(wallEpochMs = base.wallEpochMs + hour - 1_000, elapsedRealtimeMs = base.elapsedRealtimeMs + hour - 1_000)
        now = base.copy(wallEpochMs = base.wallEpochMs + hour + 5_000, elapsedRealtimeMs = base.elapsedRealtimeMs + hour + 5_000)
        store.reconcile(now)
        observer.observeForeground(TimeLimitForegroundObservation("owner", "visit", null, null, departed))
        val ledger = readTimeLimitUsage(disk.values.value)
        assertNull(ledger.foreground)
        assertEquals(0L, ledger.targets.getValue("app").usedMs)
        now = now.copy(wallEpochMs = now.wallEpochMs + 15_000, elapsedRealtimeMs = now.elapsedRealtimeMs + 15_000)
        observer.observeForeground(TimeLimitForegroundObservation("owner", null, "new-visit", "app", now))
        assertEquals(0L, readTimeLimitUsage(disk.values.value).targets.getValue("app").usedMs)
    }

    @Test fun PC_54_delayedPreBoundaryObservationCannotStartANewPeriodSpanBeforeItsBoundary() = runBlocking {
        val disk = Disk(literalV2()); editor(disk).reconcile(now)
        now = base.copy(wallEpochMs = base.wallEpochMs + hour - 15_000, elapsedRealtimeMs = base.elapsedRealtimeMs + hour - 15_000)
        val observer = usage(disk)
        observer.observeForeground(TimeLimitForegroundObservation("owner", null, "visit", "app", now))
        val captured = base.copy(wallEpochMs = base.wallEpochMs + hour - 1_000, elapsedRealtimeMs = base.elapsedRealtimeMs + hour - 1_000)
        now = base.copy(wallEpochMs = base.wallEpochMs + hour + 60_000, elapsedRealtimeMs = base.elapsedRealtimeMs + hour + 60_000)
        observer.observeForeground(TimeLimitForegroundObservation("owner", "visit", "visit", "app", captured))
        val ledger = readTimeLimitUsage(disk.values.value)
        assertNull(ledger.foreground)
        assertEquals(0L, ledger.targets.getValue("app").usedMs)
        assertTrue(ledger.targets.getValue("app").accountingUnknownUntilReset)
        observer.observeForeground(TimeLimitForegroundObservation("owner", "visit", "visit", "app", now))
        assertEquals(0L, readTimeLimitUsage(disk.values.value).targets.getValue("app").usedMs)
    }
}
