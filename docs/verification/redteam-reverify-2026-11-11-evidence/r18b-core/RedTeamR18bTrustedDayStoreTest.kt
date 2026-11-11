package com.flint.peakfocus.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.flint.peakfocus.blocking.engine.CommitmentPolicy
import com.flint.peakfocus.blocking.engine.OpenCreditPolicy
import com.flint.peakfocus.blocking.engine.TimeLimitDayAccounting
import com.flint.peakfocus.blocking.engine.TimeLimitDayState
import com.flint.peakfocus.core.model.*
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

/**
 * R-18b through the real durable store: an exhausted Hardcore/Harder daily Time Limit must stay
 * exhausted until its *trusted* reset, whatever the wall clock, device zone, reboot, process
 * recreation or (zero) OS measurement says. ADR-012 §9, PC-54/55/56/58/60.
 *
 * Wall clock and zone are pinned; no host-time-zone dependency.
 */
class RedTeamR18bTrustedDayStoreTest {
    private class Disk(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        val values = MutableStateFlow(initial)
        private val lock = Mutex()
        override val data: Flow<Preferences> = values
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            lock.withLock { transform(values.value).also { values.value = it } }
    }

    private val hour = 3_600_000L
    private val day = 86_400_000L
    private val base = Instant.parse("2026-10-28T23:00:00Z").toEpochMilli()
    private var now = AccessClockStamp(base, 10_000, "boot")
    private var zone = "UTC"
    private val safe = EditorSafety(true)

    private fun boot(elapsed: Long, wall: Long = base + elapsed) = AccessClockStamp(wall, 10_000 + elapsed, "boot")
    private fun usage(disk: Disk, prepare: Boolean = true) = DataStoreTimeLimitUsageStore(disk, { now }, { zone },
        prepareEditor = if (!prepare) null else { prefs, stamp ->
            DataStoreEditorCommitmentStore(disk, { now }, { zone }).prepare(prefs, stamp, checkpointQuota = false)
        })
    private fun editor(disk: Disk) = DataStoreEditorCommitmentStore(disk, { now }, { zone })
    private fun legacy() = preferencesOf(
        PrefsKeys.TIME_LIMITS to "app~1~HARDCORE\nother~30~HARDER",
        PrefsKeys.DEFAULT_BREAK_LEVEL to "HARDCORE")
    private fun row(disk: Disk, pkg: String = "app") = readTimeLimitUsage(disk.values.value).targets.getValue(pkg)
    private fun remaining(state: TimeLimitDayState, minutes: Int) = TimeLimitDayAccounting.remainingMs(state, minutes)
    private fun liveReset(state: TimeLimitDayState, at: AccessClockStamp) =
        OpenCreditPolicy().remainingToResetMs(state.reset, at)

    /** (a) Process recreation after exhaustion, then a forged midnight with a zero new-day OS sample. */
    @Test fun R18b_processRecreationCannotLoseTheFloorAcrossAForgedMidnight() = runBlocking {
        val disk = Disk(legacy())
        val first = usage(disk, prepare = false)
        val ticket = first.measurementContext("app", now)
        assertTrue(first.record(ticket, 60_000, now).sampleAccepted)
        val exhausted = row(disk)
        assertEquals(0L, remaining(exhausted, 1))

        now = boot(1_000, base + 48 * hour)
        val recreated = usage(disk, prepare = false)
        val after = recreated.measurementContext("app", now)
        assertEquals(ticket.periodId, after.periodId)
        assertEquals("UTC", after.zoneId)
        // The OS window is the trusted logical day, not the raw day the wall clock now shows.
        assertEquals(base - 23 * hour, after.windowStartEpochMs)
        assertTrue(after.windowStartEpochMs < now.wallEpochMs)
        val zero = recreated.record(after, 0, now)
        assertTrue("a zero sample is measurement evidence, not a refill", zero.sampleAccepted)
        assertEquals(60_000L, zero.state.usedMs)
        assertEquals(0L, remaining(zero.state, 1))
        assertEquals(hour - 1_000, liveReset(zero.state, now))
    }

    /** (b) Reboot (no cross-boot elapsed) plus a wall leap: the floor and the reset bound survive. */
    @Test fun R18b_rebootPlusWallLeapKeepsTheFloorAndResumesTheSavedReset() = runBlocking {
        val disk = Disk(legacy())
        val first = usage(disk, prepare = false)
        val ticket = first.measurementContext("app", now)
        first.record(ticket, 60_000, now)

        now = AccessClockStamp(base + 7 * day, 5, "boot-2")
        val resumed = usage(disk, prepare = false)
        val after = resumed.measurementContext("app", now)
        assertEquals(ticket.periodId, after.periodId)
        val zero = resumed.record(after, 0, now)
        assertEquals(60_000L, zero.state.usedMs)
        assertEquals(0L, remaining(zero.state, 1))
        assertEquals(hour, zero.state.reset.remainingToResetMs)
        assertTrue(zero.state.timeUncertain)
        // Nothing releases until that saved remainder of *observed* time elapses.
        now = AccessClockStamp(base + 8 * day - 1_000, 5 + hour - 1, "boot-2")
        val nearly = usage(disk, prepare = false).measurementContext("app", now)
        assertEquals(ticket.periodId, nearly.periodId)
        assertEquals(60_000L, row(disk).usedMs)
        now = AccessClockStamp(base + 8 * day, 5 + hour, "boot-2")
        val boundary = usage(disk, prepare = false).measurementContext("app", now)
        assertEquals(ticket.periodId + 1, boundary.periodId)
        assertEquals(0L, row(disk).usedMs)
    }

    /** (c) A device-zone change is display only: the saved zone, window and floor do not move. */
    @Test fun R18b_deviceZoneChangeCannotRecaptureTheDayOrRefund() = runBlocking {
        val disk = Disk(legacy())
        val first = usage(disk, prepare = false)
        val ticket = first.measurementContext("app", now)
        first.record(ticket, 60_000, now)
        val savedWindow = ticket.windowStartEpochMs
        for (travel in listOf("Pacific/Auckland", "Asia/Tokyo", "America/Los_Angeles")) {
            zone = travel
            now = boot(1_000)
            val after = usage(disk, prepare = false)
            val context = after.measurementContext("app", now)
            assertEquals(travel, "UTC", context.zoneId)
            assertEquals(travel, savedWindow, context.windowStartEpochMs)
            assertEquals(travel, ticket.periodId, context.periodId)
            val zero = after.record(context, 0, now)
            assertEquals(travel, 60_000L, zero.state.usedMs)
            assertEquals(travel, 0L, remaining(zero.state, 1))
            assertEquals(travel, hour - 1_000, liveReset(zero.state, now))
        }
    }

    /** (d) Re-zoning an active Hardcore limit is a weakening edit: deferred, and never a reset. */
    @Test fun R18b_rezoningAnExhaustedHardcoreLimitIsDeferredAndKeepsTheUsedFloor() = runBlocking {
        val disk = Disk()
        val store = editor(disk)
        val created = store.request(EditorChange("Create", limits =
            EditorLimitsChange("app", TimeLimit("app", 1, BreakLevel.HARDCORE), null)), now, safe)
        val pendingCreate = (created as EditorCommitResult.Preview).pending
        assertEquals(EditorCommitResult.Applied(false), store.confirm(pendingCreate.id, now, safe))
        val capturedZone = disk.values.value.editorTimes().single().zoneId
        assertEquals("UTC", capturedZone)

        val judge = usage(disk)
        val ticket = judge.measurementContext("app", now)
        assertTrue(judge.record(ticket, 60_000, now).sampleAccepted)
        val exhausted = row(disk)
        assertEquals(0L, remaining(exhausted, 1))

        val edit = EditorChange("Move zone", limits =
            EditorLimitsChange("app", TimeLimit("app", 1, BreakLevel.HARDCORE, "Pacific/Auckland"), null))
        val preview = store.request(edit, now, safe) as EditorCommitResult.Preview
        assertEquals("a zone edit is weakening (ADR-012 §2/§9)", CommitmentPolicy.Outcome.DEFER, preview.pending.outcome)
        assertEquals(EditorCommitResult.Applied(true), store.confirm(preview.pending.id, now, safe))
        // Template, saved zone, period and floor are all untouched until the trusted boundary.
        assertEquals("UTC", disk.values.value.editorTimes().single().zoneId)
        assertEquals("UTC", row(disk).zoneId)
        assertEquals(ticket.periodId, row(disk).periodId)
        assertEquals(60_000L, row(disk).usedMs)
        assertEquals(0L, remaining(row(disk), 1))

        // A forged midnight with a zero new-day OS sample still cannot release it.
        now = boot(1_000, base + 48 * hour)
        val jumped = judge.measurementContext("app", now)
        assertEquals(ticket.periodId, jumped.periodId)
        assertEquals("UTC", jumped.zoneId)
        assertEquals(60_000L, judge.record(jumped, 0, now).state.usedMs)
        assertEquals(0L, remaining(row(disk), 1))

        // A reboot whose wall clock is set far ahead must not mature the queued weakening on the
        // finite-restriction wall fallback: §9 resumes the saved *trusted* remainder.
        now = AccessClockStamp(base + 3 * day, 5, "boot-2")
        val rebooted = judge.measurementContext("app", now)
        assertEquals(ticket.periodId, rebooted.periodId)
        assertEquals("UTC", rebooted.zoneId)
        assertEquals("UTC", disk.values.value.editorTimes().single().zoneId)
        assertEquals(60_000L, judge.record(rebooted, 0, now).state.usedMs)
        assertEquals(0L, remaining(row(disk), 1))

        // The deferred edit lands at the trusted reset, and only then does the new day start.
        now = AccessClockStamp(base + 4 * day, 5 + hour, "boot-2")
        val boundary = judge.measurementContext("app", now)
        assertEquals(ticket.periodId + 1, boundary.periodId)
        assertEquals("Pacific/Auckland", boundary.zoneId)
        assertEquals("Pacific/Auckland", disk.values.value.editorTimes().single().zoneId)
        assertEquals(0L, row(disk).usedMs)
    }

    /** (e) Raising the budget while exhausted is deferred; lowering it is allowed but never refunds. */
    @Test fun R18b_budgetRaiseIsDeferredAndABudgetCutCannotRefundTheFloor() = runBlocking {
        val disk = Disk()
        val store = editor(disk)
        suspend fun create(minutes: Int) {
            val request = store.request(EditorChange("Create", limits =
                EditorLimitsChange("app", TimeLimit("app", minutes, BreakLevel.HARDCORE), null)), now, safe)
            val pending = (request as EditorCommitResult.Preview).pending
            store.confirm(pending.id, now, safe)
        }
        create(5)
        val judge = usage(disk)
        val ticket = judge.measurementContext("app", now)
        judge.record(ticket, 5 * 60_000L, now)
        assertEquals(0L, remaining(row(disk), 5))

        val raise = store.request(EditorChange("Raise", limits =
            EditorLimitsChange("app", TimeLimit("app", 30, BreakLevel.HARDCORE, "UTC"), null)), now, safe)
        assertEquals(CommitmentPolicy.Outcome.DEFER, (raise as EditorCommitResult.Preview).pending.outcome)
        assertEquals(EditorCommitResult.Applied(true), store.confirm(raise.pending.id, now, safe))
        assertEquals(5, disk.values.value.editorTimes().single().dailyMinutes)
        assertEquals(5 * 60_000L, row(disk).usedMs)
        assertEquals(0L, remaining(row(disk), 5))

        now = boot(1_000, base + 48 * hour)
        assertEquals(5, disk.values.value.editorTimes().single().dailyMinutes)
        assertEquals(0L, remaining(judge.record(judge.measurementContext("app", now), 0, now).state, 5))

        now = boot(hour, base + hour)
        judge.measurementContext("app", now)
        assertEquals(30, disk.values.value.editorTimes().single().dailyMinutes)
        assertEquals(0L, row(disk).usedMs)
    }

    @Test fun R18b_budgetCutWhileUnderQuotaAppliesImmediatelyAndKeepsTheMeasuredFloor() = runBlocking {
        val disk = Disk()
        val store = editor(disk)
        val request = store.request(EditorChange("Create", limits =
            EditorLimitsChange("app", TimeLimit("app", 30, BreakLevel.HARDCORE), null)), now, safe)
        store.confirm((request as EditorCommitResult.Preview).pending.id, now, safe)
        val judge = usage(disk)
        val ticket = judge.measurementContext("app", now)
        judge.record(ticket, 25 * 60_000L, now)
        assertEquals(5 * 60_000L, remaining(row(disk), 30))
        val cut = store.request(EditorChange("Cut", limits =
            EditorLimitsChange("app", TimeLimit("app", 1, BreakLevel.HARDCORE, "UTC"), null)), now, safe)
        val pending = (cut as EditorCommitResult.Preview).pending
        assertEquals(CommitmentPolicy.Outcome.ALLOW, pending.outcome)
        assertEquals(EditorCommitResult.Applied(false), store.confirm(pending.id, now, safe))
        // The stricter budget applies at once to the *same* measured floor: no new day was minted.
        assertEquals(25 * 60_000L, row(disk).usedMs)
        assertEquals(ticket.periodId, row(disk).periodId)
        assertEquals(0L, remaining(row(disk), 1))
        assertEquals(0L, remaining(judge.record(judge.measurementContext("app", now), 0, now).state, 1))
        assertEquals(25 * 60_000L, row(disk).usedMs)
    }

    /** (f) Each package keeps its own trusted day; one limit's floor cannot leak or vanish. */
    @Test fun R18b_floorsFollowTheirOwnPackageAcrossAForgedMidnight() = runBlocking {
        val disk = Disk(legacy())
        val first = usage(disk, prepare = false)
        val appTicket = first.measurementContext("app", now)
        first.record(appTicket, 60_000, now)
        assertEquals(0L, remaining(row(disk, "app"), 1))

        now = boot(1_000, base + 48 * hour)
        val recreated = usage(disk, prepare = false)
        val other = recreated.measurementContext("other", now)
        assertEquals("UTC", other.zoneId)
        assertEquals(30 * 60_000L, remaining(recreated.record(other, 0, now).state, 30))
        val app = recreated.measurementContext("app", now)
        assertEquals(appTicket.periodId, app.periodId)
        assertEquals(60_000L, recreated.record(app, 0, now).state.usedMs)
        assertEquals(0L, remaining(row(disk, "app"), 1))
        assertEquals(0L, row(disk, "other").usedMs)
    }

    /** (i) A durable floor above every later OS sum is never refunded by an OS collapse to zero. */
    @Test fun R18b_osZeroAfterAHigherFloorNeverRefundsAcrossRecreation() = runBlocking {
        val disk = Disk(legacy())
        val first = usage(disk, prepare = false)
        val ticket = first.measurementContext("app", now)
        first.record(ticket, 60_000, now)
        var at = boot(1_000)
        for (step in 1..4) {
            now = at
            val store = usage(disk, prepare = false)
            val context = store.measurementContext("app", now)
            val result = store.record(context, 0, now)
            assertEquals(60_000L, result.state.usedMs)
            assertEquals(0L, remaining(result.state, 1))
            at = boot(1_000L * (step + 1))
        }
        // A backward wall edit past the trusted day start is not allowed to import zero or refund.
        now = boot(10_000, base - 2 * day)
        val back = usage(disk, prepare = false).measurementContext("app", now)
        assertEquals(ticket.periodId, back.periodId)
        try {
            usage(disk, prepare = false).record(back, 0, now)
            fail("a future-dated window must not be charged")
        } catch (_: StorageUnavailableException) { }
        assertEquals(60_000L, row(disk).usedMs)
        assertEquals(0L, remaining(row(disk), 1))
    }

    /** The trusted reset is the only refill, and it happens once even after a week of wall edits. */
    @Test fun R18b_onlyTheTrustedResetRefillsAndOnlyOnce() = runBlocking {
        val disk = Disk(legacy())
        val first = usage(disk, prepare = false)
        val ticket = first.measurementContext("app", now)
        first.record(ticket, 60_000, now)
        for ((delta, sample) in listOf(day to 0L, 2 * day to 0L, -5 * day to null, 10 * day to 0L)) {
            now = boot(1_000, base + delta)
            val store = usage(disk, prepare = false)
            val context = store.measurementContext("app", now)
            assertEquals("wall delta $delta", ticket.periodId, context.periodId)
            // A backward edit cannot be measured by the OS window; that is unmeasured, not unused.
            assertEquals("wall delta $delta", 60_000L, store.record(context, sample, now).state.usedMs)
        }
        now = boot(hour + 1_000, base + 10 * day)
        assertEquals(ticket.periodId + 1, usage(disk, prepare = false).measurementContext("app", now).periodId)
        now = boot(hour + 1_001, base + 20 * day)
        assertEquals(ticket.periodId + 1, usage(disk, prepare = false).measurementContext("app", now).periodId)
    }
}
