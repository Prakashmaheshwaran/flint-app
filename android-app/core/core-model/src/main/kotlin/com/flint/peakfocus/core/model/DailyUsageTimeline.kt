package com.flint.peakfocus.core.model

/**
 * Share an incremental event timeline across budget readers. The long carry-in query runs once
 * per local day (or clock rewind), not on every 15-second budget refresh. The buffer is bounded
 * by that initial lookback plus the current local day; subsequent queries use a small overlap.
 * Calls are serialized because both detectors can finish a measurement during an ownership swap.
 */
class DailyUsageTimeline(private val read: (startMs: Long, endMs: Long) -> List<ForegroundEvent>) {
    private val cursor = ForegroundEventCursor()
    private val events = mutableListOf<ForegroundEvent>()
    private var dayStart: Long? = null

    @Synchronized
    fun millis(packageName: String, startOfDayMs: Long, nowMs: Long): Long {
        if (dayStart != startOfDayMs || cursor.needsReset(nowMs)) {
            cursor.reset()
            events.clear()
            dayStart = startOfDayMs
        }
        val incoming = read(cursor.queryStart(nowMs), nowMs)
        events += cursor.commit(nowMs, incoming)
        return ForegroundUsage.millis(packageName, events, startOfDayMs, nowMs)
    }
}
