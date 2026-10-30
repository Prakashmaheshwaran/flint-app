package com.flint.peakfocus.glance

import com.flint.peakfocus.blocking.overlay.ProtectionStatus as C
import com.flint.peakfocus.core.model.AccessClockStamp
import com.flint.peakfocus.core.model.BreakLevel
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

/** C's public snapshot -> widget words and notification plan, without permission inference. */
class GlanceHealthReasonsTest {
    private val now = 100_000L
    private val backup = "path-b-backup-unavailable"
    private val limits = "time-limits-need-usage-access"
    private val backupNote = "Backup blocking off: Usage access is missing"
    private val limitsNote = "Time limits can't measure app use without Usage access"
    private val ready = C(C.Readiness.READY,
        listOf(C.ActiveCause("timer", "Study", C.CauseKind.TIMER, BreakLevel.HARDCORE,
            300_000, 400_000, 1_800_000_300_000, false, "occurrence")),
        null, C.PassStatus.Available, C.DetectorHealth(true, C.PathBState.OFF, emptySet()),
        true, AccessClockStamp(1_800_000_000_000, now, "boot"), 130_000,
        C.SafetyFloorStatus(setOf("home"), setOf("home"), emptySet(), now, false))

    private fun withReasons(status: C = ready, reasons: Set<String>) =
        status.copy(detectors = status.detectors.copy(degradedReasons = reasons))
    private fun display(status: C, at: Long = now) = GlanceDisplayMapper.map(
        coordinatorGlanceReading(status), FocusTodayReading(), at, 1_800_000_000_000,
        ZoneId.of("UTC"), Locale.US)
    private val combinations get() = listOf(
        setOf(backup) to listOf(backupNote),
        setOf(limits) to listOf(limitsNote),
        // Input order must not control display order.
        linkedSetOf(limits, backup) to listOf(backupNote, limitsNote),
    )

    @Test fun eachReasonAndBothRemainNotesAcrossEveryProtectionDisplayState() {
        val cases = listOf(
            ready to "Protection active at last check",
            ready.copy(activeCauses = emptyList()) to "No active protection at last check",
            ready.copy(safetyFloorResolved = false) to "Protection degraded",
            ready.copy(readiness = C.Readiness.UNAVAILABLE) to "Protection state unavailable",
            ready.copy(readiness = C.Readiness.LOADING) to "Protection state unavailable",
            ready.copy(pass = C.PassStatus.RecoveryActive(300_000, 400_000)) to "Emergency Pass recovery",
        )
        for ((source, expectedStatus) in cases) for ((reasons, notes) in combinations) {
            val result = display(withReasons(source, reasons))
            assertEquals("$expectedStatus $reasons", expectedStatus, result.status)
            assertEquals(notes, result.healthNotes)
            assertEquals(130_000L, result.refreshAtElapsedMs)
            val plan = OngoingNotificationPlan.from(result, now)
            assertEquals("Flint · $expectedStatus", plan.title)
            assertEquals((listOf(result.detail) + notes).joinToString(" · "), plan.text)
            if (expectedStatus == "Protection active at last check") {
                assertEquals(400_000L, result.countdownEndElapsedMs)
                assertEquals("occurrence", result.session?.occurrenceId)
            } else {
                assertNull(result.countdownEndElapsedMs)
                assertNull(result.session)
            }
        }
    }

    @Test fun rawMissingAccessAndUnknownFaultsStayConservativeEvenWithBothContextualReasons() {
        for ((reasons, notes) in combinations) for (fault in listOf("usage-access-missing", "unknown-fault")) {
            val result = display(withReasons(reasons = reasons + fault))
            assertEquals("Protection degraded", result.status)
            assertEquals(notes, result.healthNotes)
            assertNull(result.session)
            assertNull(result.countdownEndElapsedMs)
        }
        assertEquals("Protection degraded", display(withReasons(reasons = setOf("usage-access-missing"))).status)
    }

    @Test fun contextualReasonsCannotProveAccessibilityOrAProtectionPath() {
        for (state in C.PathBState.entries) for ((reasons, _) in combinations) {
            val disconnected = withReasons(reasons = reasons).copy(
                detectors = C.DetectorHealth(false, state, reasons))
            val result = display(disconnected)
            assertEquals("Protection degraded", result.status)
            assertTrue(result.healthNotes.isEmpty())
            assertNull(result.session)
        }
    }

    @Test fun missingTimeLimitMeasurementDoesNotTurnOffScheduleProtection() {
        val schedule = ready.copy(activeCauses = listOf(ready.activeCauses.single().copy(kind = C.CauseKind.SCHEDULE)))
        val result = display(withReasons(schedule, setOf(limits)))
        assertEquals("Protection active at last check", result.status)
        assertTrue(result.detail.startsWith("Study · Hardcore"))
        assertEquals(listOf(limitsNote), result.healthNotes)
        assertNull(result.session)
        assertNull(result.countdownEndElapsedMs)
    }

    @Test fun warningsClearIndependentlyAndCompletelyOnRecoveryWithoutChangingTheCause() {
        val before = display(withReasons(reasons = setOf(backup, limits)))
        assertEquals(listOf(backupNote, limitsNote), before.healthNotes)
        assertEquals(listOf(backupNote), display(withReasons(reasons = setOf(backup))).healthNotes)
        assertEquals(listOf(limitsNote), display(withReasons(reasons = setOf(limits))).healthNotes)
        val recovered = display(ready)
        assertTrue(recovered.healthNotes.isEmpty())
        assertEquals(before.status, recovered.status)
        assertEquals(before.detail, recovered.detail)
        assertEquals(before.session, recovered.session)
        assertEquals(recovered.detail, OngoingNotificationPlan.from(recovered, now).text)
    }

    @Test fun staleUnknownAndUntrustedObservationsWithdrawNotesAndNotificationWords() {
        val source = withReasons(reasons = setOf(backup, limits))
        val stale = listOf(
            source.copy(observedAt = null), source.copy(validUntilElapsedRealtimeMs = null),
            source.copy(observedAt = source.observedAt!!.copy(elapsedRealtimeMs = now + 1)),
            source.copy(observedAt = source.observedAt!!.copy(elapsedRealtimeMs = now - 60_000)),
            source.copy(validUntilElapsedRealtimeMs = now),
            source.copy(activeCauses = source.activeCauses.map { it.copy(timeUncertain = true) }),
            C.UNKNOWN.copy(detectors = source.detectors),
        )
        for (status in stale) {
            val result = display(status)
            assertTrue(result.healthNotes.isEmpty())
            assertNull(result.session)
            val plan = OngoingNotificationPlan.from(result, now)
            assertFalse(plan.text.contains("Usage access"))
        }
        for (status in listOf(source, source.copy(readiness = C.Readiness.UNAVAILABLE))) {
            assertTrue(display(status, 130_000).healthNotes.isEmpty())
        }
        val displayed = display(source)
        assertFalse(OngoingNotificationPlan.from(displayed, 130_000).text.contains("Usage access"))
    }
}
