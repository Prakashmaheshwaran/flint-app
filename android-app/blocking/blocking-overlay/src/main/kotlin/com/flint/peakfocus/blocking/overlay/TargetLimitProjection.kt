package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.blocking.engine.OpenCreditPolicy
import com.flint.peakfocus.blocking.engine.clockDiscontinuity
import com.flint.peakfocus.core.datastore.ProtectionSnapshot
import com.flint.peakfocus.core.datastore.openCauseKey
import com.flint.peakfocus.core.datastore.timeCauseKey
import com.flint.peakfocus.core.model.AccessClockStamp
import com.flint.peakfocus.core.model.BreakLevel

internal data class TargetLimitFacts(
    val causes: List<CoveringCause>,
    val timeLimit: TimeLimitView?,
    val openCredit: OpenCreditView?,
    /** A configured limit without its materialized finite period is not an unprotected app. */
    val unavailableReason: String?,
)

/** Pure display projection of one app's already-decoded records. Never checkpoints the quota. */
internal object TargetLimitProjection {
    const val CREDIT_RUNTIME_UNCONFIRMED = "open-credit-runtime-unconfirmed"

    fun app(snapshot: ProtectionSnapshot, packageName: String, now: AccessClockStamp, measuredUsageMs: Long?,
        legacyMinutes: Int? = null): TargetLimitFacts {
        val time = snapshot.times.firstOrNull { it.packageName == packageName }
        val open = snapshot.opens.firstOrNull { it.packageName == packageName }
        val minutes = listOfNotNull(time?.dailyMinutes, legacyMinutes?.takeIf { it >= 0 }).minOrNull()
        if (minutes == null && open == null) return TargetLimitFacts(emptyList(), null, null, null)
        // A display read cannot publish a rollover or replace spent counts with fresh zeroes.
        val quota = snapshot.openCredits
        val creditPolicy = OpenCreditPolicy()
        val resetRemaining = quota?.let { creditPolicy.remainingToResetMs(it.reset, now) }
        val activeRemaining = quota?.active?.get(packageName)?.grant?.let {
            minOf(creditPolicy.remainingMs(it, now), resetRemaining ?: 0L)
        } ?: 0L
        val usedMs = measuredUsageMs?.takeIf { it >= 0 }
        val timeView = minutes?.let { TimeLimitView(it.toLong().coerceAtLeast(0) * 60_000L, usedMs,
            time?.breakLevel ?: snapshot.focus.defaultBreakLevel, usedMs != null) }
        val openView = open?.let { limit ->
            val remaining = quota?.let { OpenCreditPolicy().remainingToResetMs(it.reset, now) }
            OpenCreditView(
                used = quota?.let { it.counters[packageName]?.used ?: 0 },
                cap = limit.dailyOpens.coerceAtLeast(0),
                readable = quota != null,
                available = false,
                unavailableReason = if (quota == null) "open-credit-state-unavailable" else CREDIT_RUNTIME_UNCONFIRMED,
                // The full projector verifies runtime health and exact overlap before exposing it.
                activeCreditEndsAtWall = null,
                periodEndsAtWall = remaining?.let { addBounded(now.wallEpochMs, it) },
            )
        }
        var missingOccurrence = false
        val causes = mutableListOf<CoveringCause>()
        fun add(key: String, kind: CauseKind, name: String, tier: BreakLevel, enforcing: Boolean?) {
            val saved = snapshot.editor.causes[key]
            val interval = saved?.interval
            val remaining = interval?.remainingMs(now)
            if (saved == null || saved.occurrenceId == "inactive" || interval == null || remaining == null || remaining <= 0) {
                missingOccurrence = true
                return
            }
            val end = addBounded(now.wallEpochMs, remaining)
            causes += CoveringCause(null, name, kind, tier, true, end,
                clockDiscontinuity(interval.anchor, now) || quota?.timeUncertain == true,
                end, enforcing, key, saved.occurrenceId)
        }
        timeView?.let { view ->
            val budget = view.quotaMs
            val spent = if (budget <= 0) true else usedMs?.let { it >= budget }
            if (time != null) {
                add(timeCauseKey(packageName), CauseKind.TIME_LIMIT, "Time limit", view.tier, spent)
            } else {
                // Matches OrdinaryProtectionPolicy's legacy Time Limit period and tier.
                val remaining = quota?.let { OpenCreditPolicy().remainingToResetMs(it.reset, now) }
                if (remaining == null || remaining <= 0) missingOccurrence = true
                else {
                    val period = checkNotNull(quota)
                    val end = addBounded(now.wallEpochMs, remaining)
                    causes += CoveringCause(null, "Time limit", CauseKind.TIME_LIMIT, view.tier, true,
                        end, period.timeUncertain, end, spent, "legacy-time:$packageName", "legacy-time-${period.periodId}")
                }
            }
        }
        open?.let { limit ->
            // #178 shields between explicit credits even while unused quota remains.
            add(openCauseKey(packageName), CauseKind.OPEN_LIMIT, "Open limit", limit.breakLevel,
                if (quota == null || resetRemaining == null || resetRemaining <= 0) null else activeRemaining <= 0)
            if (quota != null && resetRemaining == 0L) missingOccurrence = true
        }
        return TargetLimitFacts(causes, timeView, openView,
            if (missingOccurrence) "limit-occurrence-unavailable" else null)
    }

    private fun addBounded(value: Long, delta: Long): Long =
        if (value > Long.MAX_VALUE - delta) Long.MAX_VALUE else value + delta
}
