package com.flint.peakfocus.core.history

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class HistoryAvailability { UNAVAILABLE, DEGRADED, EMPTY, VALUE }
enum class SessionOutcome { RUNNING, COMPLETED, ENDED_EARLY, SUPERSEDED, UNCONFIRMED }

data class FocusInterval(val start: Long, val end: Long)
data class FocusSession(
    val id: String,
    val name: String,
    val start: Long,
    val end: Long,
    val plannedEnd: Long?,
    val outcome: SessionOutcome,
    val inferred: Boolean,
    val intervals: List<FocusInterval>,
    val breaks: Int?,
    val opensIntercepted: Int?,
    val terminalRecorded: Boolean = false,
) {
    val focusedMs: Long get() = intervals.sumOf { it.end - it.start }
}
data class FocusDay(
    val date: LocalDate,
    /** Null is no data; zero is a measured empty day. */
    val focusedMs: Long?,
    val completed: Int = 0,
    val endedEarly: Int = 0,
    val inProgress: Boolean = false,
    val unconfirmed: Int = 0,
)
data class FocusSummary(
    val availability: HistoryAvailability,
    val sessions: List<FocusSession>,
    val days: List<FocusDay>,
    val streak: Int,
)

object FocusHistoryAggregator {
    fun summarize(
        snapshot: HistorySnapshot,
        now: Long,
        zone: ZoneId,
        activeSessionIds: Set<String>,
        from: LocalDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays(6),
        through: LocalDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate(),
    ): FocusSummary {
        if ((!snapshot.readable || snapshot.degraded) && snapshot.events.isEmpty()) {
            return FocusSummary(HistoryAvailability.UNAVAILABLE, emptyList(), emptyList(), 0)
        }
        val sessions = sessions(snapshot.events, now, activeSessionIds)
        val availability = when {
            snapshot.degraded || !snapshot.readable -> HistoryAvailability.DEGRADED
            sessions.isEmpty() -> HistoryAvailability.EMPTY
            else -> HistoryAvailability.VALUE
        }
        val startDate = date(snapshot.historyStart, zone)
        val union = union(sessions.flatMap { it.intervals })
        val totals = mutableMapOf<LocalDate, Long>()
        union.forEach { interval ->
            var cursor = interval.start
            while (cursor < interval.end) {
                val day = date(cursor, zone)
                val next = minOf(day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), interval.end)
                if (day >= startDate) totals[day] = (totals[day] ?: 0) + next - cursor
                cursor = next
            }
        }
        val completed = sessions.filter { it.outcome == SessionOutcome.COMPLETED }.groupingBy { date(it.end, zone) }.eachCount()
        val ended = sessions.filter { it.outcome in setOf(SessionOutcome.ENDED_EARLY, SessionOutcome.SUPERSEDED) }
            .groupingBy { date(it.end, zone) }.eachCount()
        val unconfirmed = sessions.filter { it.outcome == SessionOutcome.UNCONFIRMED }
            .groupingBy { date(it.end, zone) }.eachCount()
        val days = generateSequence(from) { it.plusDays(1) }.takeWhile { it <= through }.map { day ->
            if (day < startDate) FocusDay(day, null) else FocusDay(
                day, totals[day] ?: 0, completed[day] ?: 0, ended[day] ?: 0,
                sessions.any { s -> s.outcome == SessionOutcome.RUNNING && date(s.start, zone) <= day && date(now, zone) >= day },
                unconfirmed[day] ?: 0,
            )
        }.toList()
        var streak = 0
        var streakDay = date(now, zone)
        if (completed[streakDay] == null) streakDay = streakDay.minusDays(1)
        while (streakDay >= startDate && (completed[streakDay] ?: 0) > 0) {
            streak++; streakDay = streakDay.minusDays(1)
        }
        return FocusSummary(availability, sessions, days, streak)
    }

    fun sessions(events: List<HistoryEvent>, now: Long, activeSessionIds: Set<String>): List<FocusSession> {
        val supported = events.distinctBy { it.id }.filter { it.knownType != null }
        val passesBySession = supported.filter { it.knownType == HistoryEventType.PASS_USED }
            .flatMap { pass -> pass.sessions.orEmpty().distinct().map { it to pass } }
            .groupBy({ it.first }, { it.second })
        return supported.groupBy { it.session }.mapNotNull { (id, group) ->
            val ordered = group.sortedBy { it.at } // stable: ties retain file order
            val start = group.firstOrNull { it.knownType == HistoryEventType.STARTED } ?: return@mapNotNull null
            val terminal = ordered.firstOrNull { it.knownType?.terminal == true }
            val plans = ordered.takeWhile { it !== terminal }.filter {
                it.knownType in setOf(HistoryEventType.STARTED, HistoryEventType.RESCHEDULED)
            }.ifEmpty { listOf(start) }
            fun planAt(at: Long) = plans.filter { it.at <= at }.sortedBy { it.observedAt ?: it.at }.lastOrNull() ?: start
            val plan = plans.sortedBy { it.observedAt ?: it.at }.lastOrNull() ?: start
            val plannedEnd = plan.plannedEnd
            val expired = plannedEnd?.let { it <= now } == true
            fun validPlan(event: HistoryEvent) = !event.uncertainTime && event.at >= start.at &&
                (event.plannedEnd == null || event.plannedEnd > start.at)
            fun inPlan(event: HistoryEvent) = event.at >= start.at &&
                planAt(event.at).let { validPlan(it) && (it.plannedEnd == null || event.at <= it.plannedEnd) }
            val unknownEnd = terminal?.knownType == HistoryEventType.ENDED_EARLY && terminal.inferred && terminal.route == "unknown"
            val validTerminal = terminal != null && !terminal.uncertainTime && !unknownEnd && validPlan(plan) &&
                terminal.at > start.at && when (terminal.knownType) {
                    HistoryEventType.COMPLETED -> plannedEnd != null &&
                        if (terminal.timeConfidence == HistoryEventTimeConfidence.Certain) terminal.at >= plannedEnd
                        else terminal.at == plannedEnd
                    else -> plannedEnd == null || terminal.at <= plannedEnd
                }
            // A committed terminal's logical time remains valid if the reader's wall clock
            // rolls back. Later cleanup cannot taint it; replayed phases use logical order.
            val localEvidence = if (validTerminal) ordered.takeWhile { it !== terminal }
                else if (terminal != null) group.takeWhile { it !== terminal }.sortedBy { it.at } else ordered
            val beforeTerminal = (localEvidence + passesBySession[id].orEmpty().filter {
                !validTerminal || it.at <= terminal!!.at
            }).sortedBy { it.at }
            var trusted = validPlan(start)
            var coverageEnd = start.at
            beforeTerminal.forEach { event ->
                when {
                    event.knownType in setOf(HistoryEventType.STARTED, HistoryEventType.RESCHEDULED) ->
                        if (!validPlan(event)) trusted = false
                    event.knownType in coverageTypes -> {
                        // Reject an impossible witness rather than clipping it into evidence.
                        val paired = event.knownType != HistoryEventType.BREAK_ENDED || event.grant == null ||
                            ordered.any { it.knownType == HistoryEventType.BREAK_STARTED && it.grant == event.grant && it.at <= event.at }
                        if (event.uncertainTime || !inPlan(event) || !paired || (!validTerminal && event.at > now)) trusted = false
                        else if (trusted) coverageEnd = maxOf(coverageEnd, event.at)
                    }
                    event.knownType == HistoryEventType.PASS_USED && event.uncertainTime -> trusted = false
                }
            }
            val outcome = if (!trusted || (terminal != null && !validTerminal)) SessionOutcome.UNCONFIRMED else when (terminal?.knownType) {
                HistoryEventType.COMPLETED -> SessionOutcome.COMPLETED
                HistoryEventType.ENDED_EARLY -> SessionOutcome.ENDED_EARLY
                HistoryEventType.SUPERSEDED -> SessionOutcome.SUPERSEDED
                else -> when {
                    expired -> SessionOutcome.UNCONFIRMED
                    id in activeSessionIds -> SessionOutcome.RUNNING
                    else -> SessionOutcome.UNCONFIRMED
                }
            }
            val inferred = terminal?.inferred ?: (outcome != SessionOutcome.RUNNING)
            val end = terminal?.at ?: if (expired) plannedEnd!! else now
            // STARTED + a deadline is a plan, not coverage. Credit only a later
            // lifecycle observation's prefix while waiting for the owner's terminal.
            val evidencedEnd = if (outcome == SessionOutcome.UNCONFIRMED) coverageEnd else end
            val clampedEnd = minOf(evidencedEnd, plannedEnd ?: evidencedEnd,
                if (outcome == SessionOutcome.RUNNING || terminal == null) now else evidencedEnd)
            var intervals = if (clampedEnd <= start.at) emptyList()
                else listOf(FocusInterval(start.at, clampedEnd))
            val breaks = ordered.filter { it.knownType == HistoryEventType.BREAK_STARTED }
            breaks.forEach { b ->
                val observed = ordered.firstOrNull { it.knownType == HistoryEventType.BREAK_ENDED && it.at >= b.at &&
                    !it.uncertainTime && (b.grant == null || it.grant == b.grant) }?.at
                val breakEnd = minOf(observed ?: b.breakEnd ?: clampedEnd, clampedEnd)
                intervals = subtract(intervals, FocusInterval(b.at, breakEnd))
            }
            ordered.filter { it.knownType == HistoryEventType.PROTECTION_DEGRADED }.forEach { gap ->
                val restored = ordered.firstOrNull { it.knownType == HistoryEventType.PROTECTION_RESTORED &&
                    !it.uncertainTime && it.at >= gap.at }?.at
                intervals = subtract(intervals, FocusInterval(gap.at, restored ?: clampedEnd))
            }
            passesBySession[id].orEmpty().forEach { pass ->
                intervals = subtract(intervals, FocusInterval(pass.at, pass.recoveryEnd ?: pass.at))
            }
            FocusSession(id, start.name ?: "Focus", start.at, end, plannedEnd, outcome, inferred,
                intervals, breaks.size.takeIf { it > 0 },
                ordered.count { it.knownType == HistoryEventType.OPEN_INTERCEPTED }.takeIf { it > 0 }, terminal != null)
        }
    }

    private val coverageTypes = setOf(HistoryEventType.BREAK_STARTED, HistoryEventType.BREAK_ENDED,
        HistoryEventType.PROTECTION_DEGRADED, HistoryEventType.PROTECTION_RESTORED, HistoryEventType.OPEN_INTERCEPTED)

    private val HistoryEvent.uncertainTime: Boolean get() =
        timeConfidence is HistoryEventTimeConfidence.Uncertain || reason == "time_uncertain"

    internal fun union(intervals: List<FocusInterval>): List<FocusInterval> {
        val result = mutableListOf<FocusInterval>()
        intervals.filter { it.end > it.start }.sortedBy { it.start }.forEach { next ->
            val last = result.lastOrNull()
            if (last != null && next.start <= last.end) result[result.lastIndex] = last.copy(end = maxOf(last.end, next.end))
            else result += next
        }
        return result
    }

    private fun subtract(intervals: List<FocusInterval>, cut: FocusInterval): List<FocusInterval> {
        if (cut.end <= cut.start) return intervals
        return intervals.flatMap { interval ->
            if (cut.end <= interval.start || cut.start >= interval.end) listOf(interval)
            else listOf(FocusInterval(interval.start, maxOf(interval.start, cut.start)),
                FocusInterval(minOf(interval.end, cut.end), interval.end)).filter { it.end > it.start }
        }
    }
    private fun date(at: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
}
