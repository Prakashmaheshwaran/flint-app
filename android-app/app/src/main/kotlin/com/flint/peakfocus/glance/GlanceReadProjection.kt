package com.flint.peakfocus.glance

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** Each optional display read fails independently; never turn a read failure into zero/idle. */
internal suspend fun readGlanceDisplay(
    protection: GlanceProtectionReader,
    focus: GlanceFocusReader,
    nowElapsedMs: () -> Long,
    nowEpochMs: () -> Long,
): GlanceDisplay {
    val reading = guardedGlanceRead({ ProtectionReading() }) { protection.read() }
    val history = guardedGlanceRead({ FocusTodayReading() }) { focus.read() }
    return GlanceDisplayMapper.map(reading, history, nowElapsedMs(), nowEpochMs())
}

internal suspend fun <T> guardedGlanceRead(fallback: () -> T, timeoutMs: Long = 2_000, read: suspend () -> T): T = try {
    withTimeout(timeoutMs) { read() }
} catch (_: TimeoutCancellationException) {
    fallback()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    fallback()
}
