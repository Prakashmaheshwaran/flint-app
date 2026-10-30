package com.flint.peakfocus.feature.blocklist

import com.flint.peakfocus.blocking.engine.AnchoredInterval
import com.flint.peakfocus.blocking.overlay.ProtectionStatus
import com.flint.peakfocus.core.datastore.*
import com.flint.peakfocus.core.model.*
import org.junit.Assert.*
import org.junit.Test

class TimerArmingTest {
    private val base = 1_793_181_600_000L
    private val id = CONTRACT_TIMER_ID_PREFIX + "$base-test"
    private fun at(ms: Long = 0, wall: Long = ms, boot: String = "boot") = AccessClockStamp(base + wall, 10_000 + ms, boot)
    private val permissions = TimerPermissions(accessibilityEnabled = true)
    private fun attempt() = TimerArmAttempt(id, at(), at())
    private fun phase(status: ProtectionStatus, now: AccessClockStamp = at(), permission: TimerPermissions = permissions,
                      live: Boolean = true, attempt: TimerArmAttempt = attempt()) = timerArmingState(attempt, status, permission, live, now)

    @Test fun `PC-61 absent permissions and granted but unbound service cannot arm`() {
        val checked = readyTimerStatus(at(), listOf(id))
        assertTrue(phase(checked, permission = TimerPermissions()) is TimerArmingState.Degraded)
        assertTrue(phase(checked, live = false) is TimerArmingState.Degraded)
        assertTrue(phase(checked.copy(detectors = checked.detectors.copy(accessibilityConnected = false))) is TimerArmingState.Degraded)
    }

    @Test fun `PC-61 Path B needs Usage Access and live enforcing mode without Accessibility or overlay`() {
        val checked = readyTimerStatus(at(), listOf(id))
        val fallback = TimerPermissions(usageAccess = true)
        for (mode in ProtectionStatus.PathBState.entries) {
            val status = checked.copy(detectors = checked.detectors.copy(accessibilityConnected = false, pathB = mode))
            assertEquals(mode.name, mode == ProtectionStatus.PathBState.ENFORCING,
                phase(status, permission = fallback, live = false) is TimerArmingState.Armed)
        }
        val enforcing = checked.copy(detectors = checked.detectors.copy(pathB = ProtectionStatus.PathBState.ENFORCING))
        assertTrue(phase(enforcing, permission = fallback.copy(usageAccess = false), live = false) is TimerArmingState.Degraded)
    }

    @Test fun `PC-61 PC-73 stale expired future or wrong-boot checks cannot arm`() {
        val checked = readyTimerStatus(at(), listOf(id))
        val invalid = listOf(ProtectionStatus.UNKNOWN, checked.copy(readiness = ProtectionStatus.Readiness.LOADING),
            checked.copy(observedAt = null),
            checked.copy(observedAt = at(1)), checked.copy(observedAt = at(boot = "old")))
        invalid.forEach { assertTrue(it.toString(), phase(it) is TimerArmingState.Degraded) }
        assertTrue(phase(checked, now = at(2_000)) is TimerArmingState.Checking)
        assertTrue(phase(checked, now = at(4_001)) is TimerArmingState.Degraded)
    }

    @Test fun `PC-53 sticky floor resolution cannot hide failed incomplete or stale role checks`() {
        val checked = readyTimerStatus(at(), listOf(id))
        val floor = checked.safetyFloor!!
        listOf(checked.copy(safetyFloorResolved = false), checked.copy(safetyFloor = null),
            checked.copy(safetyFloor = floor.copy(degraded = true)),
            checked.copy(safetyFloor = floor.copy(failedSources = setOf("home"))),
            checked.copy(safetyFloor = floor.copy(resolvedSources = setOf("dialer"))),
            checked.copy(safetyFloor = floor.copy(checkedAtElapsedRealtimeMs = null)),
            checked.copy(safetyFloor = floor.copy(checkedAtElapsedRealtimeMs = at(1).elapsedRealtimeMs)))
            .forEach { assertTrue(phase(it) is TimerArmingState.Degraded) }
        assertTrue(phase(readyTimerStatus(at(30_000), listOf(id)).copy(safetyFloor = floor), now = at(30_000)) is TimerArmingState.Degraded)
    }

    @Test fun `PC-01 PC-65 durable save and wrong-ID or pre-commit observations remain Applying`() {
        assertTrue(phase(readyTimerStatus(at())) is TimerArmingState.Applying)
        assertTrue(phase(readyTimerStatus(at(), listOf("other"))) is TimerArmingState.Applying)
        assertTrue(phase(readyTimerStatus(at(), listOf(id)), attempt = attempt().copy(committedAt = at(1))) is TimerArmingState.Applying)
        val checked = readyTimerStatus(at(), listOf(id))
        assertTrue(phase(checked.copy(activeCauses = checked.activeCauses.map { it.copy(kind = ProtectionStatus.CauseKind.SCHEDULE) })) is TimerArmingState.Applying)
    }

    @Test fun `PC-01 fresh exact-ID backend acknowledgement is Armed with the actual observation`() {
        val observed = at(10)
        assertEquals(TimerArmingState.Armed(id, observed), phase(readyTimerStatus(observed, listOf(id)), now = observed))
    }

    @Test fun `PC-65 missing confirmation has a bounded Applying window`() {
        assertTrue(phase(readyTimerStatus(at(5_999)), now = at(5_999)) is TimerArmingState.Applying)
        val failed = phase(readyTimerStatus(at(6_000)), now = at(6_000)) as TimerArmingState.Degraded
        assertTrue(failed.committed); assertTrue(failed.reason.contains("saved end time is unchanged"))
    }

    @Test fun `PC-65 committed application failure stays degraded until positive backend proof`() {
        val failed = attempt().copy(applyFailure = "Apply failed; recovery needed")
        assertEquals("Apply failed; recovery needed", (phase(readyTimerStatus(at()), attempt = failed) as TimerArmingState.Degraded).reason)
        assertTrue(phase(readyTimerStatus(at(), listOf(id)), attempt = failed) is TimerArmingState.Armed)
    }

    @Test fun `PC-77 lost detector or missing acknowledged cause degrades an already armed Timer`() {
        val armed = attempt().copy(hadArmed = true)
        assertTrue(phase(readyTimerStatus(at(1)), now = at(1), attempt = armed) is TimerArmingState.Degraded)
        assertTrue(phase(readyTimerStatus(at(1), listOf(id)), now = at(1), attempt = armed, live = false) is TimerArmingState.Degraded)
    }

    @Test fun `PC-58 restored Timer can use current proof but a cross-boot new-start acknowledgement cannot`() {
        val reboot = at(1, boot = "reboot")
        val status = readyTimerStatus(reboot, listOf(id))
        assertTrue(phase(status, now = reboot) is TimerArmingState.Degraded)
        assertTrue(phase(status, now = reboot, attempt = TimerArmAttempt(id, null, reboot)) is TimerArmingState.Armed)
    }

    @Test fun `PC-65 absent finite endpoint or logically ended backend cause cannot arm`() {
        val checked = readyTimerStatus(at(), listOf(id))
        listOf(checked.activeCauses.single().copy(remainingMs = null), checked.activeCauses.single().copy(remainingMs = 0),
            checked.activeCauses.single().copy(logicalEndElapsedRealtimeMs = null),
            checked.activeCauses.single().copy(logicalEndElapsedRealtimeMs = at().elapsedRealtimeMs))
            .forEach { assertTrue(phase(checked.copy(activeCauses = listOf(it))) is TimerArmingState.Applying) }
    }

    @Test fun `PC-46 active global recovery requires explicit resume before a new Timer`() {
        val status = readyTimerStatus(at()).copy(pass = ProtectionStatus.PassStatus.RecoveryActive(60_000, at().elapsedRealtimeMs + 60_000))
        assertEquals("Resume protection first.", timerPrerequisiteFailure(status, permissions, true, at()))
    }

    @Test fun `PC-54 PC-55 anchored Timer display ignores wall edits and ends at the elapsed deadline`() {
        val rule = timerRule(TimerDraft(setOf(AppRef("app")), durationMinutes = 5), base, "test")
        val state = EditorCommitmentState(causes = mapOf(ruleCauseKey(rule.id) to EditorCause(rule.id,
            AnchoredInterval(at(), 300_000, base + 300_000), "UTC")))
        for (wall in listOf(-86_400_000L, 86_400_000L)) {
            val now = at(100_000, wall)
            val displayed = anchoredTimerSessions(listOf(rule), state, now, now.wallEpochMs).single()
            assertEquals(200_000L, displayed.expiresAtEpochMs!! - now.wallEpochMs)
        }
        assertTrue(anchoredTimerSessions(listOf(rule), state, at(300_000, -86_400_000), base - 86_400_000).isEmpty())
        assertEquals(base + 300_000, rule.expiresAtEpochMs)
    }

    @Test fun `PC-73 short stale grace is Checking with no assurance and longer staleness is degraded`() {
        val status = readyTimerStatus(at(), listOf(id))
        val armed = attempt().copy(hadArmed = true)
        assertTrue(phase(status, now = at(1_999), attempt = armed) is TimerArmingState.Armed)
        assertTrue(phase(status, now = at(2_000), attempt = armed) is TimerArmingState.Checking)
        assertTrue(phase(status, now = at(4_000), attempt = armed) is TimerArmingState.Checking)
        assertTrue(phase(status, now = at(4_001), attempt = armed) is TimerArmingState.Degraded)
        assertNotNull(timerPrerequisiteFailure(status, permissions, true, at(2_000))) // A new start cannot use the grace.
        assertTrue(phase(status, now = at(2_001), permission = TimerPermissions(), attempt = armed) is TimerArmingState.Degraded)
    }

    @Test fun `PC-46 unavailable spent or unused pass status alone never refuses a healthy new Timer`() {
        for (pass in listOf(ProtectionStatus.PassStatus.Unavailable, ProtectionStatus.PassStatus.Available,
            ProtectionStatus.PassStatus.CoolingDown(base + 604_800_000, false))) {
            assertNull(timerPrerequisiteFailure(readyTimerStatus(at()).copy(pass = pass), permissions, true, at()))
        }
    }

    @Test fun `PC-61 cause-less session rows use the same activity predicate for capacity and display`() {
        val future = timerRule(TimerDraft(setOf(AppRef("app")), durationMinutes = 5), base, "cold")
        for (now in listOf(at(), at(300_000))) {
            assertEquals(isActiveEditorSession(future, null, now), anchoredTimerSessions(listOf(future), null, now, now.wallEpochMs).isNotEmpty())
        }
    }
}
