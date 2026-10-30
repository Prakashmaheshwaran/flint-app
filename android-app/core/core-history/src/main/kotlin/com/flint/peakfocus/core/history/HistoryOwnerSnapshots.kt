package com.flint.peakfocus.core.history

import com.flint.peakfocus.core.model.ActiveRulesHolder
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Observe accepted publications promptly and advance the anchored projection between them. */
internal fun historyOwnerSnapshots(
    read: () -> HistoryRulesSnapshot = { historyRulesSnapshot(ActiveRulesHolder.dataStoreState) },
    subscribe: (() -> Unit) -> (() -> Unit) = ActiveRulesHolder::subscribe,
): Flow<HistoryRulesSnapshot> = callbackFlow {
    // Capture the public read-time projection at invalidation, without disk work on the
    // publisher. A short Timer can start and end before the next periodic clock sample.
    val observe: () -> Unit = { trySend(read()); Unit }
    val unsubscribe = subscribe(observe)
    observe()
    val ticker = launch {
        while (true) {
            delay(1_000)
            observe()
        }
    }
    awaitClose { ticker.cancel(); unsubscribe() }
}.distinctUntilChanged()
