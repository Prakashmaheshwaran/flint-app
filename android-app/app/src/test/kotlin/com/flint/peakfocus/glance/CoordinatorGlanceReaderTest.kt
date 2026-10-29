package com.flint.peakfocus.glance

import com.flint.peakfocus.blocking.overlay.ProtectionStatus as C
import com.flint.peakfocus.core.model.AccessClockStamp
import com.flint.peakfocus.core.model.BreakLevel
import org.junit.Assert.*
import org.junit.Test

class CoordinatorGlanceReaderTest {
    private val timer = C.ActiveCause("timer-fixture", "Deep study", C.CauseKind.TIMER, BreakLevel.HARDCORE,
        300_000, 400_000, 1_800_000_300_000, false, "occurrence-fixture")
    private val ready = C(C.Readiness.READY, listOf(timer), null, C.PassStatus.Available,
        C.DetectorHealth(true, C.PathBState.OFF, emptySet()), true,
        AccessClockStamp(1_800_000_000_000, 100_000, "fixture-boot"), 130_000,
        C.SafetyFloorStatus(setOf("home"), setOf("home"), emptySet(), 100_000, false))

    @Test fun unknownLoadingUnavailableAndMissingValidityStayUnavailable() {
        listOf(C.UNKNOWN, ready.copy(readiness = C.Readiness.LOADING), ready.copy(readiness = C.Readiness.UNAVAILABLE),
            ready.copy(observedAt = null),
            ready.copy(validUntilElapsedRealtimeMs = null)).forEach {
            assertEquals(ProtectionStatus.UNAVAILABLE, coordinatorGlanceReading(it).status)
        }
    }

    @Test fun unavailablePassIsIndependentButOtherDegradationCannotBeIgnored() {
        val pending = ready.copy(pass = C.PassStatus.Unavailable,
            detectors = ready.detectors.copy(degradedReasons = setOf("pass-contract-unavailable")))
        val reading = coordinatorGlanceReading(pending)
        assertEquals(ProtectionStatus.ACTIVE, reading.status)
        assertEquals(GlancePass.Unavailable, reading.pass)
        assertEquals(ProtectionStatus.DEGRADED, coordinatorGlanceReading(pending.copy(
            detectors = pending.detectors.copy(degradedReasons = setOf("pass-contract-unavailable", "policy-check-failed")))).status)
    }

    @Test fun stickySafetyBitCannotOverrideMissingFailedOrFutureProvenance() {
        val floor = ready.safetyFloor!!
        listOf(null, floor.copy(degraded = true), floor.copy(failedSources = setOf("home")),
            floor.copy(resolvedSources = emptySet()), floor.copy(checkedAtElapsedRealtimeMs = null),
            floor.copy(checkedAtElapsedRealtimeMs = 100_001)).forEach {
            assertEquals(ProtectionStatus.DEGRADED, coordinatorGlanceReading(ready.copy(safetyFloor = it)).status)
        }
    }

    @Test fun pathARequiresConfirmedBAbsenceAndLiveBStatesAlwaysSuppressIt() {
        assertEquals(GlanceDetector.PATH_A_CONFIRMED, coordinatorGlanceReading(ready).detector)
        val unknown = ready.copy(detectors = C.DetectorHealth(true, C.PathBState.UNKNOWN, emptySet()))
        assertEquals(GlanceDetector.UNKNOWN, coordinatorGlanceReading(unknown).detector)
        for (state in listOf(C.PathBState.STANDBY, C.PathBState.ENFORCING)) {
            val result = coordinatorGlanceReading(ready.copy(detectors = C.DetectorHealth(true, state, emptySet())))
            assertEquals(GlanceDetector.PATH_B_FOREGROUND, result.detector)
            assertEquals(ProtectionStatus.ACTIVE, result.status)
        }
        assertEquals(ProtectionStatus.DEGRADED, coordinatorGlanceReading(ready.copy(
            detectors = C.DetectorHealth(false, C.PathBState.UNKNOWN, emptySet()))).status)
    }

    @Test fun sessionKindNameLogicalEndAndAuthorityValidityAreProjectedWithoutPrivateReads() {
        val result = coordinatorGlanceReading(ready)
        assertEquals(GlanceCauseKind.FOCUS_SESSION, result.cause?.kind)
        assertEquals("occurrence-fixture", result.cause?.occurrenceId)
        assertEquals(400_000L, result.cause?.logicalEndElapsedMs)
        assertEquals("Deep study", result.cause?.name)
        assertEquals(130_000L, result.validUntilElapsedMs)
        assertEquals(1_800_000_000_000, result.checkedEpochMs)
        assertEquals(GlanceTier.HARDCORE, result.cause?.tier)
        assertTrue(result.sameBootVerified)
        val schedule = coordinatorGlanceReading(ready.copy(activeCauses = listOf(timer.copy(kind = C.CauseKind.SCHEDULE))))
        assertEquals(GlanceCauseKind.SCHEDULE, schedule.cause?.kind)
        val next = coordinatorGlanceReading(ready.copy(nextOccurrence = C.NextOccurrence("private-rule-id",
            "Study\n tomorrow", C.CauseKind.SCHEDULE, 1_800_000_600_000))).next!!
        assertEquals("Study tomorrow", next.name)
        assertFalse(next.name.contains("private-rule-id"))
    }

    @Test fun recoveryDegradationNoCauseAndTimeUncertaintyRemainDistinct() {
        assertEquals(ProtectionStatus.PASS_RECOVERY, coordinatorGlanceReading(ready.copy(
            pass = C.PassStatus.RecoveryActive(300_000, 400_000))).status)
        assertEquals(ProtectionStatus.DEGRADED, coordinatorGlanceReading(ready.copy(safetyFloorResolved = false)).status)
        assertEquals(ProtectionStatus.INACTIVE, coordinatorGlanceReading(ready.copy(activeCauses = emptyList())).status)
        assertTrue(coordinatorGlanceReading(ready.copy(activeCauses = listOf(timer.copy(timeUncertain = true)))).timeUncertain)
    }

    @Test fun nullRuleIdentityIsPreservedWithoutInventingAnOccurrenceId() {
        val reading = coordinatorGlanceReading(ready.copy(activeCauses = listOf(
            timer.copy(ruleId = null, occurrenceId = null, kind = C.CauseKind.TIME_LIMIT))))
        assertNull(reading.cause?.occurrenceId)
        assertEquals(GlanceCauseKind.TIME_LIMIT, reading.cause?.kind)
    }
}
