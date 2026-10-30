package com.flint.peakfocus.core.model

/**
 * A briefly cached grant check. Absent/unconfirmed permission means an unmeasured budget, never
 * an exhausted one; only a confirmed grant permits a failed read to fail closed. Callers serialize
 * access and run both the platform probe and measurement off the main thread.
 */
class UsageAccessProbe(private val probe: () -> Boolean) {
    private var checkedAt: Long? = null
    private var granted: Boolean? = null

    fun measure(nowMs: Long, read: () -> Long): Long? {
        val checked = checkedAt
        if (checked == null || nowMs < checked || nowMs - checked >= CACHE_MS) {
            granted = try { probe() } catch (_: RuntimeException) { null }
            checkedAt = nowMs
        }
        if (granted != true) return null
        return try { read() } catch (_: RuntimeException) { Long.MAX_VALUE }
    }

    fun invalidate() { checkedAt = null }

    companion object { const val CACHE_MS = 1_000L }
}
