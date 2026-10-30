package com.flint.peakfocus.core.datastore

import androidx.datastore.preferences.core.*
import com.flint.peakfocus.blocking.engine.*
import com.flint.peakfocus.core.model.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class TimeLimitEditorZoneRebindingTest {
    private val now = AccessClockStamp(Instant.parse("2026-10-28T23:00:00Z").toEpochMilli(), 10_000, "boot")
    private val old = TimeLimit("app", 30, BreakLevel.HARDER)
    private val occurrence = HarderWaitPolicy.Occurrence(timeCauseKey("app"), "same-active-id")
    private val change = EditorChange("literal TimeLimit(packageName=app) label", limits = EditorLimitsChange("app", old.copy(dailyMinutes = 60), null))
    private val expected = EditorExpected(emptyList(), old, null, mapOf(timeCauseKey("app") to occurrence.occurrenceId))
    private val operation = HarderWaitPolicy.Operation(HarderWaitPolicy.OperationKind.EDIT_WEAKEN, mapOf(occurrence to "30:HARDER"),
        setOf(legacyTimeLimitEditorScope(change)))
    private val cause = EditorCause(occurrence.occurrenceId, AnchoredInterval(now, 3_600_000, now.wallEpochMs + 3_600_000), "UTC")
    private fun prefs() = preferencesOf(PrefsKeys.TIME_LIMITS to "app~30~HARDER").toMutablePreferences().also {
        it.putHarderWait(HarderWaitPolicy.Ledger(mapOf(occurrence to 2), HarderWaitPolicy.PendingWait(operation, now, 60_000)))
    }
    private fun state() = EditorCommitmentState(causes = mapOf(timeCauseKey("app") to cause),
        pending = PendingEditorChange(7, change, expected, CommitmentPolicy.Outcome.WAIT, operation))
    private fun migrate(prefs: MutablePreferences, state: EditorCommitmentState, at: AccessClockStamp = now,
        before: List<TimeLimit> = listOf(old)): EditorCommitmentState {
        val ledger = prepareTimeLimitSavedZones(prefs, now, "UTC")
        return rebindTimeLimitEditorZones(prefs, state, before, ledger, "UTC", at,
            expected = { expected.copy(time = prefs.editorTimes().single()) },
            decision = { normalized -> CommitmentPolicy.Outcome.WAIT to HarderWaitPolicy.Operation(operation.kind,
                mapOf(occurrence to "30:HARDER:UTC"), setOf(normalized.toString())) })
    }

    @Test fun PC_54_PC_56_upgradeRebindsOnlyExactOldScopeAndPreservesWaitTimeCountAndCauseIdentity() {
        val prefs = prefs()
        val result = migrate(prefs, state(), now.copy(wallEpochMs = now.wallEpochMs + 15_000, elapsedRealtimeMs = now.elapsedRealtimeMs + 15_000))
        val pending = result.pending!!
        assertEquals(7L, pending.id)
        assertEquals("UTC", pending.change.limits!!.time!!.zoneId)
        assertEquals("UTC", pending.expected.time!!.zoneId)
        assertEquals(cause, result.causes.getValue(timeCauseKey("app")))
        val harder = readHarderWait(prefs)
        assertEquals(mapOf(occurrence to 2), harder.counts)
        assertEquals(now, harder.pending!!.startedAt)
        assertEquals(60_000L, harder.pending!!.requiredMs)
        assertEquals(pending.wait, harder.pending!!.operation)
        assertEquals(setOf(pending.change.toString()), pending.wait!!.targetScope)
    }

    @Test fun PC_54_PC_70_staleRecordsOrForgedScopeCancelOnlyPendingAndNeverRefundSpentFriction() {
        for (staleRecord in listOf(false, true)) {
            val prefs = prefs()
            val original = state()
            val forged = operation.copy(targetScope = setOf("unprovable"))
            val before = if (staleRecord) listOf(old.copy(dailyMinutes = 20)) else listOf(old)
            val state = if (staleRecord) original else original.copy(pending = original.pending!!.copy(wait = forged)).also {
                prefs.putHarderWait(readHarderWait(prefs).copy(pending = HarderWaitPolicy.PendingWait(forged, now, 60_000)))
            }
            val result = migrate(prefs, state, before = before)
            assertNull(result.pending)
            assertNull(readHarderWait(prefs).pending)
            assertEquals(mapOf(occurrence to 2), readHarderWait(prefs).counts)
            assertEquals(original.causes, result.causes)
        }
    }

    @Test fun PC_60_rebootCancelsPendingWithoutIncrementingOrResettingN() {
        val prefs = prefs()
        val result = migrate(prefs, state(), now.copy(bootSessionId = "reboot", elapsedRealtimeMs = 5))
        assertNull(result.pending)
        assertNull(readHarderWait(prefs).pending)
        assertEquals(mapOf(occurrence to 2), readHarderWait(prefs).counts)
    }

    @Test fun PC_56_queuedExpectedRecordsAndDraftZoneRebindTogetherWhileStaleQueueNeedsReview() {
        val prefs = prefs()
        val queued = QueuedEditorChange(8, change, expected, mapOf(timeCauseKey("app") to cause.interval!!))
        val stale = queued.copy(id = 9, expected = expected.copy(time = old.copy(dailyMinutes = 20)))
        val result = migrate(prefs, state().copy(pending = null, queued = listOf(queued, stale)))
        val current = result.queued.first()
        assertEquals("UTC", current.change.limits!!.time!!.zoneId)
        assertEquals("UTC", current.expected.time!!.zoneId)
        assertEquals(queued.boundaries, current.boundaries)
        assertFalse(current.needsReview)
        assertEquals(stale.copy(needsReview = true), result.queued.last())
    }
}
