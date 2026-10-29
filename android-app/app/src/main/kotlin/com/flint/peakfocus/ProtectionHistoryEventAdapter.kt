package com.flint.peakfocus

import android.content.Context
import com.flint.peakfocus.blocking.overlay.BlockEventHooks
import com.flint.peakfocus.blocking.overlay.ProtectionStatus
import com.flint.peakfocus.blocking.overlay.ProtectionStatusHub
import com.flint.peakfocus.core.history.FocusHistoryBridge
import com.flint.peakfocus.core.history.FocusHistoryStore
import com.flint.peakfocus.core.history.HistoryEvent
import com.flint.peakfocus.core.history.HistoryEventType
import com.flint.peakfocus.core.history.HistorySnapshot
import com.flint.peakfocus.core.model.ProtectionBreakEndReason
import com.flint.peakfocus.core.model.ProtectionLifecycleEvent
import com.flint.peakfocus.core.model.ProtectionLifecycleKind
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The app shell connects C's durable outbox to F's independent history store. */
internal object ProtectionHistoryEventAdapter {
    fun start(context: Context, scope: CoroutineScope) {
        BlockEventHooks.registerDurableConsumer(ProtectionHistoryConsumer(FocusHistoryBridge.store(context)))
        scope.launch {
            ProtectionStatusHub.status.collect { status ->
                if (hasMissingProtectionEvents(status)) FocusHistoryBridge.noteLifecycleEventsDropped()
            }
        }
    }
}

/** Consume only the public health projection; no history reader opens C's private ledger. */
internal fun hasMissingProtectionEvents(status: ProtectionStatus): Boolean =
    status.lifecycleEventsDropped > 0 || "lifecycle-events-dropped" in status.detectors.degradedReasons

/** Returning true acknowledges the whole projection, including all pass terminals, on disk. */
internal class ProtectionHistoryConsumer(
    private val store: FocusHistoryStore,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : BlockEventHooks.DurableConsumer {
    override suspend fun deliver(event: ProtectionLifecycleEvent): Boolean = try {
        // update validates the entire batch before writing, atomically replaces multi-record
        // batches, syncs the write and deduplicates IDs under its process-wide lock. A crash
        // after this commit but before the owner's ack therefore replays the same records.
        store.update { snapshot -> project(event, snapshot) }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        false
    }

    private fun project(event: ProtectionLifecycleEvent, snapshot: HistorySnapshot): List<HistoryEvent> {
        require(event.id.isNotBlank() && event.operationId.isNotBlank())
        val predatesClear = snapshot.clearedAt?.let { event.at.wallEpochMs <= it } == true
        // A clear may commit after history append but before the owner acknowledges it.
        // Inspect the cutoff and retained starts under update's lock so retry cannot
        // resurrect cleared sessions, including through a global pass's references.
        val retained = if (predatesClear) snapshot.events.asSequence()
            .filter { it.knownType == HistoryEventType.STARTED }.map { it.session }.toSet() else emptySet()
        val sessions = event.affectedRuleIds.filter(::isSession)
            .filter { !predatesClear || it in retained }.sorted()
        val timeZone = zone().id
        fun record(type: HistoryEventType, session: String) = HistoryEvent(
            id = projectionId(event.id, type, session), type = type.wireName, session = session,
            at = event.at.wallEpochMs, tz = timeZone, inferred = event.timeUncertain,
        )
        return when (event.kind) {
            ProtectionLifecycleKind.PASS_COMMITTED -> {
                val until = requireNotNull(event.untilEpochMs)
                require(until > event.at.wallEpochMs)
                require(event.affectedRuleIds.containsAll(event.endedTransientIds))
                if (predatesClear && sessions.isEmpty()) return emptyList()
                // The operation id is opaque. A single global pass may affect no focus
                // sessions at all; ordinary rules never become focus sessions here.
                listOf(record(HistoryEventType.PASS_USED, event.operationId).copy(
                    grant = event.operationId, sessions = sessions, recoveryEnd = until,
                )) + sessions.filter { it in event.endedTransientIds }.map { session ->
                    record(HistoryEventType.ENDED_EARLY, session).copy(
                        route = "emergency_pass", reason = "emergency_pass", grant = event.operationId,
                    )
                }
            }
            ProtectionLifecycleKind.BREAK_STARTED -> {
                val grant = requireNotNull(event.grantId).also { require(it.isNotBlank()) }
                val until = requireNotNull(event.untilEpochMs)
                require(until > event.at.wallEpochMs)
                sessions.map { session -> record(HistoryEventType.BREAK_STARTED, session).copy(
                    kind = "ordinary", grant = grant, breakEnd = until,
                ) }
            }
            ProtectionLifecycleKind.BREAK_ENDED -> {
                val grant = requireNotNull(event.grantId).also { require(it.isNotBlank()) }
                val reason = when (requireNotNull(event.breakEndReason)) {
                    ProtectionBreakEndReason.EXPIRED -> "expired"
                    ProtectionBreakEndReason.RESUMED -> "resumed"
                    ProtectionBreakEndReason.SUPERSEDED -> "superseded"
                    ProtectionBreakEndReason.RECOVERY -> "recovery"
                }
                // Persist ends even if replay reaches them before their starts. Pairing
                // uses the owner's exact frozen scope and grant, never today's rules.
                sessions.map { session -> record(HistoryEventType.BREAK_ENDED, session).copy(
                    grant = grant, reason = reason,
                ) }
            }
        }
    }

    private fun isSession(id: String) = id.startsWith("session-")

    private fun projectionId(eventId: String, type: HistoryEventType, session: String): String =
        UUID.nameUUIDFromBytes(buildString {
            append("flint-protection-history-v1")
            // Length prefixes keep even opaque ids containing separators unambiguous.
            for (part in listOf(eventId, type.wireName, session)) append("|${part.length}:$part")
        }.toByteArray(Charsets.UTF_8)).toString()
}
