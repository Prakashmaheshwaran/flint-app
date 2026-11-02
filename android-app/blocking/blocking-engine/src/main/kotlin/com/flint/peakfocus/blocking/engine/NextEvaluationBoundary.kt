package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.Schedule
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Earliest instant the current foreground decision can change without another window event.
 * Produces one deadline, not a periodic minute poll. Enumerates both sides of DST overlaps and
 * the jump itself, then uses the decision engine's exact weekday/overnight semantics to reject
 * non-boundaries. Pure: clock, zone, rules and remaining budget are supplied by the caller.
 */
object NextEvaluationBoundary {
    fun next(
        nowMs: Long,
        zone: ZoneId,
        foregroundPackage: String,
        rules: List<BlockRule>,
        remainingBudgetMs: Long? = null,
        interactive: Boolean = true,
        boundedRecovery: Boolean = false,
        budgetResetRemainingMs: Long? = null,
        budgetUsesSavedReset: Boolean = false,
    ): Long? {
        val relevant = rules.filter { rule ->
            rule.enabled && (rule.runtime?.evaluation?.let { it.active || it.nextBoundaryEpochMs != null }
                ?: !rule.isExpired(nowMs)) && with(rule.targets) {
                if (allowListMode) apps.none { it.packageName == foregroundPackage }
                else apps.any { it.packageName == foregroundPackage } || domains.isNotEmpty()
            }
        }
        if (relevant.isEmpty() && remainingBudgetMs == null && budgetResetRemainingMs == null) return null
        val now = Instant.ofEpochMilli(nowMs)
        val today = now.atZone(zone).toLocalDate()
        val candidates = mutableListOf<Long>()
        // Recover an obscured foreground or unavailable usage measurement without a new event.
        if (boundedRecovery && interactive) candidates += nowMs + 60_000L
        // Saved Time periods reset only on elapsed proof; schedules still use their own zone.
        if (!budgetUsesSavedReset || relevant.isNotEmpty())
            candidates += today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        budgetResetRemainingMs?.takeIf { it > 0L && it <= Long.MAX_VALUE - nowMs }
            ?.let { candidates += nowMs + it }
        remainingBudgetMs?.takeIf { interactive && it > 0L && it <= Long.MAX_VALUE - nowMs }
            ?.let { candidates += nowMs + it }
        for (rule in relevant) {
            val occurrence = rule.runtime?.evaluation
            if (occurrence != null) {
                occurrence.nextBoundaryEpochMs?.let(candidates::add)
                continue
            }
            rule.expiresAtEpochMs?.takeIf { it > nowMs }?.let(candidates::add)
            val schedule = rule.schedule ?: continue
            val scheduleCandidates = mutableSetOf<Long>()
            // A weekly gate has a transition within eight local days. The preceding date
            // also covers a DST overlap still in progress at the caller's instant.
            for (day in -1L..8L) {
                val date = today.plusDays(day)
                for (minute in listOf(0, schedule.startMinuteOfDay, schedule.endMinuteOfDay)) {
                    if (minute !in 0..1440) continue
                    val local = date.atStartOfDay().plusMinutes(minute.toLong())
                    addLocalInstants(local, zone, scheduleCandidates)
                }
            }
            var transition = zone.rules.nextTransition(now.minusNanos(1))
            val horizon = today.plusDays(9).atStartOfDay(zone).toInstant()
            while (transition != null && transition.instant < horizon) {
                scheduleCandidates += transition.instant.toEpochMilli()
                transition = zone.rules.nextTransition(transition.instant.plusNanos(1))
            }
            scheduleCandidates.filterTo(candidates) { instant ->
                instant > nowMs && !rule.isExpired(instant) &&
                    active(schedule, instant - 1, zone) != active(schedule, instant, zone)
            }
        }
        return candidates.filter { it > nowMs }.minOrNull()
    }

    private fun addLocalInstants(local: LocalDateTime, zone: ZoneId, into: MutableSet<Long>) {
        val offsets = zone.rules.getValidOffsets(local)
        if (offsets.isEmpty()) {
            // A missing wall minute becomes observable at the spring-forward jump, if the
            // engine changes state there; it must not be shifted an extra hour by atZone().
            zone.rules.getTransition(local)?.instant?.toEpochMilli()?.let(into::add)
        } else {
            offsets.forEach { into += local.toInstant(it).toEpochMilli() }
        }
    }

    private fun active(schedule: Schedule, atMs: Long, zone: ZoneId): Boolean {
        val local = Instant.ofEpochMilli(atMs).atZone(zone)
        return BlockDecisionEngine().scheduleActive(schedule, local.hour * 60 + local.minute, local.dayOfWeek.value)
    }
}
