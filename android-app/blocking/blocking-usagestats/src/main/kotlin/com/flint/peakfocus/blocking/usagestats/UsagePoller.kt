package com.flint.peakfocus.blocking.usagestats

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

/**
 * Polls [UsageStatsManager] for the current foreground package. This is the fallback detector
 * (path B) used when the AccessibilityService is disabled/revoked. It can only *observe* — it
 * never blocks by itself; enforcement is the overlay/foreground service's job.
 *
 * Note: `ACTIVITY_RESUMED` (API 29) is the numeric alias of the legacy `MOVE_TO_FOREGROUND`
 * (value 1, since API 21), so referencing it is safe down to minSdk 23.
 */
class UsagePoller(private val usm: UsageStatsManager) {

    constructor(context: Context) : this(
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager,
    )

    /** Most-recent foreground package within [windowMs] before [now], or null if none seen. */
    fun currentForegroundPackage(now: Long, windowMs: Long = 10_000L): String? {
        val events = usm.queryEvents(now - windowMs, now)
        val event = UsageEvents.Event()
        var latest: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            @Suppress("DEPRECATION")
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                latest = event.packageName
            }
        }
        return latest
    }
}
