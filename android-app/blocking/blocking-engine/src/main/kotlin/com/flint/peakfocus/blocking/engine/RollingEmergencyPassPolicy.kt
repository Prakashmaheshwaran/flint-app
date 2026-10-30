package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.AccessClockStamp

/**
 * ADR-012 §6 Emergency Pass: one free pass per rolling 604,800 s after committed use — no Monday
 * refill, no bank — and a confirmed pass starts 60 minutes of **global** personal-protection
 * recovery. Pure: callers run [consume] inside one durable transaction with the recovery record
 * and the session terminations ("consume then release"), and release protection only after that
 * commit. This class decides eligibility and the records to persist; it never releases anything.
 *
 * Clock policy (§9): in one boot, monotonic elapsed time governs the cooldown, so wall-clock edits
 * cannot shorten it. Across a reboot no elapsed duration is trusted: the cooldown resumes from its
 * last checkpointed remaining time on the new clock (powered-off time is not credited — renewal can
 * be later than seven calendar days, never earlier), and an active recovery interval expires.
 */
class RollingEmergencyPassPolicy {

    /** Durable pass state. [Unavailable] (corrupt/future/unreadable) never becomes a fresh pass (§6, PC-47). */
    sealed interface PassState {
        data object Unavailable : PassState

        /** [cooldown] null = never used, or a cooldown that already elapsed and was cleared. */
        data class Known(val cooldown: Cooldown? = null, val recovery: Recovery? = null) : PassState
    }

    /** Remaining cooldown as of [checkpoint]; [operationId] is the committed use it belongs to. */
    data class Cooldown(
        val operationId: String,
        val usedAtWallEpochMs: Long,
        val checkpoint: AccessClockStamp,
        val remainingAtCheckpointMs: Long,
        /** Sticky: a clock discontinuity or reboot was observed since the use (survives re-anchoring). */
        val wallUncertain: Boolean = false,
    )

    /**
     * The full scope a pass recovers (§6): every active personal cause it suspends, and the
     * transient sessions it ends early (`ended_early`, reason `emergency_pass`). Built by the
     * caller from the materialized occurrences; an incomplete/unreadable scope must not be passed.
     */
    data class RecoveryScope(val affectedCauseIds: Set<String>, val transientsToEnd: Set<String>)

    /** Global recovery started by [operationId] at [startedAt] over [scope]. */
    data class Recovery(
        val operationId: String,
        val startedAt: AccessClockStamp,
        val scope: RecoveryScope,
        val durationMs: Long = ProtectionContract.PASS_RECOVERY_MS,
    )

    sealed interface Eligibility {
        data object Available : Eligibility

        /** [nextEligibleEstimateEpochMs] is an estimate; [clockUncertain] after a reboot. */
        data class CoolingDown(
            val remainingMs: Long,
            val nextEligibleEstimateEpochMs: Long,
            val clockUncertain: Boolean,
        ) : Eligibility

        data object Unavailable : Eligibility
    }

    enum class Denial { UNAVAILABLE, COOLING_DOWN, NOTHING_TO_RECOVER }

    sealed interface Consumption {
        /** Persist [state] (with the recovery and terminations) in one transaction, then release. */
        data class Consumed(val state: PassState.Known, val recovery: Recovery) : Consumption

        /** The same operation already committed: return its interval, never spend twice (PC-45). */
        data class AlreadyConsumed(val recovery: Recovery?) : Consumption

        data class Denied(val reason: Denial, val eligibility: Eligibility) : Consumption
    }

    fun eligibility(state: PassState, now: AccessClockStamp): Eligibility {
        val known = state as? PassState.Known ?: return Eligibility.Unavailable
        val cooldown = known.cooldown ?: return Eligibility.Available
        val elapsed = trustedElapsedMs(cooldown.checkpoint, now)
        val remaining = (cooldown.remainingAtCheckpointMs - (elapsed ?: 0L)).coerceAtLeast(0L)
        if (remaining == 0L) return Eligibility.Available
        return Eligibility.CoolingDown(
            remainingMs = remaining,
            nextEligibleEstimateEpochMs = now.wallEpochMs + remaining,
            // A reboot, or a same-boot wall/elapsed discrepancy (a clock edit): the remaining time
            // is still exact, but the wall-clock date estimate is not (§9, PC-55).
            clockUncertain = cooldown.wallUncertain || clockDiscontinuity(cooldown.checkpoint, now),
        )
    }

    /**
     * Re-anchor [state] on [now]'s clock (call on every policy entry and persist the result): a
     * finished cooldown clears, a cross-boot cooldown keeps its remaining time on the new boot,
     * and a recovery interval that ended — or that cannot be measured after a reboot — clears.
     */
    fun checkpointed(state: PassState, now: AccessClockStamp): PassState {
        val known = state as? PassState.Known ?: return state
        val cooldown = known.cooldown?.let { c ->
            val remaining = (eligibility(known, now) as? Eligibility.CoolingDown)?.remainingMs ?: return@let null
            c.copy(
                checkpoint = now,
                remainingAtCheckpointMs = remaining,
                wallUncertain = c.wallUncertain || clockDiscontinuity(c.checkpoint, now),
            )
        }
        val recovery = known.recovery?.takeIf { recoveryRemainingMs(it, now) > 0L }
        return PassState.Known(cooldown, recovery)
    }

    /** Remaining recovery; 0 after its end or after a reboot (§9: expire conservatively). */
    fun recoveryRemainingMs(recovery: Recovery, now: AccessClockStamp): Long {
        val elapsed = trustedElapsedMs(recovery.startedAt, now) ?: return 0L
        return (recovery.durationMs - elapsed).coerceAtLeast(0L)
    }

    /**
     * Spend the pass for [operationId]. §6: spend only when at least one personal commitment is
     * active **and the full recovery scope is known** — pass null for [scope] when it could not be
     * resolved (→ UNAVAILABLE), an empty affected set when nothing is active (→ NOTHING_TO_RECOVER).
     * A retry of the committed operation returns its recovery; a different request during cooldown
     * or recovery is denied and never extends anything.
     */
    fun consume(
        state: PassState,
        now: AccessClockStamp,
        operationId: String,
        scope: RecoveryScope?,
    ): Consumption {
        val known = state as? PassState.Known
            ?: return Consumption.Denied(Denial.UNAVAILABLE, Eligibility.Unavailable)
        if (known.cooldown?.operationId == operationId) {
            return Consumption.AlreadyConsumed(known.recovery?.takeIf { recoveryRemainingMs(it, now) > 0L })
        }
        val eligibility = eligibility(known, now)
        if (eligibility != Eligibility.Available) return Consumption.Denied(Denial.COOLING_DOWN, eligibility)
        if (scope == null) return Consumption.Denied(Denial.UNAVAILABLE, eligibility)
        if (scope.affectedCauseIds.isEmpty()) return Consumption.Denied(Denial.NOTHING_TO_RECOVER, eligibility)
        val recovery = Recovery(operationId = operationId, startedAt = now, scope = scope)
        val cooldown = Cooldown(
            operationId = operationId,
            usedAtWallEpochMs = now.wallEpochMs,
            checkpoint = now,
            remainingAtCheckpointMs = ProtectionContract.PASS_COOLDOWN_MS,
        )
        return Consumption.Consumed(PassState.Known(cooldown, recovery), recovery)
    }

    /** "Resume now" (§6): truncate recovery without refunding the pass. */
    fun resumeNow(state: PassState.Known): PassState.Known = state.copy(recovery = null)
}
