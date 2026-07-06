package com.flint.peakfocus.core.model

/**
 * Temporary in-memory bridge for the currently-active rule set, shared by both detection paths
 * (AccessibilityService and UsageStats foreground service) so neither couples to the other.
 *
 * Rules arrive from more than one writer — the legacy quick-blocklist store (core-data) and the
 * DataStore rules bridge (app) — so each writer owns a named lane via [publish], and [rules] is
 * the merged snapshot both paths read. A writer can only ever replace its *own* lane: the legacy
 * store reloading on service connect / boot warm-up can no longer wipe DataStore-authored rules
 * out of enforcement (the old single-assignment "clobber window").
 *
 * This will be replaced by a repository-backed (DataStore/Room) source with a schedule resolver —
 * for now it keeps the engine fed.
 */
object ActiveRulesHolder {

    /** Lane owned by core-data `BlocklistStore` — its one synthetic quick-blocklist rule. */
    const val SOURCE_LEGACY_BLOCKLIST = "legacy-blocklist"

    /** Lane owned by the app-side bridge observing the DataStore-authored rules. */
    const val SOURCE_DATASTORE = "datastore"

    // Fixed lane order so the engine sees the same list regardless of which writer published
    // last (verdict precedence scans rules in order). Unknown lanes append alphabetically.
    private val laneOrder = listOf(SOURCE_LEGACY_BLOCKLIST, SOURCE_DATASTORE)

    private val lanes = HashMap<String, List<BlockRule>>()

    @Volatile
    var rules: List<BlockRule> = emptyList()
        private set

    /** Replace [source]'s lane with [laneRules] and refresh the merged [rules] snapshot. */
    fun publish(source: String, laneRules: List<BlockRule>) {
        synchronized(lanes) {
            lanes[source] = laneRules
            val known = laneOrder.flatMap { lanes[it].orEmpty() }
            val extra = (lanes.keys - laneOrder).sorted().flatMap { lanes[it].orEmpty() }
            rules = known + extra
        }
    }

    /** Drop every lane. The object is process-wide state; tests use this to isolate cases. */
    fun reset() {
        synchronized(lanes) {
            lanes.clear()
            rules = emptyList()
        }
    }
}
