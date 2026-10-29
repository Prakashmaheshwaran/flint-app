package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.AccessClockStamp
import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.RuleEvaluation
import java.time.ZoneId

/** Read-time projection: no I/O, mutation, quota reset or occurrence creation in durable state. */
object RuleClockProjection {
    fun project(rules: List<BlockRule>, now: AccessClockStamp): List<BlockRule> =
        rules.map { project(it, now) }

    fun project(rule: BlockRule, now: AccessClockStamp): BlockRule {
        val runtime = rule.runtime ?: return rule
        val evaluation = evaluate(rule, now)
        // Keep the raw schedule for reason/identity and future-window consumers. The engine
        // uses evaluation.active; the wall end is only this read's display/timer estimate.
        return rule.copy(expiresAtEpochMs = evaluation.endsAtEpochMs,
            runtime = runtime.copy(evaluation = evaluation))
    }

    private fun evaluate(rule: BlockRule, now: AccessClockStamp): RuleEvaluation {
        val runtime = requireNotNull(rule.runtime)
        if (!rule.enabled) return RuleEvaluation(false, null, null)
        if (runtime.occurrenceId != "inactive") {
            val remaining = runtime.remainingAtAnchorMs?.let {
                AnchoredInterval(runtime.anchor, it, requireNotNull(runtime.wallEndEpochMs)).remainingMs(now)
            }
            if (remaining == null) return RuleEvaluation(true, null, null)
            if (remaining > 0) {
                val end = addBounded(now.wallEpochMs, remaining)
                return RuleEvaluation(true, end, end)
            }
        }
        val schedule = rule.schedule ?: return RuleEvaluation(false,
            now.wallEpochMs.takeIf { runtime.remainingAtAnchorMs != null }, null)
        if (runtime.stopAtOccurrenceEnd) return RuleEvaluation(false, null, null)
        // Future windows retain their saved zone and the persisted monotonic timeline. This
        // also lets a stationary reader reach the next start before the bridge callback runs.
        val elapsed = trustedElapsedMs(runtime.timeline, now)
        val logicalWall = elapsed?.let { addBounded(runtime.timeline.wallEpochMs, it) } ?: now.wallEpochMs
        val zone = ZoneId.of(runtime.zoneId)
        val occurrence = ScheduleOccurrences.containing(schedule, zone, logicalWall)
        // A completed occurrence cannot replay after a reboot/rollback or a duplicate callback.
        if (occurrence != null && occurrence.id != runtime.occurrenceId) {
            val end = addBounded(now.wallEpochMs, occurrence.endsAtEpochMs - logicalWall)
            return RuleEvaluation(true, end, end)
        }
        val next = ScheduleOccurrences.nextStart(schedule, zone, logicalWall)
            ?.let { addBounded(now.wallEpochMs, it - logicalWall) }
        return RuleEvaluation(false, null, next)
    }

    private fun addBounded(value: Long, duration: Long): Long =
        if (value > Long.MAX_VALUE - duration) Long.MAX_VALUE else value + duration
}
