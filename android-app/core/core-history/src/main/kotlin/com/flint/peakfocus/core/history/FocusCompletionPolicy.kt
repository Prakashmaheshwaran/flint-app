package com.flint.peakfocus.core.history

data class FocusCompletionAlarm(val sessionId: String, val at: Long)
enum class FocusCompletionDelivery { WAIT, SUPPRESS, STALE, POST }
data class FocusCompletionSchedule(val alarms: List<FocusCompletionAlarm>, val staleSessionIds: Set<String>)

/** Pure decisions shared by the startup rescheduler and receiver. */
object FocusCompletionPolicy {
    const val MAX_LATENESS_MS = 30 * 60_000L

    fun alarms(snapshot: HistorySnapshot, delivered: Set<String>, now: Long): List<FocusCompletionAlarm> =
        schedule(snapshot, delivered, now).alarms

    fun schedule(snapshot: HistorySnapshot, delivered: Set<String>, now: Long): FocusCompletionSchedule {
        if (!snapshot.readable || snapshot.degraded) return FocusCompletionSchedule(emptyList(), emptySet())
        val alarms = mutableListOf<FocusCompletionAlarm>(); val stale = mutableSetOf<String>()
        snapshot.events.groupBy { it.session }.forEach { (id, events) ->
            if (id !in delivered) {
                val (end, decision) = evaluate(events, now)
                when (decision) {
                    FocusCompletionDelivery.WAIT, FocusCompletionDelivery.POST -> alarms += FocusCompletionAlarm(id, requireNotNull(end))
                    FocusCompletionDelivery.STALE -> stale += id
                    FocusCompletionDelivery.SUPPRESS -> Unit
                }
            }
        }
        return FocusCompletionSchedule(alarms.sortedBy { it.at }.take(256), stale)
    }

    fun decision(snapshot: HistorySnapshot, sessionId: String, now: Long): FocusCompletionDelivery {
        if (!snapshot.readable || snapshot.degraded) return FocusCompletionDelivery.SUPPRESS
        return evaluate(snapshot.events.filter { it.session == sessionId }, now).second
    }

    private fun evaluate(group: List<HistoryEvent>, now: Long): Pair<Long?, FocusCompletionDelivery> {
        val events = group.filter { it.knownType != null }
            .distinctBy { it.id }.sortedBy { it.at }
        val start = events.firstOrNull { it.knownType == HistoryEventType.STARTED }
            ?: return null to FocusCompletionDelivery.SUPPRESS
        val terminalIndex = events.indexOfFirst { it.knownType?.terminal == true }
        val terminal = events.getOrNull(terminalIndex)
        val plan = events.take(if (terminalIndex < 0) events.size else terminalIndex).lastOrNull {
            it.knownType in setOf(HistoryEventType.STARTED, HistoryEventType.RESCHEDULED)
        } ?: start
        val end = plan.plannedEnd ?: return null to FocusCompletionDelivery.SUPPRESS
        if (terminal != null && (terminal.knownType != HistoryEventType.COMPLETED || terminal.at != end))
            return end to FocusCompletionDelivery.SUPPRESS
        if (now < end) return end to FocusCompletionDelivery.WAIT
        val oldest = if (now < Long.MIN_VALUE + MAX_LATENESS_MS) Long.MIN_VALUE else now - MAX_LATENESS_MS
        if (end < oldest) return end to FocusCompletionDelivery.STALE
        return end to FocusCompletionDelivery.POST
    }
}

interface FocusCompletionDeliveryState {
    suspend fun wasDelivered(sessionId: String): Boolean
    /** Must durably commit before returning true. Failure suppresses notification. */
    suspend fun markDelivered(sessionId: String, at: Long): Boolean
}

/** Tests the actual receiver work without Android classes or mocked platform permissions. */
class FocusCompletionDeliveryService(
    private val state: FocusCompletionDeliveryState,
    private val readHistory: suspend () -> HistorySnapshot,
    private val notificationsEnabled: () -> Boolean,
    private val post: (String) -> Unit,
) {
    suspend fun deliver(sessionId: String, now: Long): FocusCompletionDelivery {
        if (state.wasDelivered(sessionId)) return FocusCompletionDelivery.SUPPRESS
        val decision = FocusCompletionPolicy.decision(readHistory(), sessionId, now)
        if (decision == FocusCompletionDelivery.WAIT || decision == FocusCompletionDelivery.SUPPRESS) return decision
        // Mark before notify: a process crash may omit a notification, but cannot duplicate it.
        if (!state.markDelivered(sessionId, now)) return FocusCompletionDelivery.SUPPRESS
        if (decision == FocusCompletionDelivery.POST && notificationsEnabled()) post(sessionId)
        return decision
    }
}
