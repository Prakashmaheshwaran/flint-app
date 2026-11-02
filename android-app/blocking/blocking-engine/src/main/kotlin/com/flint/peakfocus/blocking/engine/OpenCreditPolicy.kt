package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.AccessClockStamp

/**
 * ADR-012 §5 Open Limits as **timed credits**: the target stays shielded between grants (including
 * before the first credit); an accepted open is an explicit "Use one credit · 5 minutes" that
 * grants 300 s of elapsed access, clipped at the quota reset. One daily counter per target is
 * shared by every covering Open rule, whose effective cap is the minimum of their caps, so
 * duplicate rules never multiply credits and editing/re-creating a rule never resets the count.
 *
 * The reset is a **trusted** boundary (§9): the counter carries the remaining time to its reset,
 * checkpointed on the monotonic clock. A same-boot wall-clock edit cannot mint a fresh quota; after
 * a reboot the remaining-to-reset resumes on the new clock with no credit for powered-off time,
 * and exactly one reset happens at that deadline (missed resets are never replayed).
 *
 * Every accepted operation id is recorded on the counter for its period, so a duplicate callback
 * or retry never charges twice — even after its grant expired and was pruned.
 *
 * Pure: run [accept] inside one durable transaction (counter + grant together) and honor only the
 * committed result. Nothing is charged when another cause still blocks the target, for a
 * duplicate, a floor target, or unavailable state.
 */
class OpenCreditPolicy {

    /** Time to the counter's reset, measured from [checkpoint] on the monotonic clock. */
    data class ResetClock(val checkpoint: AccessClockStamp, val remainingToResetMs: Long)

    /** Shared per-target counter for one quota period. */
    data class Counter(
        val target: String,
        val periodId: Long,
        val used: Int,
        val reset: ResetClock,
        val consumedOperationIds: Set<String> = emptySet(),
        /** The previous period's accepted ids, so a duplicate arriving just after a reset is still no charge. */
        val previousPeriodOperationIds: Set<String> = emptySet(),
    )

    data class Grant(
        val target: String,
        val operationId: String,
        val startedAt: AccessClockStamp,
        val durationMs: Long,
    )

    enum class Denial {
        /** Counter/grant state is corrupt, unreadable or of an unknown version (§8). */
        UNAVAILABLE,
        SAFETY_FLOOR,
        NO_LIMIT,
        ZERO_CAP,
        EXHAUSTED,
        BLOCKED_BY_OTHER_CAUSE,

        /** Exactly at the quota reset instant: reset first, then retry (not a failure). */
        PERIOD_BOUNDARY,
    }

    sealed interface Acceptance {
        /** Persist [counter] and [grant] together; [remainingCredits] may be 0 for the last credit. */
        data class Accepted(val counter: Counter, val grant: Grant, val remainingCredits: Int) : Acceptance

        /** A live grant already covers the target: same remaining interval, no spend (PC-23). */
        data class AlreadyActive(val grant: Grant, val remainingMs: Long) : Acceptance

        /** [operationId] was already accepted this period: no spend; [liveGrant] if still running (PC-31). */
        data class Duplicate(val operationId: String, val liveGrant: Grant?) : Acceptance

        /** [blockingContributors] names the other causes that still block (PC-32). */
        data class Denied(val reason: Denial, val blockingContributors: List<String> = emptyList()) : Acceptance
    }

    /** Remaining access on [grant]; 0 after its deadline or after a reboot (§9). */
    fun remainingMs(grant: Grant, now: AccessClockStamp): Long {
        val elapsed = trustedElapsedMs(grant.startedAt, now) ?: return 0L
        return (grant.durationMs - elapsed).coerceAtLeast(0L)
    }

    /** Remaining time to [reset] at [now]: same boot subtracts elapsed; across a reboot it resumes unchanged. */
    fun remainingToResetMs(reset: ResetClock, now: AccessClockStamp): Long {
        val elapsed = trustedElapsedMs(reset.checkpoint, now) ?: 0L
        return (reset.remainingToResetMs - elapsed).coerceAtLeast(0L)
    }

    /**
     * Re-anchor [counter] on [now] and apply at most one reset: when the trusted remaining time
     * reaches zero, start the next period at zero used with the next reset [msUntilNextQuotaMidnight]
     * from now (computed by the caller in the saved quota zone). Persist the result.
     */
    fun rolled(counter: Counter, now: AccessClockStamp, msUntilNextQuotaMidnight: Long): Counter {
        val remaining = remainingToResetMs(counter.reset, now)
        if (remaining > 0L) return counter.copy(reset = ResetClock(now, remaining))
        // Unknown next boundary: do not reset (no loop of resets); accept reports PERIOD_BOUNDARY.
        if (msUntilNextQuotaMidnight <= 0L) return counter.copy(reset = ResetClock(now, 0L))
        return Counter(
            target = counter.target,
            periodId = counter.periodId + 1,
            used = 0,
            reset = ResetClock(now, msUntilNextQuotaMidnight),
            previousPeriodOperationIds = counter.consumedOperationIds,
        )
    }

    /**
     * @param caps the daily caps of every enabled Open rule covering [target] now.
     * @param counter the shared counter, or null when storage is not ready (fail closed).
     * @param liveGrant the target's current grant, if any.
     * @param otherBlockingCauses non-Open causes that would still block the target (sessions,
     *   schedules, exhausted Time Limits, unprovable overlaps) — any of them means no charge.
     */
    fun accept(
        target: String,
        operationId: String,
        caps: List<Int>,
        counter: Counter?,
        liveGrant: Grant?,
        otherBlockingCauses: List<String>,
        onSafetyFloor: Boolean,
        now: AccessClockStamp,
        msUntilNextQuotaMidnight: Long,
    ): Acceptance {
        if (onSafetyFloor) return Acceptance.Denied(Denial.SAFETY_FLOOR)
        val stored = counter?.takeIf { it.target == target } ?: return Acceptance.Denied(Denial.UNAVAILABLE)
        val current = rolled(stored, now, msUntilNextQuotaMidnight)
        val live = liveGrant?.takeIf { it.target == target && remainingMs(it, now) > 0L }
        if (operationId in current.consumedOperationIds || operationId in current.previousPeriodOperationIds) {
            return Acceptance.Duplicate(operationId, live)
        }
        if (live != null) return Acceptance.AlreadyActive(live, remainingMs(live, now))
        if (otherBlockingCauses.isNotEmpty()) {
            return Acceptance.Denied(Denial.BLOCKED_BY_OTHER_CAUSE, otherBlockingCauses)
        }
        val cap = caps.minOrNull() ?: return Acceptance.Denied(Denial.NO_LIMIT)
        if (cap <= 0) return Acceptance.Denied(Denial.ZERO_CAP)
        if (current.used >= cap) return Acceptance.Denied(Denial.EXHAUSTED)
        val duration = minOf(ProtectionContract.OPEN_CREDIT_MS, current.reset.remainingToResetMs)
        if (duration <= 0L) return Acceptance.Denied(Denial.PERIOD_BOUNDARY)
        val next = current.copy(used = current.used + 1, consumedOperationIds = current.consumedOperationIds + operationId)
        return Acceptance.Accepted(
            counter = next,
            grant = Grant(target, operationId, now, duration),
            remainingCredits = cap - next.used,
        )
    }

    /** Credits left this period for display; null when no Open rule covers the target. */
    fun remainingCredits(caps: List<Int>, counter: Counter, now: AccessClockStamp, msUntilNextQuotaMidnight: Long): Int? =
        caps.minOrNull()?.let { (it - rolled(counter, now, msUntilNextQuotaMidnight).used).coerceAtLeast(0) }
}
