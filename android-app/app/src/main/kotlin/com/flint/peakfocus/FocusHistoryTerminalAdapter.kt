package com.flint.peakfocus

import android.content.Context
import com.flint.peakfocus.core.datastore.EditorCommitmentStore
import com.flint.peakfocus.core.datastore.EditorTerminalEvent
import com.flint.peakfocus.core.datastore.EditorTerminalKind
import com.flint.peakfocus.core.datastore.FlintPreferences
import com.flint.peakfocus.core.datastore.deliverTerminalEvents
import com.flint.peakfocus.core.history.FocusHistoryBridge
import com.flint.peakfocus.core.history.HistoryEvent
import com.flint.peakfocus.core.history.HistoryEventTimeConfidence
import com.flint.peakfocus.core.history.HistoryEventType
import com.flint.peakfocus.core.history.HistoryRulesSnapshot
import com.flint.peakfocus.core.history.HistorySnapshot
import com.flint.peakfocus.core.history.PlannedRest
import com.flint.peakfocus.core.history.historyRulesSnapshot
import com.flint.peakfocus.core.history.plannedRestEvents
import com.flint.peakfocus.core.model.ActiveRulesHolder
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Called once by the app adapter; only the editor owner may supply terminal authority. */
internal object FocusHistoryTerminalAdapter {
    fun start(context: Context, scope: CoroutineScope) {
        val app = context.applicationContext
        val owner = FlintPreferences.editorCommitments(app)
        val history = FocusHistoryBridge.store(app)
        // The published parent is the only source of a Pomodoro plan; the owner prunes the
        // rule only after this delivery is acknowledged.
        val rests = { id: String ->
            (historyRulesSnapshot(ActiveRulesHolder.dataStoreState) as? HistoryRulesSnapshot.Ready)
                ?.rules?.firstOrNull { it.id == id }?.plannedRests.orEmpty()
        }
        scope.launch(Dispatchers.IO) {
            // Retry even when IO recovers without a new rule/terminal flow emission.
            while (isActive) {
                try { projectEditorHistoryTerminals(owner, history::update, plannedRests = rests) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Leave the owner's durable intent pending. */ }
                delay(5_000)
            }
        }
    }
}

/** Commit the owner's final plan and terminal together before acknowledgement. */
internal suspend fun projectEditorHistoryTerminals(
    owner: EditorCommitmentStore,
    update: suspend ((HistorySnapshot) -> List<HistoryEvent>) -> Boolean,
    zone: ZoneId = ZoneId.systemDefault(),
    /**
     * F01 §7: the planned rests of a parent that is completing, read from the owner's published
     * plan. The terminal is delivered before the owner prunes the rule, so the rests behind the
     * terminal land with it even if no reconciler read observed the chain while it ran.
     */
    plannedRests: (String) -> List<PlannedRest> = { emptyList() },
) = owner.deliverTerminalEvents { event ->
    val terminal = event.toHistoryEvent(zone)
    // Read the owner's published plan once, outside the history transaction: the owner prunes
    // the rule only after this delivery is acknowledged, so the plan is still readable here.
    val rests = if (event.kind == EditorTerminalKind.COMPLETED) plannedRests(event.sessionId) else emptyList()
    // The rules observer may have missed an edit, or run after this delivery.
    // The authoritative endpoint must replace that stale plan in the same write.
    update { snapshot ->
        // If an explicit outcome already won arbitration, its effective plan must
        // survive too. Dropping only this terminal could leave a backdated final
        // plan that invalidates the retained app/owner outcome. ACK the whole replay.
        if (snapshot.events.any { it.session == event.sessionId && it.knownType?.terminal == true &&
                !(it.knownType == HistoryEventType.ENDED_EARLY && it.route == "unknown") }) return@update emptyList()
        val cleared = snapshot.clearedAt != null
        val retained = snapshot.events.any {
            it.session == event.sessionId && it.knownType == HistoryEventType.STARTED
        }
        // Clear removed ended sessions and their dedupe IDs. A pending owner's
        // retry is acknowledged without restoring them; retained running IDs survive.
        // A completed orphan has no later STARTED source, even after wall rollback.
        if (cleared && !retained) emptyList() else {
            // A completed chain ran its whole plan, so every rest is behind its logical end;
            // an early end stopped the chain where it was and proves no later rest.
            val restEvents = plannedRestEvents(
                event.sessionId, rests,
                snapshot.events.filter { it.session == event.sessionId },
                now = maxOf(terminal.at, event.observedAt.wallEpochMs), tz = zone.id,
                observedAt = event.observedAt.wallEpochMs,
            )
            restEvents + listOf(HistoryEvent(
                id = "${event.id}:deadline", type = "rescheduled", session = event.sessionId,
                at = terminal.at, plannedEnd = terminal.at, observedAt = terminal.observedAt,
                inferred = true, tz = zone.id,
                originRoute = "editor_terminal", reason = "owner_final_plan", timeConfidence = terminal.timeConfidence,
            ), terminal)
        }
    }
}

internal fun EditorTerminalEvent.toHistoryEvent(zone: ZoneId): HistoryEvent = when (kind) {
    EditorTerminalKind.COMPLETED -> HistoryEvent(
        id = id, type = "completed", session = sessionId, at = logicalEnd.wallEpochMs,
        observedAt = observedAt.wallEpochMs, tz = zone.id,
        inferred = observedAt != logicalEnd,
        reason = if (timeUncertain) "time_uncertain" else "deadline",
        originRoute = "editor_terminal",
        timeConfidence = if (timeUncertain) HistoryEventTimeConfidence.Uncertain("owner_time_uncertain")
            else HistoryEventTimeConfidence.Certain,
    )
}
