package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.core.datastore.ProtectionSnapshot
import com.flint.peakfocus.core.model.AccessClockStamp
import com.flint.peakfocus.core.model.BlockRule

/** #178's pure read/view only: never requests, checkpoints, reconciles or consumes a credit. */
internal object TargetCreditProjection {
    fun read(base: OpenCreditView?, snapshot: ProtectionSnapshot, input: OrdinaryProtectionInput?,
        foreign: List<BlockRule>, now: AccessClockStamp, runtimeKnown: Boolean,
        limitsKnown: Boolean, pendingReason: String?, causes: List<CoveringCause>,
        recoveryEndsAtWall: Long?): OpenCreditView? {
        base ?: return null
        val credit = if (base.readable && runtimeKnown && limitsKnown && pendingReason == null && input != null &&
            snapshot.focus.protection != null) TimedOpenCreditPolicy.view(snapshot, input, foreign, now) else null
        val others = causes.filter { it.kind != CauseKind.OPEN_LIMIT && it.active }
        val reason = when {
            !base.readable -> "open-credit-state-unavailable"
            pendingReason != null -> pendingReason
            !runtimeKnown -> "target-runtime-unconfirmed"
            !limitsKnown -> "target-limit-state-unavailable"
            recoveryEndsAtWall != null -> "target-recovery-active"
            credit == null -> "open-credit-scope-unavailable"
            credit.blockingContributors.isNotEmpty() || others.any { it.enforcingNow == true } -> "other-contributor-blocks"
            credit.activeRemainingMs > 0 -> "open-credit-active"
            credit.durationMs <= 0 -> "open-credit-period-pending"
            credit.remainingCredits <= 0 -> "open-credit-exhausted"
            else -> null
        }
        val active = credit?.activeRemainingMs?.takeIf { it > 0 && recoveryEndsAtWall == null }
        return base.copy(available = reason == null, unavailableReason = reason,
            activeCreditEndsAtWall = active?.let { if (now.wallEpochMs > Long.MAX_VALUE - it) Long.MAX_VALUE else now.wallEpochMs + it })
    }
}
