package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.blocking.engine.AnchoredInterval
import com.flint.peakfocus.core.datastore.EditorCause
import com.flint.peakfocus.core.datastore.EditorCommitmentState
import com.flint.peakfocus.core.model.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ProtectionStatusProjectionTest {
    private val at = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(2026, Calendar.OCTOBER, 28, 10, 0) }.timeInMillis
    private val now = AccessClockStamp(at, 10_000, "boot")
    private val rule = BlockRule("session-timer-v2-one", " Deep\nwork ", BlockTargets(apps = setOf(AppRef("a"))),
        breakLevel = BreakLevel.HARDCORE, expiresAtEpochMs = at + 300_000)
    private val sidecar = EditorCommitmentState(causes = mapOf("rule:${rule.id}" to
        EditorCause(rule.id, AnchoredInterval(now, 300_000, at + 300_000), "UTC")), timeline = now)
    private val resolved = SafetyFloorGate.RuntimeResolution(setOf("home", "dialer"), setOf("home", "dialer"), checkedAtElapsedMs = now.elapsedRealtimeMs)
    private fun project(stamp: AccessClockStamp = now, focus: StorageState<FocusSnapshot> = StorageState.Ready(FocusSnapshot()),
        editor: StorageState<EditorCommitmentState> = StorageState.Ready(sidecar), rules: List<BlockRule> = listOf(rule),
        floor: SafetyFloorGate.RuntimeResolution = resolved, times: List<TimeLimit> = emptyList(), opens: List<OpenLimit> = emptyList()) =
        ProtectionStatusProjection.evaluate(focus, StorageState.Ready(rules), StorageState.Ready(times), StorageState.Ready(opens),
            editor, rules, floor, true, stamp)

    @Test fun PC_73_liveCauseHasTheStoredTierAndElapsedEndAndNeverInventsGlobalPassAvailability() {
        val s = project()
        assertEquals(ProtectionStatus.Readiness.READY, s.readiness)
        val c = s.activeCauses.single()
        assertEquals("Deep work", c.displayName)
        assertEquals(ProtectionStatus.CauseKind.TIMER, c.kind)
        assertEquals(BreakLevel.HARDCORE, c.tier)
        assertEquals(300_000L, c.remainingMs)
        assertEquals(310_000L, c.logicalEndElapsedRealtimeMs)
        assertFalse(c.timeUncertain)
        assertEquals(at, s.lastCheckedEpochMs)
        assertEquals(12_000L, s.validUntilElapsedRealtimeMs)
        assertEquals(ProtectionStatus.PassStatus.Unavailable, s.pass)
        assertTrue("pass-contract-unavailable" in s.detectors.degradedReasons)
        assertEquals(ProtectionStatus.PathBState.UNKNOWN, s.detectors.pathB)
    }

    @Test fun PC_55_wallClockEditsCannotEndOrExtendTheAnchoredDisplayedCommitment() {
        for (wall in listOf(at + 86_400_000, at - 86_400_000)) {
            val c = project(now.copy(wallEpochMs = wall, elapsedRealtimeMs = 20_000)).activeCauses.single()
            assertEquals(290_000L, c.remainingMs)
            assertEquals(310_000L, c.logicalEndElapsedRealtimeMs)
            assertEquals(wall + 290_000, c.wallEndEstimateEpochMs)
            assertTrue(c.timeUncertain)
        }
        assertTrue(project(now.copy(elapsedRealtimeMs = 310_000)).activeCauses.isEmpty())
    }

    @Test fun PC_47_PC_73_loadingUnavailableAndMissingOccurrenceNeverBecomeHealthyDefaults() {
        val loading = project(focus = StorageState.Loading)
        assertEquals(ProtectionStatus.Readiness.LOADING, loading.readiness)
        assertTrue(loading.activeCauses.isEmpty())
        val unavailable = project(editor = StorageState.Unavailable(StorageFailure.IO))
        assertEquals(ProtectionStatus.Readiness.UNAVAILABLE, unavailable.readiness)
        assertEquals(ProtectionStatus.PassStatus.Unavailable, unavailable.pass)
        val incomplete = project(editor = StorageState.Ready(EditorCommitmentState()))
        assertEquals(ProtectionStatus.Readiness.UNAVAILABLE, incomplete.readiness)
        assertTrue(incomplete.activeCauses.isEmpty())
        assertTrue("occurrence-state-unavailable" in incomplete.detectors.degradedReasons)
    }

    @Test fun PC_52_stickySafetyResolutionRetainsProvenanceButLatestFailureAndStaleCheckStayDegraded() {
        val failed = resolved.copy(failedSources = setOf("dialer"))
        val s = project(floor = failed)
        assertTrue(s.safetyFloorResolved)
        assertTrue(s.safetyFloor!!.degraded)
        assertEquals(now.elapsedRealtimeMs, s.safetyFloor!!.checkedAtElapsedRealtimeMs)
        assertTrue("safety-floor-refresh-failed" in s.detectors.degradedReasons)
        val stale = project(stamp = now.copy(elapsedRealtimeMs = now.elapsedRealtimeMs + SafetyFloorGate.DEFAULT_REFRESH_INTERVAL_MS))
        assertTrue(stale.safetyFloor!!.degraded)
        assertTrue("safety-floor-check-stale" in stale.detectors.degradedReasons)
        assertFalse(project(floor = SafetyFloorGate.RuntimeResolution(setOf("home"))).safetyFloorResolved)
    }

    @Test fun PC_15_PC_28_unspentDailyLimitsAreStillActiveCommitmentsWithTheirOwnTiers() {
        val period = EditorCause("day", AnchoredInterval(now, 600_000, at + 600_000), "UTC")
        val editor = sidecar.copy(causes = sidecar.causes + ("time:a" to period) + ("open:b" to period))
        val s = project(editor = StorageState.Ready(editor), times = listOf(TimeLimit("a", 30, BreakLevel.HARDCORE)),
            opens = listOf(OpenLimit("b", 2, BreakLevel.HARDER)))
        assertEquals(3, s.activeCauses.size)
        assertEquals(BreakLevel.HARDCORE, s.activeCauses.single { it.kind == ProtectionStatus.CauseKind.TIME_LIMIT }.tier)
        assertEquals(BreakLevel.HARDER, s.activeCauses.single { it.kind == ProtectionStatus.CauseKind.OPEN_LIMIT }.tier)
    }

    @Test fun PC_55_savedZoneAndAnchoredTimelineDetermineTheNextScheduleAfterAWallJump() {
        val scheduled = rule.copy(id = "sleep-window", schedule = Schedule(startMinuteOfDay = 11 * 60, endMinuteOfDay = 12 * 60), expiresAtEpochMs = null)
        val editor = sidecar.copy(causes = mapOf("rule:sleep-window" to EditorCause("inactive", AnchoredInterval(now, 0, at), "UTC")))
        val jumped = now.copy(wallEpochMs = at + 86_400_000, elapsedRealtimeMs = 70_000)
        val next = project(jumped, editor = StorageState.Ready(editor), rules = listOf(scheduled)).nextOccurrence!!
        assertEquals(ProtectionStatus.CauseKind.SLEEP, next.kind)
        assertEquals(jumped.wallEpochMs + 3_540_000, next.startWallEstimateEpochMs)
    }
}
