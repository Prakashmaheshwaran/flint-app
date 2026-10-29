package com.flint.peakfocus.core.model

import java.util.concurrent.CopyOnWriteArrayList

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
 * Publication remains atomic. Reads apply the app's pure clock projection without storage I/O,
 * so a clock edit cannot make a cached wall deadline authoritative before the next publication.
 */
object ActiveRulesHolder {

    /** The legacy quick blocklist's synthetic rule (core-data `BlocklistStore`). */
    const val SOURCE_LEGACY = "legacy-blocklist"

    /** Full rules authored in the Blocklist tab, persisted via core-datastore. */
    const val SOURCE_DATASTORE = "datastore"

    /** Combined order: legacy first, then DataStore, then any future source in publish order. */
    private val knownOrder = listOf(SOURCE_LEGACY, SOURCE_DATASTORE)

    private val contributions = LinkedHashMap<String, List<BlockRule>>()

    private data class Snapshot(val rules: List<BlockRule>, val dataStoreState: StorageState<List<BlockRule>>)
    @Volatile private var snapshot = Snapshot(emptyList(), StorageState.Loading)

    @Volatile private var projection: (List<BlockRule>) -> List<BlockRule> = { it }

    /** Installed once by the app before publishing editor rules; core-model owns no clock. */
    fun configureProjection(project: (List<BlockRule>) -> List<BlockRule>) { projection = project }

    val dataStoreState: StorageState<List<BlockRule>> get() = when (val state = snapshot.dataStoreState) {
        is StorageState.Ready -> StorageState.Ready(projection(state.value))
        else -> state
    }
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Lightweight invalidation; observers read [rules] and schedule work on their own loop. */
    fun subscribe(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    /** The current combined rule list — what both detection paths hand the engine. */
    val rules: List<BlockRule>
        get() = projection(snapshot.rules)

    /**
     * Read another source without mistaking this source's slower replica for a new owner.
     * A consumer still combines independently observed sources; there can be a transient
     * publication window. This read is not a transaction or authority for a release.
     */
    fun rulesOutside(source: String): List<BlockRule> = synchronized(contributions) {
        knownOrder.filterNot { it == source }.flatMap { contributions[it].orEmpty() } +
            contributions.entries.filter { it.key != source && it.key !in knownOrder }.flatMap { it.value }
    }

    /**
     * Replace [source]'s contribution with [rules] (empty clears it) and refresh the combined
     * list atomically. Safe from any thread; readers never see a partially-merged list.
     */
    fun publish(source: String, rules: List<BlockRule>) {
        publishState(source, StorageState.Ready(rules))
    }

    /** Loading/errors retain the last good contribution; only a successful read may clear it. */
    fun publishState(source: String, state: StorageState<List<BlockRule>>) {
        val changed = synchronized(contributions) {
            val previous = snapshot
            val published = if (state is StorageState.Ready) StorageState.Ready(state.value.toList()) else state
            val dataStoreState = if (source == SOURCE_DATASTORE) published else snapshot.dataStoreState
            if (published !is StorageState.Ready) {
                snapshot = snapshot.copy(dataStoreState = dataStoreState)
                return@synchronized snapshot != previous
            }
            val rules = published.value
            if (rules.isEmpty()) contributions.remove(source) else contributions[source] = rules
            val known = knownOrder.flatMap { contributions[it].orEmpty() }
            val rest = contributions.entries.filter { it.key !in knownOrder }.flatMap { it.value }
            snapshot = Snapshot(known + rest, dataStoreState)
            snapshot != previous
        }
        // No observer runs under the publication lock (including observers that publish).
        if (changed) listeners.forEach { runCatching(it) }
    }
}
