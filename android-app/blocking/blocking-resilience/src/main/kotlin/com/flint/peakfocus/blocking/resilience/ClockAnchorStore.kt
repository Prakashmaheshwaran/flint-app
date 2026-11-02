package com.flint.peakfocus.blocking.resilience

import android.content.SharedPreferences
import com.flint.peakfocus.blocking.engine.ClockShift
import com.flint.peakfocus.blocking.engine.trustedElapsedMs
import com.flint.peakfocus.core.model.AccessClockStamp

/** Tiny receiver evidence record. Startup preserves it until a receiver measures the jump. */
internal class ClockAnchorStore(private val prefs: SharedPreferences) {
    fun initialize(now: AccessClockStamp, offsetMs: Long) = synchronized(lock) {
        if (!prefs.contains(WALL) && !prefs.contains(ELAPSED) && !prefs.contains(VERSION)) write(now, offsetMs)
    }

    fun measureAndRebaseline(now: AccessClockStamp, offsetMs: Long): ClockShift = synchronized(lock) {
        // V1 stored wall/elapsed/offset but no boot identity. It can migrate, but cannot prove
        // same-boot progression even when the new boot's uptime exceeds the saved uptime.
        val version = runCatching { prefs.getInt(VERSION, 1) }.getOrNull()
        if (version != 1 && version != 2) return@synchronized ClockShift.unmeasured(now.wallEpochMs, offsetMs, offsetMs)
        val previous = runCatching {
            val wall = prefs.getLong(WALL, Long.MIN_VALUE)
            val elapsed = prefs.getLong(ELAPSED, -1)
            if (wall == Long.MIN_VALUE || elapsed < 0) null
            else AccessClockStamp(wall, elapsed, if (version == 2) prefs.getString(BOOT, "").orEmpty() else "")
        }.getOrNull()
        val oldOffset = runCatching { prefs.getLong(OFFSET, offsetMs) }.getOrDefault(offsetMs)
        val shift = if (previous != null && trustedElapsedMs(previous, now) != null) {
            ClockShift.fromAnchor(previous.wallEpochMs, previous.elapsedRealtimeMs,
                now.wallEpochMs, now.elapsedRealtimeMs, oldOffset, offsetMs)
        } else ClockShift.unmeasured(now.wallEpochMs, oldOffset, offsetMs)
        write(now, offsetMs)
        shift
    }

    private fun write(now: AccessClockStamp, offsetMs: Long) {
        prefs.edit().putInt(VERSION, 2).putLong(WALL, now.wallEpochMs)
            .putLong(ELAPSED, now.elapsedRealtimeMs).putString(BOOT, now.bootSessionId)
            .putLong(OFFSET, offsetMs).apply()
    }

    companion object {
        private val lock = Any()
        const val PREFS = "flint_clock_anchor"
        const val VERSION = "anchor_version"
        const val WALL = "wall_epoch_ms"
        const val ELAPSED = "elapsed_realtime_ms"
        const val OFFSET = "utc_offset_ms"
        const val BOOT = "boot_session_id"
    }
}
