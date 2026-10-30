package com.flint.peakfocus.blocking.overlay

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.WeakHashMap

/** One-shot, process-local acknowledgement of a recovery frame, never access authority. */
internal object RecoverySurfaceHandoff {
    private val waiting = ConcurrentHashMap<String, () -> Unit>()
    private data class Surface(val target: String, val untilElapsedMs: Long)
    private val surfaces = WeakHashMap<BlockActivity, Surface>()
    internal const val VISIBLE_LEASE_MS = 2_000L

    fun showing(activity: BlockActivity, target: String) = synchronized(surfaces) {
        surfaces[activity] = Surface(target, android.os.SystemClock.elapsedRealtime() + VISIBLE_LEASE_MS)
    }
    fun hidden(activity: BlockActivity) = synchronized(surfaces) { surfaces.remove(activity); Unit }
    fun isShowing(target: String): Boolean = synchronized(surfaces) {
        val now = android.os.SystemClock.elapsedRealtime()
        surfaces.values.any { it.target == target && now < it.untilElapsedMs }
    }

    fun register(onDrawn: () -> Unit): Pair<String, () -> Unit> {
        val id = UUID.randomUUID().toString()
        waiting[id] = onDrawn
        return id to { waiting.remove(id); Unit }
    }

    fun drawn(id: String) { waiting.remove(id)?.invoke() }
}
