package com.flint.peakfocus.glance

import com.flint.peakfocus.blocking.overlay.ProtectionStatus as C
import com.flint.peakfocus.core.model.AccessClockStamp
import com.flint.peakfocus.core.model.BreakLevel
import org.junit.Assert.*
import org.junit.Test

class CoordinatorGlanceReaderTest {
    private val timer = C.ActiveCause("timer-fixture", C.CauseKind.TIMER, BreakLevel.HARDCORE,
        300_000, 400_000, 1_800_000_300_000, false)
    private val ready = C(C.Readiness.READY, listOf(timer), null, C.PassStatus.Available,
        C.DetectorHealth(true, C.PathBState.OFF, emptySet()), true,
        AccessClockStamp(1_800_000_000_000, 100_000, "fixture-boot"))

    @Test fun unknownLoadingUnavailableWrongBootAndPassReadFailureStayUnavailable() {
        listOf(C.UNKNOWN, ready.copy(readiness = C.Readiness.LOADING), ready.copy(readiness = C.Readiness.UNAVAILABLE),
            ready.copy(pass = C.PassStatus.Unavailable), ready.copy(observedAt = null)).forEach {
            assertEquals(ProtectionStatus.UNAVAILABLE, coordinatorGlanceReading(it, "fixture-boot", false).status)
        }
        for (boot in listOf(null, "", "other-boot")) {
            assertEquals(ProtectionStatus.UNAVAILABLE, coordinatorGlanceReading(ready, boot, false).status)
        }
    }

    @Test fun pathARequiresConfirmedBAbsenceAndPathBModeIsNotFgsEvidence() {
        assertEquals(GlanceDetector.PATH_A_CONFIRMED, coordinatorGlanceReading(ready, "fixture-boot", false).detector)
        assertEquals(GlanceDetector.UNKNOWN, coordinatorGlanceReading(ready, "fixture-boot", null).detector)
        val desiredB = ready.copy(detectors = C.DetectorHealth(false, C.PathBState.ENFORCING, emptySet()))
        assertEquals(ProtectionStatus.DEGRADED, coordinatorGlanceReading(desiredB, "fixture-boot", null).status)
        val liveB = coordinatorGlanceReading(desiredB, "fixture-boot", true)
        assertEquals(ProtectionStatus.ACTIVE, liveB.status)
        assertEquals(GlanceDetector.PATH_B_FOREGROUND, liveB.detector)
    }

    @Test fun sessionKindsAndLogicalDeadlineAreProjectedWithoutRuleOrPolicyReads() {
        val result = coordinatorGlanceReading(ready, "fixture-boot", false)
        assertEquals(GlanceCauseKind.FOCUS_SESSION, result.cause?.kind)
        assertEquals(400_000L, result.cause?.logicalEndElapsedMs)
        assertEquals(GlanceTier.HARDCORE, result.cause?.tier)
        assertTrue(result.sameBootVerified)
        val schedule = coordinatorGlanceReading(ready.copy(activeCauses = listOf(timer.copy(kind = C.CauseKind.SCHEDULE))),
            "fixture-boot", false)
        assertEquals(GlanceCauseKind.SCHEDULE, schedule.cause?.kind)
    }

    @Test fun recoveryDegradationNoCauseAndTimeUncertaintyRemainDistinct() {
        assertEquals(ProtectionStatus.PASS_RECOVERY, coordinatorGlanceReading(ready.copy(
            pass = C.PassStatus.RecoveryActive(300_000, 400_000)), "fixture-boot", false).status)
        assertEquals(ProtectionStatus.DEGRADED, coordinatorGlanceReading(ready.copy(safetyFloorResolved = false), "fixture-boot", false).status)
        assertEquals(ProtectionStatus.INACTIVE, coordinatorGlanceReading(ready.copy(activeCauses = emptyList()), "fixture-boot", false).status)
        assertTrue(coordinatorGlanceReading(ready.copy(activeCauses = listOf(timer.copy(timeUncertain = true))),
            "fixture-boot", false).timeUncertain)
    }
}
