package com.flint.peakfocus.core.model

/** Signals wake standby immediately; its periodic permission-health probe runs once a minute. */
object DetectorCadence {
    const val STANDBY_MS = 60_000L
    fun delayMs(mode: FallbackMode): Long = if (mode == FallbackMode.ENFORCING) 1_000L else STANDBY_MS
}

/** Check in-process liveness before deciding whether an AppOps binder probe is necessary. */
class DetectorModeProbe(private val usageAccess: () -> Boolean) {
    private var checkedAt: Long? = null
    private var granted = false

    fun mode(nowMs: Long, pathAConnected: Boolean): FallbackMode {
        val checked = checkedAt
        if (!pathAConnected || checked == null || nowMs < checked || nowMs - checked >= DetectorCadence.STANDBY_MS) {
            granted = usageAccess()
            checkedAt = nowMs
        }
        return DetectorFailover.mode(granted, pathAConnected)
    }

    fun invalidate() { checkedAt = null }
}
