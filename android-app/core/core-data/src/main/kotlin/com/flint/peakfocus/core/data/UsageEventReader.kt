package com.flint.peakfocus.core.data

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import com.flint.peakfocus.core.model.ForegroundEvent

/** Thin adapter; the tracker and accounting both consume the same public event vocabulary. */
object UsageEventReader {
    fun read(manager: UsageStatsManager, startMs: Long, endMs: Long): List<ForegroundEvent> {
        val events = checkNotNull(manager.queryEvents(startMs, endMs)) { "Usage event history unavailable" }
        val event = UsageEvents.Event()
        val result = mutableListOf<ForegroundEvent>()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val kind = when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> ForegroundEvent.Kind.RESUMED
                UsageEvents.Event.ACTIVITY_PAUSED -> ForegroundEvent.Kind.PAUSED
                UsageEvents.Event.ACTIVITY_STOPPED -> ForegroundEvent.Kind.STOPPED
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> ForegroundEvent.Kind.SCREEN_OFF
                UsageEvents.Event.DEVICE_SHUTDOWN -> ForegroundEvent.Kind.SHUTDOWN
                UsageEvents.Event.DEVICE_STARTUP -> ForegroundEvent.Kind.STARTUP
                else -> null
            } ?: continue
            result += ForegroundEvent(event.timeStamp, kind, event.packageName, event.className)
        }
        return result
    }
}
