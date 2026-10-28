package com.flint.peakfocus.core.history

import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

sealed interface HistoryObservedEvent {
    val session: String?
    val at: Long
    data class Open(override val session: String?, override val at: Long) : HistoryObservedEvent
    data class Break(override val session: String?, override val at: Long, val until: Long) : HistoryObservedEvent
    data class End(override val session: String, override val at: Long, val route: String) : HistoryObservedEvent
}

/** Bounded, nonblocking handoff from synchronous enforcement hooks to independent IO. */
class HistoryObservedQueue(
    scope: CoroutineScope,
    private val write: suspend (HistoryObservedEvent) -> Boolean,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val queue = Channel<HistoryObservedEvent>(256)
    @Volatile var diagnostic: String? = null
        private set
    init { scope.launch(dispatcher) {
        for (event in queue) try {
            diagnostic = if (write(event)) null else "History event write unavailable"
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { diagnostic = e.javaClass.simpleName }
    } }
    fun offer(event: HistoryObservedEvent): Boolean = queue.trySend(event).isSuccess.also {
        if (!it) diagnostic = "History event queue full"
    }
}

/** Only known Timer sessions receive measured events. No rule/target data is copied or changed. */
class HistoryObservedWriter(private val store: FocusHistoryStore, private val zone: () -> ZoneId = { ZoneId.systemDefault() }) {
    suspend fun record(observed: HistoryObservedEvent): Boolean {
        val id = observed.session?.takeIf { it.startsWith("session-") } ?: return true
        return store.update { snapshot ->
            val events = snapshot.events.filter { it.session == id && it.knownType != null }.sortedBy { it.at }
            val start = events.firstOrNull { it.knownType == HistoryEventType.STARTED } ?: return@update emptyList()
            if (observed.at < start.at) return@update emptyList()
            val terminal = events.firstOrNull { it.knownType?.terminal == true }
            val plan = events.takeWhile { it !== terminal }.filter { it.at <= observed.at }.lastOrNull {
                it.knownType in setOf(HistoryEventType.STARTED, HistoryEventType.RESCHEDULED)
            } ?: start
            if (plan.plannedEnd?.let { observed.at >= it } == true) return@update emptyList()
            if (terminal != null && (terminal.at < observed.at || (terminal.at == observed.at && observed !is HistoryObservedEvent.End)))
                return@update emptyList()
            val base = HistoryEvent(type = "", session = id, at = observed.at, tz = zone().id)
            val event = when (observed) {
                is HistoryObservedEvent.Open -> base.copy(type = HistoryEventType.OPEN_INTERCEPTED.wireName, originRoute = "service")
                is HistoryObservedEvent.Break -> {
                    if (observed.until <= observed.at) return@update emptyList()
                    base.copy(type = HistoryEventType.BREAK_STARTED.wireName, breakEnd = observed.until,
                        kind = "ordinary", originRoute = "shield_action")
                }
                is HistoryObservedEvent.End -> {
                    if (observed.route !in setOf("hold_to_end", "cancel")) return@update emptyList()
                    base.copy(id = UUID.nameUUIDFromBytes("$id|${observed.at}|${observed.route}".toByteArray(Charsets.UTF_8)).toString(),
                        type = HistoryEventType.ENDED_EARLY.wireName, route = observed.route, originRoute = "app_ui")
                }
            }
            listOf(event)
        }
    }
}
