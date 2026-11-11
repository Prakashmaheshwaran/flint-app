package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.blocking.engine.*
import com.flint.peakfocus.core.datastore.*
import com.flint.peakfocus.core.model.*
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

/**
 * Red team R-30 (sibling surface): does a reported block-surface mount failure reach the
 * *per-target* enforcement projection, or does the app-detail reader keep claiming
 * "covering cause is enforcing now / blocked now" after the adapter said it delivered nothing?
 *
 * ADR-012 §8 ("An accepted OS registration is not physical proof that every target is blocked";
 * "UI success ... never promises pixels the adapter cannot observe") and §11
 * (`protection_degraded` coverage gap) are the contract. Glance and the Timer were wired for
 * this in PR #249; [TargetRuntimeHealth] — the only gate the target projection consults — was not.
 */
class RedTeamR30TargetProjectionTest {
    private val now = AccessClockStamp(1_800_000_000_000, 1_000, "boot")
    private val pkg = "com.example.rt30"
    private val end = now.wallEpochMs + 600_000

    private fun rule(id: String = "session-timer-v2-rt30") =
        BlockRule(id, id, BlockTargets(apps = setOf(AppRef(pkg))),
            breakLevel = BreakLevel.HARDCORE, expiresAtEpochMs = end)

    private fun snapshot(rule: BlockRule): ProtectionSnapshot {
        val quota = OpenCreditLedgerPolicy.initial(now, "UTC")
        val cause = EditorCause(rule.id, AnchoredInterval(now, end - now.wallEpochMs, end), "UTC")
        return ProtectionSnapshot(
            FocusSnapshot(protection = ProtectionLedger(now, now)), listOf(rule), emptyList(), emptyList(),
            EditorCommitmentState(causes = mapOf(ruleCauseKey(rule.id) to cause), timeline = now),
            HarderWaitPolicy.Ledger(), now, quota,
        )
    }

    private fun environment(reasons: Set<String> = emptySet()) = TargetReadEnvironment(
        status = ProtectionStatus.UNKNOWN.copy(
            readiness = ProtectionStatus.Readiness.READY, observedAt = now,
            validUntilElapsedRealtimeMs = now.elapsedRealtimeMs + 2_000, safetyFloorResolved = true,
            detectors = ProtectionStatus.DetectorHealth(true, ProtectionStatus.PathBState.STANDBY, reasons)),
    )

    private fun read(stored: ProtectionSnapshot, env: TargetReadEnvironment): TargetProtection {
        val storedReady = StorageState.Ready(stored)
        return TargetProtectionProjection.read(TargetRef.App(pkg), storedReady,
            env.copy(publishedRules = StorageState.Ready(stored.projectedRulesForRead(now))),
            now, "flint", ZoneId.of("UTC"))
    }

    /** Control: the fixture really is a "blocked now / enforcing now" projection when healthy. */
    @Test fun RedTeam_R30_control_aHealthySurfaceProjectsTheTimerAsBlockedNow() {
        val view = read(snapshot(rule()), environment())
        assertEquals(Readiness.Ready, view.readiness)
        assertEquals(true, view.blockingNow)
        assertEquals(true, view.coveringCauses.single().enforcingNow)
        assertEquals(CauseKind.TIMER, view.headline!!.kind)
    }

    /**
     * The attack: the adapter reported that the last real shield mount delivered nothing.
     * [TargetRuntimeHealth.unavailableReason] still answers "healthy" for that exact status, so
     * `enforcingNow` (and therefore `blockingNow`/the app-detail "Blocked" state) stays true.
     */
    @Test fun RedTeam_R30_targetProjectionMustNotClaimEnforcementAfterTheAdapterReportedAFailedMount() {
        val stored = snapshot(rule())
        for (reason in listOf(ProtectionStatus.REASON_BLOCK_SURFACE_MOUNT_FAILED,
            ProtectionStatus.REASON_BLOCK_SURFACE_FALLBACK_ONLY)) {
            val env = environment(setOf(reason))
            val view = read(stored, env)
            val violations = buildList {
                if (TargetRuntimeHealth.unavailableReason(env.status, now) == null)
                    add("TargetRuntimeHealth reports no health fault for $reason")
                if (view.coveringCauses.single().enforcingNow == true)
                    add("the covering Timer cause still claims enforcingNow=true")
                if (view.blockingNow == true)
                    add("the target still claims blockingNow=true (app detail renders 'Blocked')")
            }
            assertTrue("$reason: a reported mount failure must not be projected as live enforcement: $violations",
                violations.isEmpty())
        }
    }
}
