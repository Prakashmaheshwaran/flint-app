package com.flint.peakfocus.core.history

data class FocusCompletionAlarm(val sessionId: String, val at: Long)
enum class FocusCompletionDelivery { WAIT, SUPPRESS, STALE, POST }

/** Pure decisions shared by the startup rescheduler and receiver. */
object FocusCompletionPolicy {
    const val MAX_LATENESS_MS = 30 * 60_000L

    fun alarms(snapshot: HistorySnapshot, delivered: Set<String>, now: Long): List<FocusCompletionAlarm> {
        if (!snapshot.readable || snapshot.degraded) return emptyList()
        return snapshot.events.groupBy { it.session }.mapNotNull { (id, events) ->
            if (id in delivered) return@mapNotNull null
            val start = events.sortedBy { it.at }.firstOrNull { it.knownType == HistoryEventType.STARTED } ?: return@mapNotNull null
            val end = start.plannedEnd ?: return@mapNotNull null
            val decision = decision(snapshot, id, now)
            if (decision == FocusCompletionDelivery.WAIT || decision == FocusCompletionDelivery.POST)
                FocusCompletionAlarm(id, end) else null
        }.sortedBy { it.at }.take(256)
    }

    fun decision(snapshot: HistorySnapshot, sessionId: String, now: Long): FocusCompletionDelivery {
        if (!snapshot.readable || snapshot.degraded) return FocusCompletionDelivery.SUPPRESS
        val events = snapshot.events.filter { it.session == sessionId && it.knownType != null }
            .distinctBy { it.id }.sortedBy { it.at }
        val start = events.firstOrNull { it.knownType == HistoryEventType.STARTED } ?: return FocusCompletionDelivery.SUPPRESS
        val end = start.plannedEnd ?: return FocusCompletionDelivery.SUPPRESS
        val terminal = events.firstOrNull { it.knownType?.terminal == true }
        if (terminal != null && (terminal.knownType != HistoryEventType.COMPLETED || terminal.at != end))
            return FocusCompletionDelivery.SUPPRESS
        if (now < end) return FocusCompletionDelivery.WAIT
        if (now - end > MAX_LATENESS_MS) return FocusCompletionDelivery.STALE
        return FocusCompletionDelivery.POST
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
