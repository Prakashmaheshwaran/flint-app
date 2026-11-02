package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.Schedule
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** ADR-012 §9: one occurrence per local start date, with explicit gap/overlap resolution. */
object ScheduleOccurrences {
    data class Occurrence(val startsAtEpochMs: Long, val endsAtEpochMs: Long) {
        val id: String get() = startsAtEpochMs.toString()
    }

    fun containing(schedule: Schedule, zone: ZoneId, nowEpochMs: Long): Occurrence? {
        val today = Instant.ofEpochMilli(nowEpochMs).atZone(zone).toLocalDate()
        return listOf(today, today.minusDays(1)).mapNotNull { onDate(schedule, zone, it) }
            .firstOrNull { nowEpochMs >= it.startsAtEpochMs && nowEpochMs < it.endsAtEpochMs }
    }

    fun nextStart(schedule: Schedule, zone: ZoneId, afterEpochMs: Long): Long? {
        val today = Instant.ofEpochMilli(afterEpochMs).atZone(zone).toLocalDate()
        return (0L..8L).mapNotNull { onDate(schedule, zone, today.plusDays(it))?.startsAtEpochMs }
            .filter { it > afterEpochMs }.minOrNull()
    }

    /** An in-occurrence edit can move the start minute while retaining its accounting ID. */
    fun sameStartDate(firstId: String, secondId: String, zone: ZoneId): Boolean {
        val first = firstId.toLongOrNull() ?: return false
        val second = secondId.toLongOrNull() ?: return false
        return Instant.ofEpochMilli(first).atZone(zone).toLocalDate() ==
            Instant.ofEpochMilli(second).atZone(zone).toLocalDate()
    }

    fun onDate(schedule: Schedule, zone: ZoneId, date: LocalDate): Occurrence? {
        val start = schedule.startMinuteOfDay
        val end = schedule.endMinuteOfDay
        if (start !in 0..1439 || end !in 0..1439 || start == end) return null
        if (schedule.daysOfWeek.isNotEmpty() && date.dayOfWeek.value !in schedule.daysOfWeek) return null
        val from = endpoint(date.atStartOfDay().plusMinutes(start.toLong()), zone, start = true)
        val endDate = if (end < start) date.plusDays(1) else date
        val to = endpoint(endDate.atStartOfDay().plusMinutes(end.toLong()), zone, start = false)
        return if (isValidOccurrenceDuration(to - from)) Occurrence(from, to) else null
    }

    private fun endpoint(local: LocalDateTime, zone: ZoneId, start: Boolean): Long {
        val offsets = zone.rules.getValidOffsets(local)
        // atZone shifts a missing local endpoint forward by the full gap, as §9 requires.
        if (offsets.isEmpty()) return local.atZone(zone).toInstant().toEpochMilli()
        val candidates = offsets.map { local.toInstant(it).toEpochMilli() }
        return if (start) candidates.min() else candidates.max()
    }
}
