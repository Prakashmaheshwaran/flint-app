package com.flint.peakfocus.blocking.overlay

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.WeakHashMap

/** One-shot, process-local acknowledgement of a recovery frame, never access authority. */
internal object RecoverySurfaceHandoff {
    private data class Pending(val ready: (Boolean) -> Unit, val drawn: () -> Unit)
    private val waiting = ConcurrentHashMap<String, Pending>()
    private data class Surface(val target: String, val untilElapsedMs: Long)
    private val surfaces = WeakHashMap<BlockActivity, Surface>()
    internal const val VISIBLE_LEASE_MS = 2_000L

    fun showing(activity: BlockActivity, target: String) = synchronized(surfaces) {
        surfaces[activity] = Surface(target, android.os.SystemClock.elapsedRealtime() + VISIBLE_LEASE_MS)
    }
    fun hidden(activity: BlockActivity): Boolean = synchronized(surfaces) { surfaces.remove(activity) != null }
    fun isShowing(target: String): Boolean = synchronized(surfaces) {
        val now = android.os.SystemClock.elapsedRealtime()
        surfaces.values.any { it.target == target && now < it.untilElapsedMs }
    }

    fun register(onReady: (Boolean) -> Unit, onDrawn: () -> Unit): Pair<String, () -> Unit> {
        val id = UUID.randomUUID().toString()
        waiting[id] = Pending(onReady, onDrawn)
        return id to { waiting.remove(id); Unit }
    }

    fun ready(id: String, ready: Boolean) { waiting[id]?.ready?.invoke(ready) }
    fun drawn(id: String) { waiting.remove(id)?.drawn?.invoke() }
}
