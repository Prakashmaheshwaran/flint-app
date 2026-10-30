package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.blocking.engine.AnchoredInterval
import com.flint.peakfocus.core.datastore.EditorCause
import com.flint.peakfocus.core.datastore.EditorCommitmentState
import com.flint.peakfocus.core.model.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone
import java.time.Instant

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
        floor: SafetyFloorGate.RuntimeResolution = resolved, times: List<TimeLimit> = emptyList(), opens: List<OpenLimit> = emptyList(),
        usageAccessMissing: Boolean = false, pathAConnected: Boolean = true,
        timeMeasurementUnavailable: Boolean = false, timeMeasurementStorageUnavailable: Boolean = false) =
        ProtectionStatusProjection.evaluate(focus, StorageState.Ready(rules), StorageState.Ready(times), StorageState.Ready(opens),
            editor, rules, floor, pathAConnected, stamp, usageAccessMissing = usageAccessMissing,
            timeMeasurementUnavailable = timeMeasurementUnavailable, timeMeasurementStorageUnavailable = timeMeasurementStorageUnavailable)

    @Test fun PC_60_unknownMeasurementIsDegradedWithoutPretendingTheGrantIsMissingOrQuotaIsSpent() {
        val status = project(timeMeasurementUnavailable = true)
        assertTrue("time-limit-measurement-unavailable" in status.detectors.degradedReasons)
        assertFalse("time-limits-need-usage-access" in status.detectors.degradedReasons)
        assertFalse("usage-access-missing" in status.detectors.degradedReasons)
        assertEquals(ProtectionStatus.Readiness.READY, status.readiness)
        assertFalse(status.activeCauses.any { it.kind == ProtectionStatus.CauseKind.TIME_LIMIT })
        val failed = project(timeMeasurementUnavailable = true, timeMeasurementStorageUnavailable = true)
        assertEquals(ProtectionStatus.Readiness.UNAVAILABLE, failed.readiness)
        assertTrue("policy-storage-unavailable" in failed.detectors.degradedReasons)
        assertEquals(ProtectionStatus.PassStatus.Unavailable, failed.pass)
    }

    @Test fun pathAOnlyWithoutTimeLimitsReportsMissingBackupWithoutInventingTimeLimitFailure() {
        val s = project(usageAccessMissing = true)
        assertTrue(s.detectors.accessibilityConnected)
        assertTrue("path-b-backup-unavailable" in s.detectors.degradedReasons)
        assertFalse("time-limits-need-usage-access" in s.detectors.degradedReasons)
        assertEquals(ProtectionStatus.Readiness.READY, s.readiness)
        assertEquals(rule.id, s.activeCauses.single().ruleId)
        assertEquals(now, s.observedAt)
    }

    @Test fun pathAOnlyWithConfiguredTimeLimitsReportsTheirUsageAccessDependencyUntilRecovery() {
        val limit = TimeLimit("a", 30, BreakLevel.HARDCORE)
        val period = EditorCause("day", AnchoredInterval(now, 600_000, at + 600_000), "UTC")
        val editor = StorageState.Ready(sidecar.copy(causes = sidecar.causes + ("time:a" to period)))
        val missing = project(editor = editor, times = listOf(limit), usageAccessMissing = true)
        assertTrue("path-b-backup-unavailable" in missing.detectors.degradedReasons)
        assertTrue("time-limits-need-usage-access" in missing.detectors.degradedReasons)
        assertEquals(ProtectionStatus.Readiness.READY, missing.readiness)
        val recovered = project(editor = editor, times = listOf(limit), usageAccessMissing = false)
        assertFalse("path-b-backup-unavailable" in recovered.detectors.degradedReasons)
        assertFalse("time-limits-need-usage-access" in recovered.detectors.degradedReasons)
        assertEquals(missing.activeCauses, recovered.activeCauses)
        val disconnected = project(editor = editor, times = listOf(limit), usageAccessMissing = true, pathAConnected = false)
        assertFalse("path-b-backup-unavailable" in disconnected.detectors.degradedReasons)
        assertTrue("path-a-disconnected" in disconnected.detectors.degradedReasons)
        assertTrue("time-limits-need-usage-access" in disconnected.detectors.degradedReasons)
    }

    @Test fun zeroMinuteBlocksRemainActiveWithoutAUsageMeasurementWarning() {
        val period = EditorCause("day", AnchoredInterval(now, 600_000, at + 600_000), "UTC")
        val editor = StorageState.Ready(sidecar.copy(causes = sidecar.causes +
            mapOf("time:a" to period, "time:b" to period)))
        for (tier in BreakLevel.entries) {
            val zero = TimeLimit("a", 0, tier)
            val status = project(editor = editor, times = listOf(zero), usageAccessMissing = true)
            assertTrue("path-b-backup-unavailable" in status.detectors.degradedReasons)
            assertFalse("time-limits-need-usage-access" in status.detectors.degradedReasons)
            assertEquals(ProtectionStatus.Readiness.READY, status.readiness)
            assertEquals(tier, status.activeCauses.single { it.kind == ProtectionStatus.CauseKind.TIME_LIMIT }.tier)
            val mixed = project(editor = editor, times = listOf(zero, TimeLimit("b", 1, tier)), usageAccessMissing = true)
            assertTrue("time-limits-need-usage-access" in mixed.detectors.degradedReasons)
        }
    }

    @Test fun contextualPolicyReasonsRetainTheServiceCauseWithoutFeedingItBackAfterRecovery() {
        try {
            ProtectionStatusHub.publishPathBHealth(ProtectionStatus.PathBState.OFF, setOf("usage-access-missing"))
            fun refresh() = ProtectionStatusHub.publish(project(usageAccessMissing =
                "usage-access-missing" in ProtectionStatusHub.status.value.detectors.degradedReasons))
            refresh()
            assertTrue("usage-access-missing" in ProtectionStatusHub.status.value.detectors.degradedReasons)
            assertTrue("path-b-backup-unavailable" in ProtectionStatusHub.status.value.detectors.degradedReasons)
            ProtectionStatusHub.publishPathBHealth(ProtectionStatus.PathBState.STANDBY)
            refresh()
            assertFalse("usage-access-missing" in ProtectionStatusHub.status.value.detectors.degradedReasons)
            assertFalse("path-b-backup-unavailable" in ProtectionStatusHub.status.value.detectors.degradedReasons)
            assertTrue(ProtectionStatusHub.status.value.detectors.accessibilityConnected)
            assertEquals(now, ProtectionStatusHub.status.value.observedAt)
        } finally { ProtectionStatusHub.resetForTests() }
    }

    @Test fun PC_73_liveCauseHasTheStoredTierAndElapsedEndAndNeverInventsGlobalPassAvailability() {
        val s = project()
        assertEquals(ProtectionStatus.Readiness.READY, s.readiness)
        val c = s.activeCauses.single()
        assertEquals("Deep work", c.displayName)
        assertEquals(ProtectionStatus.CauseKind.TIMER, c.kind)
        assertEquals(sidecar.causes.getValue("rule:${rule.id}").occurrenceId, c.occurrenceId)
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

    @Test fun PC_44_PC_63_PC_73_initializedRecoveryUsesElapsedBoundaryAndPublishesBoundedHistoryLoss() {
        val id = "p1-one"
        val recovery = ProtectionRecovery(id, now, setOf("rule:${rule.id}"), setOf(rule.id), emptySet(), 1_000)
        val ledger = ProtectionLedger(now, now, cooldown = ProtectionCooldown(id, at, now, 604_800_000),
            recovery = recovery, droppedEvents = 7)
        val s = project(focus = StorageState.Ready(FocusSnapshot(protection = ledger)))
        assertEquals(ProtectionStatus.PassStatus.RecoveryActive(1_000, 11_000), s.pass)
        assertEquals(11_000L, s.validUntilElapsedRealtimeMs)
        assertEquals(7L, s.lifecycleEventsDropped)
        assertTrue("lifecycle-events-dropped" in s.detectors.degradedReasons)
        assertFalse("pass-contract-unavailable" in s.detectors.degradedReasons)
        val jumped = project(now.copy(wallEpochMs = at + 86_400_000, elapsedRealtimeMs = 10_500),
            focus = StorageState.Ready(FocusSnapshot(protection = ledger)))
        assertEquals(ProtectionStatus.PassStatus.RecoveryActive(500, 11_000), jumped.pass)
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

    @Test fun R_18_PC_57_nextScheduleUsesEarlierOverlapStartAndDoesNotRepeatItInTheSecondHour() {
        val scheduled = rule.copy(id = "overlap", schedule = Schedule(startMinuteOfDay = 75, endMinuteOfDay = 105), expiresAtEpochMs = null)
        val first = AccessClockStamp(Instant.parse("2026-11-01T05:00:00Z").toEpochMilli(), 1_000, "boot")
        val editor = EditorCommitmentState(causes = mapOf("rule:overlap" to
            EditorCause("inactive", AnchoredInterval(first, 0, first.wallEpochMs), "America/New_York")), timeline = first)
        val next = project(first, editor = StorageState.Ready(editor), rules = listOf(scheduled)).nextOccurrence!!
        assertEquals(Instant.parse("2026-11-01T05:15:00Z").toEpochMilli(), next.startWallEstimateEpochMs)
        val second = first.copy(wallEpochMs = first.wallEpochMs + 3_600_000, elapsedRealtimeMs = first.elapsedRealtimeMs + 3_600_000)
        val tomorrow = project(second, editor = StorageState.Ready(editor), rules = listOf(scheduled)).nextOccurrence!!
        assertEquals(Instant.parse("2026-11-02T06:15:00Z").toEpochMilli(), tomorrow.startWallEstimateEpochMs)
    }

    @Test fun R_18_PC_57_nextScheduleShiftsMissingLocalStartByTheFullDSTGap() {
        val scheduled = rule.copy(id = "gap", schedule = Schedule(startMinuteOfDay = 150, endMinuteOfDay = 240), expiresAtEpochMs = null)
        val before = AccessClockStamp(Instant.parse("2026-03-08T07:00:00Z").toEpochMilli(), 1_000, "boot")
        val editor = EditorCommitmentState(causes = mapOf("rule:gap" to
            EditorCause("inactive", AnchoredInterval(before, 0, before.wallEpochMs), "America/New_York")), timeline = before)
        val jumped = before.copy(wallEpochMs = before.wallEpochMs + 86_400_000, elapsedRealtimeMs = before.elapsedRealtimeMs + 60_000)
        val next = project(jumped, editor = StorageState.Ready(editor), rules = listOf(scheduled)).nextOccurrence!!
        assertEquals(jumped.wallEpochMs + 29 * 60_000, next.startWallEstimateEpochMs)
    }

    @Test fun PC_73_legacyQuickBlockScheduleUsesItsOwnSourceWithoutMakingStoredPolicyUnavailable() {
        val foreign = rule.copy(id = "blocklist", name = "Quick block", breakLevel = BreakLevel.HARDER,
            schedule = Schedule(daysOfWeek = setOf(3), startMinuteOfDay = 9 * 60, endMinuteOfDay = 11 * 60), expiresAtEpochMs = null)
        fun status(stamp: AccessClockStamp) = ProtectionStatusProjection.evaluate(StorageState.Ready(FocusSnapshot()),
            StorageState.Ready(listOf(rule)), StorageState.Ready(emptyList()), StorageState.Ready(emptyList()),
            StorageState.Ready(sidecar), listOf(foreign), resolved, true, stamp, legacyZone = "UTC")
        val active = status(now)
        assertEquals(ProtectionStatus.Readiness.READY, active.readiness)
        val legacy = active.activeCauses.single { it.ruleId == "blocklist" }
        assertEquals(ProtectionStatus.CauseKind.SCHEDULE, legacy.kind)
        assertEquals(BreakLevel.HARDER, legacy.tier)
        assertEquals(3_600_000L, legacy.remainingMs)
        assertNull(legacy.logicalEndElapsedRealtimeMs)
        assertNull(legacy.occurrenceId)
        assertTrue(legacy.timeUncertain)
        assertFalse("occurrence-state-unavailable" in active.detectors.degradedReasons)
        val ended = status(now.copy(wallEpochMs = at + 3_600_000, elapsedRealtimeMs = 3_610_000))
        assertEquals(ProtectionStatus.Readiness.READY, ended.readiness)
        assertTrue(ended.activeCauses.none { it.ruleId == "blocklist" })
    }
}
