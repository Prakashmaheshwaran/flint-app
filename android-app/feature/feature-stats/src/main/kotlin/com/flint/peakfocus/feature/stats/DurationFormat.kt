package com.flint.peakfocus.feature.stats

/**
 * Human-readable durations for the stats screen. Pure Kotlin (JVM-testable).
 *
 * Rules: zero/negative → `0m`; under a minute → `<1m`; under an hour → `37m`;
 * exact hours → `2h`; otherwise → `2h 14m`. Always rounds down (screen-time apps
 * shouldn't inflate).
 */
object DurationFormat {

    fun format(millis: Long): String {
        if (millis <= 0L) return "0m"
        val totalMinutes = millis / 60_000L
        if (totalMinutes < 1L) return "<1m"
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return when {
            hours == 0L -> "${minutes}m"
            minutes == 0L -> "${hours}h"
            else -> "${hours}h ${minutes}m"
        }
    }
}
