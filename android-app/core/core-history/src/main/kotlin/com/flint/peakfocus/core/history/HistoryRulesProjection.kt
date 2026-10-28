package com.flint.peakfocus.core.history

import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.StorageState
import java.util.Locale

/** A failed strict decode is never an authoritative empty rule set. */
internal fun historyRulesSnapshot(state: StorageState<List<BlockRule>>): HistoryRulesSnapshot = when (state) {
    StorageState.Loading -> HistoryRulesSnapshot.Loading
    is StorageState.Unavailable -> HistoryRulesSnapshot.Unavailable(state.reason.name)
    is StorageState.Ready -> HistoryRulesSnapshot.Ready(state.value.filter {
        it.id.startsWith("session-") && it.enabled && it.schedule == null
    }.map { HistoryRule(it.id, it.name, it.breakLevel.name.lowercase(Locale.ROOT), it.expiresAtEpochMs) })
}
