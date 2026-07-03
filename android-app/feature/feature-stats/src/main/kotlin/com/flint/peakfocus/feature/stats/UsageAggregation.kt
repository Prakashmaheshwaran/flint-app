package com.flint.peakfocus.feature.stats

/**
 * Pure aggregation core for the stats screen: folds a stream of foreground transitions (as
 * reported by `UsageStatsManager.queryEvents`) into per-app, per-day foreground totals.
 *
 * Deliberately Android-free — same convention as `blocking-engine` — so the day-bucketing math
 * is unit-testable on the plain JVM. The Android edge ([UsageStatsSource]) maps
 * `UsageEvents.Event` into [ForegroundEvent] and builds the [DayWindow]s with `Calendar`.
 */

/**
 * A half-open local-day window `[startMillis, endMillis)`. Callers supply windows in ascending
 * order, non-overlapping, covering the report range (7 entries for the weekly report).
 */
data class DayWindow(val startMillis: Long, val endMillis: Long)

/** The two foreground transitions the aggregator understands. */
enum class ForegroundTransition { RESUMED, PAUSED }

/** One foreground transition for a package (mapped from a `UsageEvents.Event`). */
data class ForegroundEvent(
    val packageName: String,
    val timestampMillis: Long,
    val transition: ForegroundTransition,
)

/** Per-app, per-day foreground totals over [windows]. Produced by [UsageAggregator.bucketIntoDays]. */
class DayBuckets(
    val windows: List<DayWindow>,
    private val perAppDayMillis: Map<String, LongArray>,
) {
    /** Total foreground time across all apps for the day at [dayIndex] (into [windows]). */
    fun dayTotalMillis(dayIndex: Int): Long = perAppDayMillis.values.sumOf { it[dayIndex] }

    /** Total foreground time across all apps and all days. */
    fun rangeTotalMillis(): Long = windows.indices.sumOf { dayTotalMillis(it) }

    /** Per-app totals for one day; zero-duration apps are dropped. */
    fun appTotalsForDay(dayIndex: Int): Map<String, Long> =
        perAppDayMillis.mapValues { (_, days) -> days[dayIndex] }.filterValues { it > 0L }

    /** Per-app totals across the whole range; zero-duration apps are dropped. */
    fun appTotalsForRange(): Map<String, Long> =
        perAppDayMillis.mapValues { (_, days) -> days.sum() }.filterValues { it > 0L }
}

object UsageAggregator {

    /**
     * Buckets foreground intervals into [windows].
     *
     * Interval semantics (single-foreground-app model, matching how UsageStatsManager reports
     * activity transitions):
     * - `RESUMED(p)` implicitly closes any other app's open interval and opens one for `p`.
     * - `PAUSED(p)` closes `p`'s open interval; a PAUSED for an app that isn't currently
     *   foreground is ignored (its RESUMED predates the queried range).
     * - A duplicate RESUMED for the already-foreground app keeps the original start time.
     * - An interval still open after the last event is closed at [nowMillis] (the app is
     *   foreground right now), clamped to the end of the last window.
     * - Events need not arrive sorted; intervals are clamped to the overall range and split
     *   at day boundaries, so a session spanning midnight counts toward both days.
     */
    fun bucketIntoDays(
        events: List<ForegroundEvent>,
        windows: List<DayWindow>,
        nowMillis: Long,
    ): DayBuckets {
        require(windows.isNotEmpty()) { "windows must not be empty" }
        val rangeStart = windows.first().startMillis
        val rangeEnd = minOf(windows.last().endMillis, nowMillis)
        val perApp = HashMap<String, LongArray>()

        fun close(packageName: String, startMillis: Long, endMillis: Long) {
            val start = maxOf(startMillis, rangeStart)
            val end = minOf(endMillis, rangeEnd)
            if (end <= start) return
            val days = perApp.getOrPut(packageName) { LongArray(windows.size) }
            windows.forEachIndexed { i, window ->
                val overlap = minOf(end, window.endMillis) - maxOf(start, window.startMillis)
                if (overlap > 0L) days[i] += overlap
            }
        }

        var currentPackage: String? = null
        var currentSince = 0L
        for (event in events.sortedBy { it.timestampMillis }) {
            when (event.transition) {
                ForegroundTransition.RESUMED -> {
                    if (currentPackage == event.packageName) continue // duplicate; keep start
                    currentPackage?.let { close(it, currentSince, event.timestampMillis) }
                    currentPackage = event.packageName
                    currentSince = event.timestampMillis
                }
                ForegroundTransition.PAUSED -> {
                    if (currentPackage == event.packageName) {
                        close(event.packageName, currentSince, event.timestampMillis)
                        currentPackage = null
                    }
                }
            }
        }
        currentPackage?.let { close(it, currentSince, rangeEnd) }

        return DayBuckets(windows, perApp)
    }

    /**
     * Highest-usage apps first, capped at [limit]; zero-duration entries dropped. Ties break
     * alphabetically so the order is deterministic.
     */
    fun topApps(totals: Map<String, Long>, limit: Int): List<Pair<String, Long>> =
        totals.entries
            .filter { it.value > 0L }
            .sortedWith(
                compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key },
            )
            .take(limit)
            .map { it.key to it.value }
}
