package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.AccessClockStamp

/**
 * ADR-012 §5 Open Limits as **timed credits**: the target stays shielded between grants (including
 * before the first credit); an accepted open is an explicit "Use one credit · 5 minutes" that
 * grants 300 s of elapsed access, clipped at the quota midnight. One daily counter per target is
 * shared by every covering Open rule, whose effective cap is the minimum of their caps, so
 * duplicate rules never multiply credits and editing/re-creating a rule never resets the count.
 *
 * Pure: run [accept] inside one durable transaction (increment + grant + idempotency key) and
 * honor only the committed result. Nothing is charged when another cause still blocks the target,
 * for a duplicate request, a floor target, or unavailable state.
 */
class OpenCreditPolicy {

    /** Shared per-target counter for one quota period (local day in the saved quota zone). */
    data class Counter(val target: String, val periodId: Long, val used: Int)

    data class Grant(
        val target: String,
        val operationId: String,
        val startedAt: AccessClockStamp,
        val durationMs: Long,
    )

    enum class Denial { UNAVAILABLE, SAFETY_FLOOR, NO_LIMIT, ZERO_CAP, EXHAUSTED, BLOCKED_BY_OTHER_CAUSE }

    sealed interface Acceptance {
        /** Persist [counter] and [grant] together; [remainingCredits] may be 0 for the last credit. */
        data class Accepted(val counter: Counter, val grant: Grant, val remainingCredits: Int) : Acceptance

        /** A live grant already covers the target: same remaining interval, no spend (PC-23, PC-31). */
        data class AlreadyActive(val grant: Grant, val remainingMs: Long) : Acceptance

        /** [blockingContributors] names the other causes that still block (PC-32). */
        data class Denied(val reason: Denial, val blockingContributors: List<String> = emptyList()) : Acceptance
    }

    /** Remaining access on [grant]; 0 after its deadline or after a reboot (§9). */
    fun remainingMs(grant: Grant, now: AccessClockStamp): Long {
        val elapsed = trustedElapsedMs(grant.startedAt, now) ?: return 0L
        return (grant.durationMs - elapsed).coerceAtLeast(0L)
    }

    /**
     * Roll [counter] into [periodId] forward only: a later period starts at zero; the same or an
     * earlier period (clock moved back) keeps the used count — no fresh quota from a clock change.
     */
    fun rolled(counter: Counter, periodId: Long): Counter =
        if (periodId > counter.periodId) counter.copy(periodId = periodId, used = 0) else counter

    /**
     * @param caps the daily caps of every enabled Open rule covering [target] now.
     * @param counter the shared counter, or null when storage is not ready (fail closed).
     * @param liveGrant the target's current grant, if any.
     * @param otherBlockingCauses non-Open causes that would still block the target (sessions,
     *   schedules, exhausted Time Limits, unprovable overlaps) — any of them means no charge.
     * @param msUntilQuotaMidnight time to the next trusted quota-period boundary; grants clip there.
     */
    fun accept(
        target: String,
        operationId: String,
        caps: List<Int>,
        counter: Counter?,
        periodId: Long,
        liveGrant: Grant?,
        otherBlockingCauses: List<String>,
        onSafetyFloor: Boolean,
        now: AccessClockStamp,
        msUntilQuotaMidnight: Long,
    ): Acceptance {
        if (onSafetyFloor) return Acceptance.Denied(Denial.SAFETY_FLOOR)
        val current = counter?.takeIf { it.target == target }
            ?: return Acceptance.Denied(Denial.UNAVAILABLE)
        liveGrant?.takeIf { it.target == target }?.let { grant ->
            val remaining = remainingMs(grant, now)
            // A live grant, or a retry of the very operation that created it: never charge again.
            if (remaining > 0L || grant.operationId == operationId) return Acceptance.AlreadyActive(grant, remaining)
        }
        if (otherBlockingCauses.isNotEmpty()) {
            return Acceptance.Denied(Denial.BLOCKED_BY_OTHER_CAUSE, otherBlockingCauses)
        }
        val cap = caps.minOrNull() ?: return Acceptance.Denied(Denial.NO_LIMIT)
        if (cap <= 0) return Acceptance.Denied(Denial.ZERO_CAP)
        val rolled = rolled(current, periodId)
        if (rolled.used >= cap) return Acceptance.Denied(Denial.EXHAUSTED)
        val duration = minOf(ProtectionContract.OPEN_CREDIT_MS, msUntilQuotaMidnight.coerceAtLeast(0L))
        if (duration == 0L) return Acceptance.Denied(Denial.UNAVAILABLE) // at the boundary: reset first
        val next = rolled.copy(used = rolled.used + 1)
        return Acceptance.Accepted(
            counter = next,
            grant = Grant(target, operationId, now, duration),
            remainingCredits = cap - next.used,
        )
    }

    /** Credits left today for display; null when no Open rule covers the target. */
    fun remainingCredits(caps: List<Int>, counter: Counter, periodId: Long): Int? =
        caps.minOrNull()?.let { (it - rolled(counter, periodId).used).coerceAtLeast(0) }
}
