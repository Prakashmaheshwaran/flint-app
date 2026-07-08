package com.flint.peakfocus.core.model

/**
 * In-memory bridge for the currently-active rule set, shared by both detection paths
 * (AccessibilityService and UsageStats foreground service) so neither couples to the other.
 *
 * Rules arrive from more than one writer — the legacy quick blocklist projects itself on every
 * mutation and on service connect, while the app-side bridge streams the DataStore-authored
 * rules — so the holder is **source-keyed**: each writer [publish]es only its own contribution
 * and can never clobber another's (the bug this replaced: legacy writers used to overwrite the
 * whole list, silently dropping DataStore rules from enforcement until the next republish).
 *
 * [rules] stays a cheap volatile read — it is on the hot path of every accessibility event.
 */
object ActiveRulesHolder {

    /** The legacy quick blocklist's synthetic rule (core-data `BlocklistStore`). */
    const val SOURCE_LEGACY = "legacy-blocklist"

    /** Full rules authored in the Blocklist tab, persisted via core-datastore. */
    const val SOURCE_DATASTORE = "datastore"

    /** Combined order: legacy first, then DataStore, then any future source in publish order. */
    private val knownOrder = listOf(SOURCE_LEGACY, SOURCE_DATASTORE)

    private val contributions = LinkedHashMap<String, List<BlockRule>>()

    @Volatile
    private var combined: List<BlockRule> = emptyList()

    /** The current combined rule list — what both detection paths hand the engine. */
    val rules: List<BlockRule>
        get() = combined

    /**
     * Replace [source]'s contribution with [rules] (empty clears it) and refresh the combined
     * list atomically. Safe from any thread; readers never see a partially-merged list.
     */
    fun publish(source: String, rules: List<BlockRule>) {
        synchronized(contributions) {
            if (rules.isEmpty()) contributions.remove(source) else contributions[source] = rules
            val known = knownOrder.flatMap { contributions[it].orEmpty() }
            val rest = contributions.entries.filter { it.key !in knownOrder }.flatMap { it.value }
            combined = known + rest
        }
    }
}
