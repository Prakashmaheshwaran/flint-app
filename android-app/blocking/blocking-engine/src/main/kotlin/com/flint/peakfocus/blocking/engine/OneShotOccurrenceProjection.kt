package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.AccessClockStamp
import com.flint.peakfocus.core.model.OneShotSchedule
import com.flint.peakfocus.core.model.RuleEvaluation

/**
 * Pure preparation for the serialized editor adapter. The returned state MUST be committed
 * with its rule before publication; this helper performs no I/O or lifecycle emission.
 */
object OneShotOccurrenceProjection {
    data class State(
        val occurrenceId: String,
        val interval: AnchoredInterval? = null,
        val ended: Boolean = false,
    )

    data class Projection(val state: State, val evaluation: RuleEvaluation) {
        /** Recovery suspends a rule cause without terminating its commitment or moving its end. */
        fun enforcing(recoveryActive: Boolean): Boolean = evaluation.active && !recoveryActive
    }

    fun advance(schedule: OneShotSchedule, previous: State?, timeline: AccessClockStamp,
        now: AccessClockStamp, enabled: Boolean = true): Projection {
        require(isValidOccurrenceDuration(schedule.durationMs))
        require(previous == null || previous.occurrenceId == schedule.occurrenceId) {
            "A stale occurrence cannot authorize a different one-shot pair"
        }
        val state = previous ?: State(schedule.occurrenceId)
        if (state.ended) return Projection(state, RuleEvaluation(false, null, null))
        val interval = state.interval
        if (interval != null) {
            val remaining = interval.remainingMs(now)
            if (remaining == 0L) return Projection(state.copy(ended = true), RuleEvaluation(false, null, null))
            val end = addBounded(now.wallEpochMs, remaining)
            // Checkpoint a reboot fallback so another restart cannot restore the old balance.
            val next = if (trustedElapsedMs(interval.anchor, now) == null)
                state.copy(interval = AnchoredInterval(now, remaining, end)) else state
            return Projection(next, RuleEvaluation(enabled, end.takeIf { enabled }, end.takeIf { enabled }))
        }
        val logicalNow = trustedElapsedMs(timeline, now)?.let { addBounded(timeline.wallEpochMs, it) }
            ?: now.wallEpochMs
        // First observation can be after the whole interval: tombstone, never fake a start.
        if (logicalNow >= schedule.endsAtEpochMs)
            return Projection(state.copy(ended = true), RuleEvaluation(false, null, null))
        if (!enabled) return Projection(state, RuleEvaluation(false, null, null))
        if (logicalNow < schedule.startsAtEpochMs) return Projection(state, RuleEvaluation(false, null,
            addBounded(now.wallEpochMs, schedule.startsAtEpochMs - logicalNow)))
        val remaining = schedule.endsAtEpochMs - logicalNow
        val end = addBounded(now.wallEpochMs, remaining)
        return Projection(state.copy(interval = AnchoredInterval(now, remaining, end)), RuleEvaluation(true, end, end))
    }

    private fun addBounded(value: Long, duration: Long): Long =
        if (value > Long.MAX_VALUE - duration) Long.MAX_VALUE else value + duration
}
