package com.flint.peakfocus.glance

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Only pixels/words belong in the render key; a fresh health heartbeat is not new content. */
internal fun GlanceDisplay.widgetKey() = listOf(status, detail, next, focusedToday, historyNote,
    checked, focusedTodayLabel)

/** Coalesces optional surface IO, while checking notification eligibility on every source change. */
internal class GlanceSurfaceDelivery(
    private val notify: (GlanceDisplay) -> Unit,
    private val schedule: (Long?) -> Unit,
    private val render: suspend () -> Unit,
    private val nowElapsed: () -> Long,
) {
    private val lock = Mutex()
    private var lastRendered: List<String?>? = null
    private var scheduledAt: Long? = null

    suspend fun publish(display: GlanceDisplay) {
        lock.withLock {
            notify(display)
            val at = display.refreshAtElapsedMs
            val previous = scheduledAt
            // Keep an earlier pending check instead of pushing it back on every heartbeat.
            if ((at == null && previous != null) || (at != null &&
                    (previous == null || previous <= nowElapsed() || at < previous))) {
                schedule(at)
                scheduledAt = at
            }
            val key = display.widgetKey()
            if (lastRendered != key) {
                render() // An exception leaves the key unchanged so the next event retries.
                lastRendered = key
            }
        }
    }
}
