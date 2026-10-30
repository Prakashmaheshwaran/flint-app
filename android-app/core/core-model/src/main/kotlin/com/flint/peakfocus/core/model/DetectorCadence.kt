package com.flint.peakfocus.core.model

/** Signals wake standby immediately; its periodic permission-health probe runs once a minute. */
object DetectorCadence {
    const val STANDBY_MS = 60_000L
    fun delayMs(mode: FallbackMode): Long = if (mode == FallbackMode.ENFORCING) 1_000L else STANDBY_MS
}
