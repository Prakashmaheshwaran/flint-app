package com.flint.peakfocus.core.model

/** Public UsageEvents fields only; no Android classes or hidden instance-id API. */
data class ForegroundEvent(
    val timestampMs: Long,
    val kind: Kind,
    val packageName: String? = null,
    val activityName: String? = null,
) {
    enum class Kind { RESUMED, PAUSED, STOPPED, SCREEN_OFF, SHUTDOWN, STARTUP }
}

/** Retains an observation until an actual transition, never until an arbitrary query timeout. */
class ForegroundTracker {
    var packageName: String? = null
        private set
    private var activityName: String? = null
    private var lastEventMs = Long.MIN_VALUE

    fun accept(event: ForegroundEvent) {
        // An event arriving late through the cursor's overlap must not rewind newer state.
        if (event.timestampMs < lastEventMs) return
        lastEventMs = event.timestampMs
        when (event.kind) {
            ForegroundEvent.Kind.RESUMED -> {
                packageName = event.packageName?.takeIf { it.isNotBlank() }
                activityName = event.activityName
            }
            ForegroundEvent.Kind.PAUSED, ForegroundEvent.Kind.STOPPED -> {
                if (event.packageName == packageName &&
                    (event.activityName == null || event.activityName == activityName)
                ) clear()
            }
            ForegroundEvent.Kind.SCREEN_OFF, ForegroundEvent.Kind.SHUTDOWN,
            ForegroundEvent.Kind.STARTUP -> clear()
        }
    }

    fun clear() { packageName = null; activityName = null }

    fun reset() { clear(); lastEventMs = Long.MIN_VALUE }
}

/**
 * Incremental query cursor. The small overlap catches event delivery lag; identity de-duplication
 * prevents an old resume from reopening an app every tick. A clock rewind requires a full rebuild.
 * Call [commit] only after a successful query; failed queries never advance the watermark.
 */
class ForegroundEventCursor(
    private val initialLookbackMs: Long = 7 * 24 * 60 * 60_000L,
    private val overlapMs: Long = 2_000L,
) {
    private var queriedThrough: Long? = null
    private val seenCounts = mutableMapOf<ForegroundEvent, Int>()

    init { require(initialLookbackMs > 0 && overlapMs >= 0) }

    fun needsReset(nowMs: Long): Boolean = queriedThrough?.let { nowMs < it } == true

    fun queryStart(nowMs: Long): Long =
        if (queriedThrough == null || needsReset(nowMs)) nowMs - initialLookbackMs
        else queriedThrough!! - overlapMs

    fun commit(nowMs: Long, events: List<ForegroundEvent>): List<ForegroundEvent> {
        if (needsReset(nowMs)) reset()
        val start = queryStart(nowMs)
        val batchCounts = mutableMapOf<ForegroundEvent, Int>()
        val fresh = events.asSequence().filter { it.timestampMs in start..nowMs }
            .sortedBy { it.timestampMs }.filter { event ->
                // Public events have no unique id. Occurrence counts preserve a legitimate
                // resume/pause/resume sequence sharing a timestamp, while de-duping overlap.
                val count = (batchCounts[event] ?: 0) + 1
                batchCounts[event] = count
                count > (seenCounts[event] ?: 0)
            }.toList()
        batchCounts.forEach { (event, count) -> seenCounts[event] = maxOf(count, seenCounts[event] ?: 0) }
        queriedThrough = nowMs
        seenCounts.keys.removeAll { it.timestampMs < nowMs - overlapMs }
        return fresh
    }

    fun reset() { queriedThrough = null; seenCounts.clear() }
}

/**
 * Reconstructs package foreground intervals and includes the still-resumed interval up to now.
 * Read events before midnight to establish the carry-in, but charge only [startMs, nowMs]. Multiple
 * resumed activities in a package contribute their union, not a sum. Screen-off/shutdown closes
 * all sessions, and a startup never charges an unknown gap across a missing shutdown.
 */
object ForegroundUsage {
    fun millis(packageName: String, events: List<ForegroundEvent>, startMs: Long, nowMs: Long): Long {
        if (nowMs <= startMs) return 0L
        val resumed = mutableSetOf<String?>()
        var openedAt: Long? = null
        var total = 0L
        var shutdown = false

        fun close(at: Long) {
            openedAt?.let { total += (minOf(at, nowMs) - maxOf(it, startMs)).coerceAtLeast(0L) }
            openedAt = null
            resumed.clear()
        }

        for (event in events.sortedBy { it.timestampMs }) {
            if (event.timestampMs > nowMs) break
            when (event.kind) {
                ForegroundEvent.Kind.SHUTDOWN -> { close(event.timestampMs); shutdown = true }
                ForegroundEvent.Kind.STARTUP -> {
                    // Android does not know the close time when shutdown was not recorded.
                    openedAt = null; resumed.clear(); shutdown = false
                }
                ForegroundEvent.Kind.SCREEN_OFF -> close(event.timestampMs)
                ForegroundEvent.Kind.RESUMED -> if (!shutdown && event.packageName == packageName) {
                    if (resumed.isEmpty()) openedAt = event.timestampMs
                    resumed += event.activityName
                }
                ForegroundEvent.Kind.PAUSED, ForegroundEvent.Kind.STOPPED ->
                    if (event.packageName == packageName) {
                        if (event.activityName == null) resumed.clear() else resumed.remove(event.activityName)
                        if (resumed.isEmpty()) close(event.timestampMs)
                    }
            }
        }
        close(nowMs)
        return total.coerceIn(0L, nowMs - startMs)
    }
}
