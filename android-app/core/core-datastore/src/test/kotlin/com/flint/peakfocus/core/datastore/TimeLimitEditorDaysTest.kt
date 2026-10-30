package com.flint.peakfocus.core.datastore

import androidx.datastore.preferences.core.*
import com.flint.peakfocus.blocking.engine.*
import com.flint.peakfocus.core.model.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class TimeLimitEditorDaysTest {
    private val hour = 3_600_000L
    private val now = AccessClockStamp(Instant.parse("2026-10-28T23:00:00Z").toEpochMilli(), 10_000, "boot")
    private fun cause(remaining: Long = 2 * hour) = EditorCause("old-exact-id", AnchoredInterval(now, remaining,
        now.wallEpochMs + remaining), "UTC")
    private fun prefs() = preferencesOf(PrefsKeys.TIME_LIMITS to "app~30~HARDER").toMutablePreferences()

    @Test fun PC_56_upgradePreservesActiveTimeAnchorAndIdentityWhileCapturingTemplateZone() {
        val prefs = prefs()
        val state = EditorCommitmentState(causes = mapOf(timeCauseKey("app") to cause()), timeline = now)
        val ledger = prepareTimeLimitEditorDays(prefs, state, now, "Asia/Tokyo")!!
        assertEquals("Asia/Tokyo", prefs.editorTimes().single().zoneId)
        assertEquals("UTC", ledger.targets.getValue("app").zoneId)
        assertEquals(2 * hour, ledger.targets.getValue("app").reset.remainingToResetMs)
        assertEquals(state, preserveTimeLimitEditorEnds(state, ledger, now))
        val end = now.copy(wallEpochMs = now.wallEpochMs + 2 * hour, elapsedRealtimeMs = now.elapsedRealtimeMs + 2 * hour)
        val next = advanceTimeLimitEditorDays(prefs, ledger, end, false)!!
        assertEquals("Asia/Tokyo", next.targets.getValue("app").zoneId)
        assertEquals(ledger.targets.getValue("app").periodId + 1, next.targets.getValue("app").periodId)
    }

    @Test fun PC_60_upgradeAfterRebootResumesWholeSavedBoundAndDoesNotTrustWallExpiry() {
        val prefs = prefs()
        val state = EditorCommitmentState(causes = mapOf(timeCauseKey("app") to cause()), timeline = now)
        val reboot = now.copy(wallEpochMs = now.wallEpochMs + 7 * 86_400_000L, elapsedRealtimeMs = 5, bootSessionId = "unknown-boot")
        val ledger = prepareTimeLimitEditorDays(prefs, state, reboot, "Asia/Tokyo")!!
        assertEquals(2 * hour, ledger.targets.getValue("app").reset.remainingToResetMs)
        val preserved = preserveTimeLimitEditorEnds(state, ledger, reboot).causes.getValue(timeCauseKey("app"))
        assertEquals("old-exact-id", preserved.occurrenceId)
        assertEquals(2 * hour, preserved.interval!!.remainingMs(reboot))
    }

    @Test fun PC_60_previewReadsDoNotPersistAnUnchangedSameBootCheckpoint() {
        val prefs = prefs()
        val initial = prepareTimeLimitEditorDays(prefs, null, now, "UTC")!!
        val bytes = prefs.toPreferences()
        for (elapsed in listOf(1_000L, 15_000L, 60_000L)) {
            val later = now.copy(wallEpochMs = now.wallEpochMs + elapsed, elapsedRealtimeMs = now.elapsedRealtimeMs + elapsed)
            assertEquals(initial, advanceTimeLimitEditorDays(prefs, initial, later, false))
            assertEquals(bytes, prefs)
        }
    }

    @Test fun PC_60_anAlreadyProvenOldEndIsNotMovedPastItsDueQueuedChange() {
        val prefs = prefs()
        val old = cause(0)
        val change = EditorChange("delete", limits = EditorLimitsChange("app", null, null))
        val expected = EditorExpected(emptyList(), TimeLimit("app", 30, BreakLevel.HARDER), null,
            mapOf(timeCauseKey("app") to old.occurrenceId))
        val queue = QueuedEditorChange(1, change, expected, mapOf(timeCauseKey("app") to old.interval!!))
        val state = EditorCommitmentState(causes = mapOf(timeCauseKey("app") to old), queued = listOf(queue))
        val ledger = prepareTimeLimitEditorDays(prefs, state, now, "UTC")!!
        val preserved = preserveTimeLimitEditorEnds(state, ledger, now)
        assertEquals(0L, preserved.causes.getValue(timeCauseKey("app")).interval!!.remainingMs(now))
        assertEquals(0L, preserved.queued.single().boundaries.values.single().remainingMs(now))
    }

    @Test fun PC_56_newTargetRegistersAtCommitAndDraftZoneDoesNotFollowLaterTravel() {
        val prefs = prefs()
        val ledger = prepareTimeLimitEditorDays(prefs, null, now, "UTC")!!
        val draft = EditorChange("add", limits = EditorLimitsChange("new", TimeLimit("new", 1), null))
        val normalized = normalizeTimeLimitEditorChange(draft, prefs.editorTimes(), ledger, "Asia/Tokyo")
        assertEquals(normalized, normalizeTimeLimitEditorChange(normalized, prefs.editorTimes(), ledger, "Pacific/Auckland"))
        prefs[PrefsKeys.TIME_LIMITS] = PrefsCodec.encodeTimeLimits(prefs.editorTimes() + normalized.limits!!.time!!)
        val committed = advanceTimeLimitEditorDays(prefs, ledger, now, true)!!
        assertEquals("Asia/Tokyo", committed.targets.getValue("new").zoneId)
        assertEquals(setOf("app", "new"), committed.targets.keys)
    }

    @Test fun PC_37_PC_48_corruptTimeChildrenAreIsolatedAndCannotPublishAPartialZoneMigration() {
        val invalidAnchor = EditorCommitmentState(causes = mapOf(timeCauseKey("app") to cause(30 * hour)))
        val initial = prefs(); val before = initial.toPreferences()
        assertNull(prepareTimeLimitEditorDays(initial, invalidAnchor, now, "Asia/Tokyo"))
        assertEquals(before, initial)
        for (kind in listOf("type", "future", "state")) {
            val broken = prefs()
            prepareTimeLimitEditorDays(broken, null, now, "UTC")
            when (kind) {
                "type" -> broken[stringPreferencesKey(TIME_USAGE_SCHEMA.name)] = "wrong type"
                "future" -> broken[TIME_USAGE_SCHEMA] = 2L
                else -> broken[TIME_USAGE_STATE] = "corrupt"
            }
            val original = broken.toPreferences()
            assertNull(kind, prepareTimeLimitEditorDays(broken, null, now, "Asia/Tokyo"))
            assertEquals(original, broken)
        }
    }

    @Test fun PC_48_unavailableTimeDomainDoesNotChangeTheTimeHalfOfAnOpenOnlyPairedEdit() {
        val old = TimeLimit("app", 30, BreakLevel.HARDER)
        val openOnly = EditorChange("Open edit", limits = EditorLimitsChange("app", old, OpenLimit("app", 2)))
        assertEquals(openOnly, normalizeTimeLimitEditorChange(openOnly, listOf(old), null, "Asia/Tokyo"))
    }
}
