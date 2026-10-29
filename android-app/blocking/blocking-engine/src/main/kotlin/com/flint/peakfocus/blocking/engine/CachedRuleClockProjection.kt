package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.AccessClockStamp
import com.flint.peakfocus.core.model.BlockRule
import java.util.IdentityHashMap
import kotlin.math.abs

/** Reuse immutable publication projections briefly, but never across a boundary or clock edit. */
class CachedRuleClockProjection(private val clock: () -> AccessClockStamp) {
    private data class Entry(val at: AccessClockStamp, val projected: List<BlockRule>, val boundaryDelayMs: Long?)
    private val entries = IdentityHashMap<List<BlockRule>, Entry>()

    // The projection is pure and cheap; serialize both detector reads so they share one
    // recomputation. At most four immutable lists are retained, never a publication history.
    @Synchronized
    fun project(rules: List<BlockRule>): List<BlockRule> {
        val now = clock()
        entries[rules]?.let { cached ->
            val elapsed = trustedElapsedMs(cached.at, now)
            if (elapsed != null && elapsed < MAX_AGE_MS &&
                // Two clock reads can straddle a millisecond tick; that is not a clock edit.
                abs((now.wallEpochMs - cached.at.wallEpochMs) - elapsed) <= 2 &&
                (cached.boundaryDelayMs == null || elapsed < cached.boundaryDelayMs)) return cached.projected
        }
        val projected = RuleClockProjection.project(rules, now)
        // Keep only a few live publication identities: combined rules and store readiness
        // are separate immutable lists, and replaced snapshots must not accumulate.
        if (entries.size >= 4 && !entries.containsKey(rules)) entries.clear()
        val boundaryDelay = projected.mapNotNull { it.runtime?.evaluation?.nextBoundaryEpochMs }
            .minOrNull()?.let { (it - now.wallEpochMs).coerceAtLeast(0) }
        entries[rules] = Entry(now, projected, boundaryDelay)
        return projected
    }

    companion object { const val MAX_AGE_MS = 100L }
}
