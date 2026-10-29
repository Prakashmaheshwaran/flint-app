package com.flint.peakfocus.core.history

import java.time.Clock
import java.time.ZoneId
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
data class HistoryRule(val id: String, val name: String, val tier: String, val plannedEnd: Long?)

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
            val current = sessions.filterValues { it.plannedEnd == null || it.plannedEnd > now }.keys
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
                        source = when {
                            !native -> "legacy"
                            rule.name == "Focus timer" -> "manual"
                            rule.name in PRESET_NAMES -> "preset"
                            else -> "unknown"
                        },
                    )
                }
            }
            sessions.values.forEach { rule ->
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
            it.id.startsWith("session-") && (it.plannedEnd == null || it.plannedEnd > now)
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
