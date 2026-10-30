package com.flint.peakfocus.blocking.engine

/**
 * A single replaceable timer, independent of Android/coroutines for fake-clock verification.
 * The service serializes calls on its event loop. Generation checks reject an already queued
 * callback after foreground/rule/clock changes; a backwards clock never fires a boundary early.
 */
class EvaluationTimer(
    private val nowMs: () -> Long,
    private val post: (delayMs: Long, callback: () -> Unit) -> (() -> Unit),
    private val evaluate: () -> Unit,
) {
    private var generation = 0L
    private var cancelPending: (() -> Unit)? = null

    fun arm(deadlineMs: Long?) {
        cancel()
        deadlineMs ?: return
        val token = generation
        cancelPending = post((deadlineMs - nowMs()).coerceAtLeast(1L)) {
            if (token == generation) {
                cancelPending = null
                if (nowMs() < deadlineMs) arm(deadlineMs) else evaluate()
            }
        }
    }

    fun cancel() {
        generation++
        cancelPending?.invoke()
        cancelPending = null
    }
}
