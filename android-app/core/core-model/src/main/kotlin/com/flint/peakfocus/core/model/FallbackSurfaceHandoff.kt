package com.flint.peakfocus.core.model

/**
 * Dismiss only a currently started fallback surface, once on a live Path A transition.
 * It never dismisses on disconnect, repeated clock/rule notifications, or unrelated activities.
 * The Android adapter supplies only BlockActivity instances; calls share its main event loop.
 */
class FallbackSurfaceHandoff<T> {
    private val started = mutableSetOf<T>()
    private var pathALive = false

    fun started(surface: T) { started += surface }
    fun stopped(surface: T) { started -= surface }

    fun pathAChanged(live: Boolean): List<T> {
        val tookOver = live && !pathALive
        pathALive = live
        return if (tookOver) started.toList() else emptyList()
    }
}
