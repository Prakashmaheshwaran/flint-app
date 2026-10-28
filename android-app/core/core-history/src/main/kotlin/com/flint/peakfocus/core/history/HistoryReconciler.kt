package com.flint.peakfocus.core.history

import java.time.Clock
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Contains no target identifiers and never mutates the rules store. */
data class HistoryRule(val id: String, val name: String, val tier: String, val plannedEnd: Long?)

class HistoryReconciler(
    private val store: FocusHistoryStore,
    private val clock: Clock = Clock.systemUTC(),
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    private var firstSuccessfulSnapshot = true
    private val active = MutableStateFlow<Set<String>>(emptySet())
    val activeSessionIds: StateFlow<Set<String>> = active.asStateFlow()
    private val diagnostics = MutableStateFlow<String?>(null)
    val diagnostic: StateFlow<String?> = diagnostics.asStateFlow()

    /** Retry only errors, never synthesize an empty snapshot on a read failure. */
    suspend fun collect(rules: Flow<List<HistoryRule>>) {
        while (true) {
            try {
                rules.collect { observe(it) }
                return
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                diagnostics.value = "Rules unavailable: ${e.javaClass.simpleName}"
                delay(1_000)
            }
        }
    }

    suspend fun observe(rules: List<HistoryRule>) {
        val now = clock.millis()
        val sessions = rules.filter { it.id.startsWith("session-") }.associateBy { it.id }
        val current = sessions.filterValues { it.plannedEnd == null || it.plannedEnd > now }.keys
        val coldStart = firstSuccessfulSnapshot
        val success = store.update { snapshot ->
            val events = mutableListOf<HistoryEvent>()
            val known = snapshot.events.filter { it.knownType != null }.groupBy { it.session }
            sessions.values.filter { it.id in current }.forEach { rule ->
                if (known[rule.id].orEmpty().none { it.knownType == HistoryEventType.STARTED }) {
                    val native = rule.id.startsWith("session-timer-")
                    val timestamp = if (native) rule.id.removePrefix("session-timer-").substringBefore('-').toLongOrNull() else null
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
                val plan = group.sortedBy { it.at }.lastOrNull {
                    it.knownType in setOf(HistoryEventType.STARTED, HistoryEventType.RESCHEDULED)
                } ?: return@forEach
                if (plan.plannedEnd != rule.plannedEnd) {
                    events += event(HistoryEventType.RESCHEDULED, rule.id, now).copy(plannedEnd = rule.plannedEnd)
                }
            }
            (snapshot.events + events).filter { it.knownType != null }.groupBy { it.session }.forEach { (id, group) ->
                if (group.none { it.knownType == HistoryEventType.STARTED }) return@forEach
                if (group.any { it.knownType?.terminal == true }) return@forEach
                val plan = group.sortedBy { it.at }.last { it.knownType in setOf(HistoryEventType.STARTED, HistoryEventType.RESCHEDULED) }
                val rule = sessions[id]
                val deadline = if (rule != null) rule.plannedEnd else plan.plannedEnd
                if (deadline != null && now >= deadline) {
                    // Expiry is provable even if ActiveRulesBridge has not swept the row yet.
                    events += event(HistoryEventType.COMPLETED, id, deadline).copy(inferred = coldStart || id in sessions)
                } else if (id !in current) {
                    events += event(HistoryEventType.ENDED_EARLY, id, now).copy(route = "unknown", inferred = coldStart)
                }
            }
            events
        }
        if (success) {
            active.value = current
            firstSuccessfulSnapshot = false
            diagnostics.value = null
        } else diagnostics.value = "History write unavailable"
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
