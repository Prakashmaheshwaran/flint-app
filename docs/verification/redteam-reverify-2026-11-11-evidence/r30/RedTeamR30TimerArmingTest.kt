package com.flint.peakfocus.feature.blocklist

import com.flint.peakfocus.blocking.overlay.ProtectionStatus
import com.flint.peakfocus.core.datastore.CONTRACT_TIMER_ID_PREFIX
import com.flint.peakfocus.core.model.AccessClockStamp
import org.junit.Assert.*
import org.junit.Test

/**
 * Red team R-30 (Timer contract): a published block-surface mount failure must always beat
 * "Armed", for every attempt shape, at every phase of the freshness grace, and for every Timer
 * the process might be showing. ADR-012 §8 (Applying/degraded rather than success) applies.
 */
class RedTeamR30TimerArmingTest {
    private val base = 1_793_181_600_000L
    private val id = CONTRACT_TIMER_ID_PREFIX + "$base-rt30"
    private val otherId = CONTRACT_TIMER_ID_PREFIX + "$base-other"
    private fun at(ms: Long = 0, boot: String = "boot") = AccessClockStamp(base + ms, 10_000 + ms, boot)
    private val permissions = TimerPermissions(accessibilityEnabled = true)
    private val failed = ProtectionStatus.REASON_BLOCK_SURFACE_MOUNT_FAILED
    private val fallbackOnly = ProtectionStatus.REASON_BLOCK_SURFACE_FALLBACK_ONLY

    private fun status(vararg reasons: String, ids: List<String> = listOf(id)) =
        readyTimerStatus(at(), ids).let { checked ->
            checked.copy(detectors = checked.detectors.copy(degradedReasons = reasons.toSet()))
        }

    private fun attempt() = TimerArmAttempt(id, at(), at())
    private fun phase(status: ProtectionStatus, now: AccessClockStamp = at(), attempt: TimerArmAttempt = attempt()) =
        timerArmingState(attempt, status, permissions, livePathA = true, now = now)

    /** Control: with no mount fault the same fixture really is Armed. */
    @Test fun RedTeam_R30_control_theFixtureArmsWithoutAMountFault() {
        assertTrue(phase(status()) is TimerArmingState.Armed)
    }

    /** The merged claim: no attempt shape may reach Armed while the record says FAILED/FALLBACK. */
    @Test fun RedTeam_R30_noAttemptShapeReachesArmedWhileTheMountRecordIsFailing() {
        for (reason in listOf(failed, fallbackOnly)) {
            val degradedStatus = status(reason)
            val shapes = listOf(
                attempt(),                                                     // fresh commit
                attempt().copy(committedAt = null),                            // restored Timer
                attempt().copy(hadArmed = true),                               // already Armed yesterday
                attempt().copy(applyFailure = "Apply failed; recovery needed"),// adapter apply error too
                attempt().copy(waitingSince = at(-60_000)),                    // long past the Applying window
            )
            // 2_000..4_000 is the snapshot-staleness grace: "Checking protection…" is allowed
            // there (never Armed), a fresh or clearly stale check must be Degraded.
            for (now in listOf(at(), at(1_999), at(2_000), at(2_001), at(4_001), at(60_000))) {
                val grace = now.elapsedRealtimeMs in at(2_000).elapsedRealtimeMs..at(4_000).elapsedRealtimeMs
                val staleSnapshot = now.elapsedRealtimeMs > at(4_000).elapsedRealtimeMs
                for (shape in shapes) {
                    val state = phase(degradedStatus, now = now, attempt = shape)
                    assertFalse("$reason/$now/$shape armed over a failing mount",
                        state is TimerArmingState.Armed)
                    if (grace) continue
                    val degraded = state as TimerArmingState.Degraded
                    assertTrue("$reason: the saved plan must survive", degraded.committed)
                    if (!staleSnapshot) assertTrue("$reason: degraded copy must name the unchanged end time",
                        degraded.reason.contains("saved end time is unchanged"))
                }
            }
        }
    }

    /** ADR §8: a start is still attempted, but the result may never read Armed. */
    @Test fun RedTeam_R30_aTimerStartedWhileTheSurfaceFailsIsSavedButNeverReadsArmed() {
        for (reason in listOf(failed, fallbackOnly)) {
            val degradedStatus = status(reason)
            // Start stays available: refusing a new Timer would only remove protection.
            assertNull(timerPrerequisiteFailure(degradedStatus, permissions, true, at()))
            assertTrue(phase(degradedStatus) is TimerArmingState.Degraded)
        }
    }

    /** A failing surface is process-wide: no second/other Timer may borrow Armed from it. */
    @Test fun RedTeam_R30_aSecondArmedTimerCannotBorrowArmedWhileTheSurfaceFails() {
        val status = status(failed, ids = listOf(id, otherId))
        for (timerId in listOf(id, otherId)) {
            val degraded = phase(status, attempt = TimerArmAttempt(timerId, at(), at())) as TimerArmingState.Degraded
            assertTrue(degraded.committed)
        }
    }

    /** The fallback Activity request is not delivery proof and gets its own honest copy. */
    @Test fun RedTeam_R30_fallbackOnlyCopyDoesNotClaimEnforcement() {
        val degraded = phase(status(fallbackOnly)) as TimerArmingState.Degraded
        assertTrue(degraded.reason.contains("blocking may not appear"))
        assertFalse(degraded.reason.contains("Flint couldn't show its block screen"))
    }
}
