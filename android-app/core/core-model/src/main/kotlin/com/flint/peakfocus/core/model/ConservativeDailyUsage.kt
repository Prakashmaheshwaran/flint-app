package com.flint.peakfocus.core.model

import java.time.Instant
import java.time.ZoneId

/**
 * A daily lower bound from both public Android measurements. Pruned event history must not
 * erase aggregate usage or an already observed total. Two unavailable measurements fail closed
 * for that decision, without poisoning the next successful measurement.
 *
 * quotaZone is not persisted yet (contract deviation tracked for C4d). Midnight follows the
 * device zone; a zone change retains the larger observed total until the next midnight in the
 * new zone. This is process-local protection, not durable clock/zone integrity.
 */
class ConservativeDailyUsage {
    private val observed = mutableMapOf<String, Long>()
    private var zone: ZoneId? = null
    private var resetAt: Long? = null

    fun observe(packageName: String, nowMs: Long, currentZone: ZoneId, events: Long?, aggregate: Long?): Long {
        if (zone != currentZone) {
            // Changing the clock's zone is not evidence that the prior quota day ended.
            zone = currentZone
            resetAt = nextMidnight(nowMs, currentZone)
        } else if (resetAt?.let { nowMs >= it } == true) {
            observed.clear()
            resetAt = nextMidnight(nowMs, currentZone)
        }
        if (events == null && aggregate == null) return Long.MAX_VALUE
        val measured = maxOf(events ?: 0L, aggregate ?: 0L, observed[packageName] ?: 0L)
        observed[packageName] = measured
        return measured
    }

    private fun nextMidnight(nowMs: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().plusDays(1)
            .atStartOfDay(zone).toInstant().toEpochMilli()
}
