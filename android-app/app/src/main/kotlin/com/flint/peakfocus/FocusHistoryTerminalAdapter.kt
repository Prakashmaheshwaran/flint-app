package com.flint.peakfocus

import android.content.Context
import com.flint.peakfocus.core.datastore.EditorCommitmentStore
import com.flint.peakfocus.core.datastore.EditorTerminalEvent
import com.flint.peakfocus.core.datastore.EditorTerminalKind
import com.flint.peakfocus.core.datastore.FlintPreferences
import com.flint.peakfocus.core.datastore.deliverTerminalEvents
import com.flint.peakfocus.core.history.FocusHistoryBridge
import com.flint.peakfocus.core.history.HistoryEvent
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
        scope.launch(Dispatchers.IO) {
            // Retry even when IO recovers without a new rule/terminal flow emission.
            while (isActive) {
                try { projectEditorHistoryTerminals(owner, history::append) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Leave the owner's durable intent pending. */ }
                delay(5_000)
            }
        }
    }
}

/** The owner acknowledges only true delivery; stable ids make a crash before ack safe. */
internal suspend fun projectEditorHistoryTerminals(
    owner: EditorCommitmentStore,
    append: suspend (HistoryEvent) -> Boolean,
    zone: ZoneId = ZoneId.systemDefault(),
) = owner.deliverTerminalEvents { event -> append(event.toHistoryEvent(zone)) }

internal fun EditorTerminalEvent.toHistoryEvent(zone: ZoneId): HistoryEvent = when (kind) {
    EditorTerminalKind.COMPLETED -> HistoryEvent(
        id = id, type = "completed", session = sessionId, at = logicalEnd.wallEpochMs,
        observedAt = observedAt.wallEpochMs, tz = zone.id,
        inferred = observedAt != logicalEnd,
        reason = if (timeUncertain) "time_uncertain" else "deadline",
    )
}
