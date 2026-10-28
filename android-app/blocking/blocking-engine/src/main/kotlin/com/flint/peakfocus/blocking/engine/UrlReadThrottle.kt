package com.flint.peakfocus.blocking.engine

/**
 * Leading + trailing throttle using a monotonic clock. Every request in a burst is coalesced
 * into the next 500ms boundary; a continuing burst cannot starve its final URL read. Changing
 * windows or cancelling invalidates even callbacks that were already queued by the scheduler.
 */
class UrlReadThrottle<K : Any>(
    private val nowMs: () -> Long,
    private val post: (Long, () -> Unit) -> (() -> Unit),
    private val read: (window: K, trailing: Boolean) -> Unit,
    private val intervalMs: Long = 500L,
) {
    init { require(intervalMs > 0) }
    private var window: K? = null
    private var lastRead: Long? = null
    private var cancelPending: (() -> Unit)? = null
    private var generation = 0L

    /** Returns true for an immediate read, false when coalesced into the trailing read. */
    fun request(current: K): Boolean {
        if (current != window) {
            cancel()
            window = current
        }
        val last = lastRead
        if (last == null || nowMs() - last >= intervalMs) {
            cancelPending?.invoke()
            cancelPending = null
            generation++
            lastRead = nowMs()
            read(current, false)
            return true
        } else if (cancelPending == null) {
            val token = generation
            cancelPending = post((intervalMs - (nowMs() - last)).coerceAtLeast(1L)) {
                if (token == generation && window == current) {
                    cancelPending = null
                    lastRead = nowMs()
                    read(current, true)
                }
            }
        }
        return false
    }

    fun cancel() {
        generation++
        cancelPending?.invoke()
        cancelPending = null
        window = null
        lastRead = null
    }
}
