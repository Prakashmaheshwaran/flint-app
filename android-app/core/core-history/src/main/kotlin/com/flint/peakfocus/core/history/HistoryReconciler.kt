package com.flint.peakfocus.core.history

import java.time.Clock
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Contains no target identifiers and never mutates the rules store. */
data class HistoryRule(
    val id: String, val name: String, val tier: String, val plannedEnd: Long?,
    /** Public owner activity is independent of the stable logical history endpoint. */
    val ownerActive: Boolean? = null,
    /** F01: planned round count of a Pomodoro parent, else null. */
    val rounds: Int? = null,
    /** F01: every planned rest of the chain; the reconciler gates each on the trusted clock. */
    val plannedRests: List<PlannedRest> = emptyList(),
    /**
     * F01: the current phase index, so a rest→focus transition is a distinct observable value.
     * Null when the chain has finished or the rule has no plan.
     */
    val phaseIndex: Int? = null,
) {
    internal fun isActiveAt(now: Long): Boolean = ownerActive ?: (plannedEnd == null || plannedEnd > now)
}

sealed interface HistoryRulesSnapshot {
    data class Ready(val rules: List<HistoryRule>, val ownerInactiveIds: Set<String> = emptySet()) : HistoryRulesSnapshot
    data class Unavailable(val reason: String) : HistoryRulesSnapshot
    data object Loading : HistoryRulesSnapshot
}

class HistoryReconciler(
    private val store: FocusHistoryStore,
    private val clock: Clock = Clock.systemUTC(),
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    // Read again under the history transaction lock: a queued observation may be
    // stale after clear, a clock edit, or the owner's natural terminal transition.
    private val authoritativeSnapshot: (() -> HistoryRulesSnapshot)? = null,
) {
    private var firstSuccessfulSnapshot = true
    private val observationMutex = Mutex()
    // Public active IDs update immediately; an observed end keeps its original time
    // until history accepts it, even if a later retry is after the planned deadline.
    private val pendingEarlyEnds = mutableMapOf<String, Long>()
    private val active = MutableStateFlow<Set<String>>(emptySet())
    val activeSessionIds: StateFlow<Set<String>> = active.asStateFlow()
    private val readiness = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = readiness.asStateFlow()
    private val diagnostics = MutableStateFlow<String?>(null)
    val diagnostic: StateFlow<String?> = diagnostics.asStateFlow()

    /** Retry only errors, never synthesize an empty snapshot on a read failure. */
    suspend fun collect(rules: Flow<List<HistoryRule>>) = collectSnapshots(rules.map { HistoryRulesSnapshot.Ready(it) })

    suspend fun collectSnapshots(rules: Flow<HistoryRulesSnapshot>) {
        while (true) {
            try {
                rules.collect { snapshot ->
                    when (snapshot) {
                        is HistoryRulesSnapshot.Ready -> observeSnapshot(snapshot)
                        is HistoryRulesSnapshot.Unavailable -> diagnostics.value = "Rules unavailable: ${snapshot.reason}"
                        HistoryRulesSnapshot.Loading -> diagnostics.value = "Rules loading"
                    }
                }
                return
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                diagnostics.value = "Rules unavailable: ${e.javaClass.simpleName}"
                delay(1_000)
            }
        }
    }

    suspend fun observe(rules: List<HistoryRule>) = observeSnapshot(HistoryRulesSnapshot.Ready(rules))

    private suspend fun observeSnapshot(observation: HistoryRulesSnapshot.Ready) = observationMutex.withLock {
        val initial = authoritativeSnapshot?.invoke() ?: observation
        if (initial is HistoryRulesSnapshot.Ready) captureActive(initial, clock.millis())
        var acceptedRules = false
        var rulesDiagnostic: String? = null
        val success = store.update { snapshot ->
            val latest = authoritativeSnapshot?.invoke() ?: observation
            if (latest !is HistoryRulesSnapshot.Ready) {
                rulesDiagnostic = if (latest is HistoryRulesSnapshot.Unavailable)
                    "Rules unavailable: ${latest.reason}" else "Rules loading"
                return@update emptyList()
            }
            acceptedRules = true
            val now = clock.millis()
            val sessions = latest.rules.filter { it.id.startsWith("session-") }.associateBy { it.id }
            val current = sessions.filterValues { it.isActiveAt(now) }.keys
            captureActive(latest, now)
            val events = mutableListOf<HistoryEvent>()
            val known = snapshot.events.filter { it.knownType != null }.groupBy { it.session }
            sessions.values.filter { it.id in current }.forEach { rule ->
                if (known[rule.id].orEmpty().none { it.knownType == HistoryEventType.STARTED }) {
                    // An unacked raw Timer can look future-dated after wall rollback.
                    // Wait for the persisted clear boundary before importing new IDs.
                    if (snapshot.clearedAt?.let { now < it } == true) return@forEach
                    val native = rule.id.startsWith("session-timer-")
                    val timestamp = if (native) rule.id.removePrefix("session-timer-v2-").removePrefix("session-timer-").substringBefore('-').toLongOrNull() else null
                    val start = timestamp?.takeIf { it > 1_500_000_000_000L && (rule.plannedEnd == null || it < rule.plannedEnd) } ?: now
                    events += event(HistoryEventType.STARTED, rule.id, start).copy(
                        plannedEnd = rule.plannedEnd, tier = rule.tier, name = rule.name.take(64),
                        rounds = rule.rounds,
                        source = when {
                            !native -> "legacy"
                            rule.name == "Focus timer" -> "manual"
                            rule.name in PRESET_NAMES -> "preset"
                            else -> "unknown"
                        },
                    )
                }
            }
            // F01 §7: each planned rest is a `planned` break pair. Events are written only when
            // this reconciler observes that the trusted elapsed clock passed the boundary; the
            // logical boundary time is kept, a late observation is marked inferred. A parent that
            // ended early stopped the chain where it was, so its plan is not evidence of later
            // rests; a completed parent's rests all happened before its terminal.
            sessions.values.forEach { rule ->
                val group = known[rule.id].orEmpty()
                val terminated = group.firstOrNull { it.knownType?.terminal == true }
                if (terminated == null || terminated.knownType == HistoryEventType.COMPLETED) {
                    events += plannedRestEvents(rule.id, rule.plannedRests, group, now, zone().id)
                }
            }
            sessions.values.forEach { rule ->
                // A finished plan's endpoint is final; its owner terminal carries the effective
                // plan. A live parent, and every plain rule, may still have a late recovered plan.
                if (rule.phaseIndex == null && rule.plannedRests.isNotEmpty()) return@forEach
                val group = known[rule.id].orEmpty()
                if (group.any { it.knownType?.terminal == true }) return@forEach
                val plan = group.sortedBy { it.observedAt ?: it.at }.lastOrNull {
                    it.knownType in setOf(HistoryEventType.STARTED, HistoryEventType.RESCHEDULED)
                } ?: return@forEach
                if (plan.plannedEnd != rule.plannedEnd) {
                    events += event(HistoryEventType.RESCHEDULED, rule.id, minOf(now, rule.plannedEnd ?: now)).copy(
                        plannedEnd = rule.plannedEnd, observedAt = now, inferred = true)
                }
            }
            (snapshot.events + events).filter { it.knownType != null }.groupBy { it.session }.forEach { (id, group) ->
                if (group.none { it.knownType == HistoryEventType.STARTED }) return@forEach
                if (group.any { it.knownType?.terminal == true }) return@forEach
                val plan = group.sortedBy { it.observedAt ?: it.at }.last { it.knownType in setOf(HistoryEventType.STARTED, HistoryEventType.RESCHEDULED) }
                val rule = sessions[id]
                val deadline = if (rule != null) rule.plannedEnd else plan.plannedEnd
                // An expired/missing rule is not proof of natural completion. Leave
                // the occurrence open for the coordinator's durable terminal/outbox.
                val observedEnd = pendingEarlyEnds[id]
                if (observedEnd != null && (deadline == null || observedEnd < deadline)) {
                    events += event(HistoryEventType.ENDED_EARLY, id, observedEnd).copy(route = "unknown")
                }
            }
            events
        }
        if (success && acceptedRules) {
            pendingEarlyEnds.clear()
            firstSuccessfulSnapshot = false
            readiness.value = true
            diagnostics.value = null
        } else diagnostics.value = rulesDiagnostic ?: "History write unavailable"
    }

    private fun captureActive(snapshot: HistoryRulesSnapshot.Ready, now: Long) {
        val current = snapshot.rules.filter {
            it.id.startsWith("session-") && it.isActiveAt(now)
        }.mapTo(mutableSetOf()) { it.id }
        val previouslyActive = active.value
        active.value = current
        // Public IDs and the first live observation survive even a history-lock
        // failure; owner-ended causes instead await their durable terminal.
        snapshot.ownerInactiveIds.forEach(pendingEarlyEnds::remove)
        if (!firstSuccessfulSnapshot) previouslyActive.filterNot {
            it in current || it in snapshot.ownerInactiveIds
        }.forEach { id -> pendingEarlyEnds.putIfAbsent(id, now) }
    }

    private fun event(type: HistoryEventType, id: String, at: Long) =
        HistoryEvent(type = type.wireName, session = id, at = at, tz = zone().id)

    private companion object {
        // Existing Timer titles are the only persisted preset discriminator. Unknown titles
        // stay unknown rather than claiming a source that was not recorded by the creator.
        val PRESET_NAMES = setOf("Deep study", "Commute", "Workout", "Reading", "Weekend zen", "Touch Grass",
            "Quiet Day", "Digital Sabbath", "Retreat")
    }
}

/**
 * F01 §7: `break_started` at a planned rest's logical start and `break_ended` at its logical end,
 * both kind `planned` with grant `<ruleId>.rest<k>`. A boundary after [now] is not an event yet,
 * so a backward wall change cannot record a future rest. Stable IDs make repeated reconciles,
 * restarted processes and the owner's terminal delivery deduplicate; a recorded grant is never
 * rewritten. [observedAt] is the actual observation (defaults to [now]): the logical boundary is
 * kept and a late observation is marked inferred.
 */
fun plannedRestEvents(sessionId: String, rests: List<PlannedRest>, known: List<HistoryEvent>,
                      now: Long, tz: String, observedAt: Long = now): List<HistoryEvent> {
    if (rests.isEmpty()) return emptyList()
    val started = known.filter { it.knownType == HistoryEventType.BREAK_STARTED }
        .mapNotNullTo(mutableSetOf()) { it.grant }
    val ended = known.filter { it.knownType == HistoryEventType.BREAK_ENDED }
        .mapNotNullTo(mutableSetOf()) { it.grant }
    val into = mutableListOf<HistoryEvent>()
    for (rest in rests) {
        if (rest.startsAt !in FocusHistoryAggregator.SUPPORTED_TIMES || rest.endsAt <= rest.startsAt ||
            rest.startsAt > now) continue
        if (rest.grant !in started) {
            into += HistoryEvent(type = HistoryEventType.BREAK_STARTED.wireName, session = sessionId,
                at = rest.startsAt, tz = tz).copy(
                id = plannedRestId(sessionId, rest.round, "started"), breakEnd = rest.endsAt,
                kind = PLANNED_BREAK_KIND, grant = rest.grant, originRoute = "pomodoro_plan",
                observedAt = observedAt, inferred = rest.startsAt < observedAt - LATE_OBSERVATION_MS,
            )
        }
        if (rest.grant !in ended && now >= rest.endsAt) {
            into += HistoryEvent(type = HistoryEventType.BREAK_ENDED.wireName, session = sessionId,
                at = rest.endsAt, tz = tz).copy(
                id = plannedRestId(sessionId, rest.round, "ended"),
                kind = PLANNED_BREAK_KIND, grant = rest.grant, originRoute = "pomodoro_plan",
                observedAt = observedAt, inferred = rest.endsAt < observedAt - LATE_OBSERVATION_MS,
            )
        }
    }
    return into
}

private fun plannedRestId(id: String, round: Int, boundary: String): String =
    UUID.nameUUIDFromBytes("$id|$PLANNED_BREAK_KIND|$round|$boundary".toByteArray(Charsets.UTF_8)).toString()

/**
 * A boundary observed later than this is recorded as inferred: the logical time is
 * still the boundary, and `observedAt` keeps the actual observation.
 */
private const val LATE_OBSERVATION_MS = 5_000L

