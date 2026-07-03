package com.flint.peakfocus.core.model

/**
 * Per-app foreground usage for a single day — the unit the Stats screen renders and the
 * UsageStatsManager layer produces.
 *
 * Pure data so the producer (core-data `UsageQuery` / blocking-usagestats) and the consumer
 * (feature-stats, owned by A-STATS) share one shape without depending on each other. A-STATS can't
 * add this itself — its scope is `feature/feature-stats` only — so the scaffold provides it here.
 */
data class UsageStat(
    val packageName: String,
    val label: String? = null,
    val foregroundMillis: Long,
) {
    /** Foreground time today in whole minutes (convenience for display/limit comparisons). */
    val foregroundMinutes: Int get() = (foregroundMillis / 60_000L).toInt()
}
