package com.flint.peakfocus.blocking.usagestats

import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.PowerManager
import com.flint.peakfocus.core.data.UsageEventReader
import com.flint.peakfocus.core.model.ForegroundEventCursor
import com.flint.peakfocus.core.model.ForegroundTracker

/**
 * Polls [UsageStatsManager] for the current foreground package. This is the fallback detector
 * (path B) used when the AccessibilityService is disabled/revoked. It can only *observe* — it
 * never blocks by itself; enforcement is the overlay/foreground service's job.
 *
 * Note: `ACTIVITY_RESUMED` (API 29) is the numeric alias of the legacy `MOVE_TO_FOREGROUND`
 * (value 1, since API 21), so referencing it is safe down to minSdk 23.
 */
class UsagePoller(
    private val usm: UsageStatsManager,
    private val isInteractive: () -> Boolean = { true },
) {
    private val cursor = ForegroundEventCursor()
    private val tracker = ForegroundTracker()

    constructor(context: Context) : this(
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager,
        { (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive },
    )

    /** Retained foreground is returned even when this tick has no new events. */
    fun currentForegroundPackage(now: Long): String? {
        if (cursor.needsReset(now)) tracker.reset()
        val events = UsageEventReader.read(usm, cursor.queryStart(now), now)
        cursor.commit(now, events).forEach(tracker::accept)
        if (!isInteractive()) tracker.clear()
        return tracker.packageName
    }

    fun reset() { cursor.reset(); tracker.reset() }
}
