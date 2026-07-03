package com.flint.peakfocus.blocking.engine

/**
 * Self-contained day/week arithmetic on epoch millis. No `java.time` (needs API 26+ or
 * desugaring on Android) and no `Math.floorDiv` (API 24+) — keeps this module pure and
 * portable down to minSdk 23, same spirit as the engine's hand-rolled host extraction.
 *
 * All functions take an optional [utcOffsetMs] so callers can anchor "day" and "week" to
 * local wall time: Android callers pass `TimeZone.getDefault().getOffset(now)`. The offset
 * is a snapshot — a DST change can shift a day/week boundary by an hour. Acceptable for
 * daily open counts and a weekly pass; documented rather than hidden.
 */
internal object DayMath {

    const val MILLIS_PER_DAY: Long = 86_400_000L

    /** Local epoch-day (days since 1970-01-01) of [nowEpochMs] at [utcOffsetMs]. */
    fun epochDay(nowEpochMs: Long, utcOffsetMs: Long = 0L): Long =
        floorDiv(nowEpochMs + utcOffsetMs, MILLIS_PER_DAY)

    /** ISO day of week for an epoch-day: 1 = Monday … 7 = Sunday (1970-01-01 was a Thursday). */
    fun isoDayOfWeek(epochDay: Long): Int = (floorMod(epochDay + 3L, 7L) + 1L).toInt()

    /** Epoch-day of the Monday starting the ISO week that contains [nowEpochMs]. */
    fun weekStartEpochDay(nowEpochMs: Long, utcOffsetMs: Long = 0L): Long {
        val day = epochDay(nowEpochMs, utcOffsetMs)
        return day - (isoDayOfWeek(day) - 1)
    }

    private fun floorDiv(a: Long, b: Long): Long {
        var q = a / b
        if ((a xor b) < 0 && q * b != a) q--
        return q
    }

    private fun floorMod(a: Long, b: Long): Long = a - floorDiv(a, b) * b
}
