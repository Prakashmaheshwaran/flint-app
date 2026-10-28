package com.flint.peakfocus

import com.flint.peakfocus.blocking.engine.NextEvaluationBoundary
import com.flint.peakfocus.core.datastore.EditorCommitmentState
import com.flint.peakfocus.core.datastore.ruleCauseKey
import com.flint.peakfocus.core.model.AccessClockStamp
import com.flint.peakfocus.core.model.AppRef
import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.BlockTargets
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive

internal const val EDITOR_RETRY_MS = 5_000L

/** Wake on a changed snapshot, an exact boundary, or a failed-read retry; never poll healthy storage. */
internal suspend fun runEditorRulePublication(changes: Flow<Unit>, refresh: suspend () -> Long?, unavailable: (Throwable) -> Unit) {
    while (currentCoroutineContext().isActive) {
        try {
            changes.collectLatest {
                while (currentCoroutineContext().isActive) {
                    val nextDelay = try { refresh() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (cause: Throwable) { unavailable(cause); EDITOR_RETRY_MS }
                    if (nextDelay == null) awaitCancellation()
                    delay(nextDelay.coerceAtLeast(1))
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (cause: Throwable) { unavailable(cause) }
        delay(EDITOR_RETRY_MS)
    }
}

/** Every schedule remains visible, including the next start while no target is foreground. */
internal fun nextEditorReconciliationDelay(rules: List<BlockRule>, state: EditorCommitmentState, now: AccessClockStamp): Long? {
    val delays = state.causes.values.mapNotNull { it.interval?.remainingMs(now)?.takeIf { ms -> ms > 0 } }.toMutableList()
    state.queued.filterNot { it.needsReview }.flatMap { it.boundaries.values }.forEach {
        it.remainingMs(now).takeIf { ms -> ms > 0 }?.let(delays::add)
    }
    rules.filter { it.enabled && it.schedule != null }.forEach { rule ->
        val zone = ZoneId.of(state.causes[ruleCauseKey(rule.id)]?.zoneId ?: ZoneId.systemDefault().id)
        val boundaryRule = rule.copy(targets = BlockTargets(apps = setOf(AppRef("editor-boundary"))))
        NextEvaluationBoundary.next(now.wallEpochMs, zone, "editor-boundary", listOf(boundaryRule))
            ?.let { delays += it - now.wallEpochMs }
    }
    return delays.filter { it > 0 }.minOrNull()
}
