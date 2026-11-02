package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.BlockRule

/**
 * End used to choose and render an active rule's headline. Anchored runtime is authoritative,
 * including a null (ongoing/unknown) end. Legacy schedules use the caller's local clock,
 * matching their existing fixed-offset countdown; saved-zone/DST ends come from runtime.
 */
fun ruleWindowEnd(rule: BlockRule, nowEpochMs: Long, nowMinutesOfDay: Int): Long? {
    rule.runtime?.evaluation?.let { return it.endsAtEpochMs }
    rule.expiresAtEpochMs?.let { return it }
    val schedule = rule.schedule ?: return null
    if (nowEpochMs < 0 || nowMinutesOfDay !in 0 until 1440 || schedule.endMinuteOfDay !in 0..1440) return null
    val minutes = (schedule.endMinuteOfDay - nowMinutesOfDay + 1440) % 1440
    val duration = (if (minutes == 0) 1440 else minutes) * 60_000L - nowEpochMs % 60_000L
    return if (nowEpochMs > Long.MAX_VALUE - duration) Long.MAX_VALUE else nowEpochMs + duration
}
