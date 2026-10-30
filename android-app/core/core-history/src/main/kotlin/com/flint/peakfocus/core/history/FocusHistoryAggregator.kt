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
    /** intervals retain possible coverage; focusedMs also discounts these placement budgets. */
    val uncertainGaps: List<UncertainFocusGap> = emptyList(),
) {
    val focusedMs: Long get() = FocusHistoryBounds.lowerBound(listOf(this))
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
    /** Owner outbox retention lost events; existing intervals/counts are never backfilled. */
    val lifecycleEventsMissing: Boolean = false,
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
        val windowStart = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val windowEnd = through.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        union.forEach { interval ->
            var cursor = maxOf(interval.start, windowStart)
            val end = minOf(interval.end, windowEnd)
            while (cursor < end) {
                val day = date(cursor, zone)
                val next = minOf(day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), end)
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
                day, if (sessions.any { it.uncertainGaps.isNotEmpty() }) FocusHistoryBounds.lowerBound(sessions,
                    FocusInterval(day.atStartOfDay(zone).toInstant().toEpochMilli(),
                        day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())) else totals[day] ?: 0,
                completed[day] ?: 0, ended[day] ?: 0,
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
        val appendOrder = supported.withIndex().associate { it.value.id to it.index }
        val passesBySession = supported.filter { it.knownType == HistoryEventType.PASS_USED }
            .flatMap { pass -> pass.sessions.orEmpty().distinct().map { it to pass } }
            .groupBy({ it.first }, { it.second })
        return supported.groupBy { it.session }.mapNotNull { (id, group) ->
            val ordered = group.sortedBy { it.at } // stable: ties retain file order
            val start = group.firstOrNull { it.knownType == HistoryEventType.STARTED } ?: return@mapNotNull null
            val terminal = ordered.firstOrNull { it.knownType?.terminal == true }
            val planIds = ordered.takeWhile { it !== terminal }.filter {
                it.knownType in setOf(HistoryEventType.STARTED, HistoryEventType.RESCHEDULED)
            }.mapTo(mutableSetOf()) { it.id }
            // Plan ties use append/revision order; phase pairing still uses logical time.
            val plans = group.filter { it.id in planIds }.ifEmpty { listOf(start) }
            fun plausiblePlan(event: HistoryEvent) = !event.uncertainTime && event.at in SUPPORTED_TIMES &&
                start.at in SUPPORTED_TIMES && event.at >= start.at &&
                (event.plannedEnd == null || (event.plannedEnd in SUPPORTED_TIMES &&
                    event.plannedEnd > start.at && event.plannedEnd >= event.at))
            val validPlanIds = mutableSetOf<String>()
            if (plausiblePlan(start)) validPlanIds += start.id
            var precedingPlan = start
            fun ownerFinalPlan(revision: HistoryEvent) = terminal != null &&
                revision.knownType == HistoryEventType.RESCHEDULED && revision.reason == "owner_final_plan" &&
                revision.originRoute == "editor_terminal" && revision.timeConfidence == HistoryEventTimeConfidence.Certain &&
                terminal.knownType == HistoryEventType.COMPLETED && terminal.originRoute == "editor_terminal" &&
                terminal.timeConfidence == HistoryEventTimeConfidence.Certain && !terminal.uncertainTime &&
                revision.at == terminal.at && revision.plannedEnd == terminal.at
            for (revision in plans.sortedBy { it.observedAt ?: it.at }.filter { it.id != start.id }) {
                if (precedingPlan.id in validPlanIds && plausiblePlan(revision) &&
                    (ownerFinalPlan(revision) || (precedingPlan.plannedEnd?.let { revision.at <= it } ?: true))) {
                    validPlanIds += revision.id
                    precedingPlan = revision
                }
            }
            fun validPlan(event: HistoryEvent) = event.id in validPlanIds
            fun planAt(at: Long) = plans.filter { validPlan(it) && it.at <= at }
                .sortedBy { it.observedAt ?: it.at }.lastOrNull() ?: start
            val authoritativePlan = plans.lastOrNull { validPlan(it) && ownerFinalPlan(it) }
            val plan = authoritativePlan ?: plans.sortedBy { it.observedAt ?: it.at }.lastOrNull() ?: start
            val plannedEnd = plan.plannedEnd
            val expired = plannedEnd?.let { it <= now } == true
            fun inPlan(event: HistoryEvent) = event.at in SUPPORTED_TIMES && event.at >= start.at &&
                (authoritativePlan ?: planAt(event.at)).let { validPlan(it) && (it.plannedEnd == null || event.at <= it.plannedEnd) }
            val unknownEnd = terminal?.knownType == HistoryEventType.ENDED_EARLY && terminal.inferred && terminal.route == "unknown"
            val validTerminal = terminal != null && terminal.at in SUPPORTED_TIMES &&
                !terminal.uncertainTime && !unknownEnd && validPlan(plan) &&
                terminal.at > start.at && when (terminal.knownType) {
                    HistoryEventType.COMPLETED -> plannedEnd != null &&
                        if (terminal.timeConfidence == HistoryEventTimeConfidence.Certain) terminal.at >= plannedEnd
                        else terminal.at == plannedEnd
                    else -> plannedEnd == null || terminal.at <= plannedEnd
                }
            // A committed terminal's logical time remains valid if the reader's wall clock
            // rolls back. Later cleanup cannot taint it; replayed phases use logical order.
            val localPrefix = if (validTerminal) ordered.takeWhile { it !== terminal }
                else if (terminal != null) group.takeWhile { it !== terminal }.sortedBy { it.at } else ordered
            val localEvidence = (localPrefix + group.filter { it.uncertainTime && it.knownType in
                setOf(HistoryEventType.BREAK_STARTED, HistoryEventType.PROTECTION_DEGRADED) }).distinctBy { it.id }
            val relatedPasses = passesBySession[id].orEmpty().filter {
                it.uncertainTime || !validTerminal || it.at <= terminal!!.at
            }
            fun rejectedTime(event: HistoryEvent): Boolean = when {
                event.knownType in setOf(HistoryEventType.STARTED, HistoryEventType.RESCHEDULED) ->
                    !validPlan(event) && !(authoritativePlan != null &&
                        event.knownType == HistoryEventType.RESCHEDULED && !event.uncertainTime)
                event.knownType in coverageTypes -> event.uncertainTime || !inPlan(event) ||
                    (event.knownType == HistoryEventType.BREAK_STARTED &&
                        event.breakEnd?.let { it < event.at || it !in SUPPORTED_TIMES } == true) ||
                    (!validTerminal && event.at > now)
                event.knownType == HistoryEventType.PASS_USED -> event.uncertainTime || !inPlan(event) ||
                    event.recoveryEnd?.let { it < event.at || it !in SUPPORTED_TIMES } == true
                else -> false
            }
            val rejectedTimeIds = (ordered + relatedPasses).filter(::rejectedTime).mapTo(mutableSetOf()) { it.id }
            // Rejected time has no logical ordering authority over a previously recorded
            // witness. Replay these barriers once in append order, retaining logical grant
            // pairing (including an end delivered before its start).
            val beforeTerminal = (localEvidence + relatedPasses).sortedBy { appendOrder[it.id] }
            var trusted = validPlan(start)
            var canExtend = trusted
            var grantBarrier: Long? = null
            var coverageEnd = start.at
            beforeTerminal.forEach { event ->
                when {
                    event.id in rejectedTimeIds -> {
                        trusted = false
                        canExtend = false
                    }
                    event.knownType in coverageTypes -> {
                        val paired = event.knownType != HistoryEventType.BREAK_ENDED || event.grant == null ||
                            ordered.any { it.knownType == HistoryEventType.BREAK_STARTED && it.grant == event.grant &&
                                it.at <= event.at && it.id !in rejectedTimeIds }
                        if (!paired) {
                            trusted = false
                            grantBarrier = minOf(grantBarrier ?: event.at, event.at)
                        } else if (canExtend && (grantBarrier?.let { event.at < it } ?: true)) {
                            coverageEnd = maxOf(coverageEnd, event.at)
                        }
                    }
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
            // Rejected timestamps cannot move the unconfirmed warning off the visible day.
            val end = if (outcome == SessionOutcome.UNCONFIRMED)
                if (validPlan(plan)) minOf(plannedEnd ?: now, now) else now
                else terminal?.at ?: now
            // STARTED + a deadline is a plan, not coverage. Credit only a later
            // lifecycle observation's prefix while waiting for the owner's terminal.
            val evidencedEnd = if (outcome == SessionOutcome.UNCONFIRMED) coverageEnd else end
            // A rejected later plan cannot truncate a prefix already accepted under its
            // own valid plan. Ordinary completed/running intervals still use the plan cap.
            val planCeiling = if (outcome == SessionOutcome.UNCONFIRMED) evidencedEnd else plannedEnd ?: evidencedEnd
            val clampedEnd = minOf(evidencedEnd, planCeiling,
                if (outcome == SessionOutcome.RUNNING || terminal == null) now else evidencedEnd)
            var intervals = if (clampedEnd <= start.at) emptyList()
                else listOf(FocusInterval(start.at, clampedEnd))
            // Cuts use the same admitted times as coverage; raw backdated uncertainty
            // must not erase the prefix again during subtraction.
            val phases = ordered.filter { it.id !in rejectedTimeIds }
            val breaks = phases.filter { it.knownType == HistoryEventType.BREAK_STARTED }
            breaks.forEach { b ->
                val observed = phases.firstOrNull { it.knownType == HistoryEventType.BREAK_ENDED && it.at >= b.at &&
                    !it.uncertainTime && (b.grant == null || it.grant == b.grant) }?.at
                val breakEnd = minOf(observed ?: b.breakEnd ?: clampedEnd, clampedEnd)
                intervals = subtract(intervals, FocusInterval(b.at, breakEnd))
            }
            phases.filter { it.knownType == HistoryEventType.PROTECTION_DEGRADED }.forEach { gap ->
                val restored = phases.firstOrNull { it.knownType == HistoryEventType.PROTECTION_RESTORED &&
                    !it.uncertainTime && it.at >= gap.at }?.at
                intervals = subtract(intervals, FocusInterval(gap.at, restored ?: clampedEnd))
            }
            relatedPasses.filter { it.id !in rejectedTimeIds }.forEach { pass ->
                intervals = subtract(intervals, FocusInterval(pass.at, pass.recoveryEnd ?: pass.at))
            }
            val anchors = (localEvidence + listOfNotNull(terminal?.takeIf { validTerminal })).filter {
                !it.uncertainTime && it.id !in rejectedTimeIds && (it === start || it === terminal ||
                    it.knownType in setOf(HistoryEventType.OPEN_INTERCEPTED, HistoryEventType.PROTECTION_RESTORED))
            }.map { it.at }
            fun uncertainGap(event: HistoryEvent, knownStart: Long? = null, knownEnd: Long? = null,
                             endHint: Long? = null, maximum: Long = Long.MAX_VALUE): UncertainFocusGap {
                // Raw uncertain stamps locate a neighborhood only; they never become a cut
                // boundary or supply a duration. Unplaceable gaps can affect the whole window.
                val ceiling = if (validTerminal) minOf(plannedEnd ?: terminal!!.at, terminal!!.at)
                    else plans.filter(::validPlan).lastOrNull()?.plannedEnd ?: maxOf(now, clampedEnd)
                val placeable = event.at >= start.at && event.at <= ceiling &&
                    (endHint == null || endHint >= event.at && endHint in SUPPORTED_TIMES)
                val a = knownStart ?: if (placeable) anchors.filter { it <= event.at }.maxOrNull() ?: start.at else start.at
                val b = knownEnd ?: if (placeable && endHint != null) anchors.filter { it >= endHint }.minOrNull()
                    ?: clampedEnd else clampedEnd
                return UncertainFocusGap(FocusInterval(maxOf(start.at, a), minOf(clampedEnd, b)), maximum)
            }
            val uncertainGaps = mutableListOf<UncertainFocusGap>()
            val uncertainPhases = localEvidence.filter { it.uncertainTime }
            for (event in uncertainPhases) when (event.knownType) {
                HistoryEventType.BREAK_STARTED -> {
                    val close = localEvidence.firstOrNull { it.knownType == HistoryEventType.BREAK_ENDED &&
                        it.grant == event.grant && it.at >= event.at && it.id !in rejectedTimeIds }
                    uncertainGaps += uncertainGap(event, knownEnd = close?.at, endHint = close?.at ?: event.breakEnd,
                        maximum = if (event.kind == "ordinary") 300_000 else Long.MAX_VALUE)
                }
                HistoryEventType.BREAK_ENDED -> {
                    val opening = localEvidence.firstOrNull { it.knownType == HistoryEventType.BREAK_STARTED &&
                        it.grant == event.grant && it.at <= event.at && !it.uncertainTime && it.id !in rejectedTimeIds }
                    if (opening != null) uncertainGaps += uncertainGap(event, knownStart = opening.at, endHint = event.at,
                        maximum = if (opening.kind == "ordinary") 300_000 else Long.MAX_VALUE)
                }
                HistoryEventType.PROTECTION_DEGRADED -> {
                    val restored = localEvidence.firstOrNull { it.knownType == HistoryEventType.PROTECTION_RESTORED &&
                        it.at >= event.at && it.id !in rejectedTimeIds }
                    uncertainGaps += uncertainGap(event, knownEnd = restored?.at)
                }
                else -> Unit
            }
            for (pass in relatedPasses.filter { it.uncertainTime }) {
                uncertainGaps += uncertainGap(pass, endHint = pass.recoveryEnd, maximum = 3_600_000)
            }
            FocusSession(id, start.name ?: "Focus", start.at, end, plannedEnd, outcome, inferred,
                intervals, breaks.size.takeIf { it > 0 },
                ordered.count { it.knownType == HistoryEventType.OPEN_INTERCEPTED }.takeIf { it > 0 }, terminal != null,
                uncertainGaps)
        }
    }

    private val coverageTypes = setOf(HistoryEventType.BREAK_STARTED, HistoryEventType.BREAK_ENDED,
        HistoryEventType.PROTECTION_DEGRADED, HistoryEventType.PROTECTION_RESTORED, HistoryEventType.OPEN_INTERCEPTED)

    // Matches the civil-time range supported by the iOS history projection. Read records
    // bypass append-time checks, so extreme Long timestamps cannot prove a focus span.
    internal val SUPPORTED_TIMES = -62_135_596_800_000L..253_402_300_799_000L

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
