package com.flint.peakfocus.core.history

import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.StorageState
import java.util.Locale

/** A failed strict decode is never an authoritative empty rule set. */
fun historyRulesSnapshot(state: StorageState<List<BlockRule>>): HistoryRulesSnapshot = when (state) {
    StorageState.Loading -> HistoryRulesSnapshot.Loading
    is StorageState.Unavailable -> HistoryRulesSnapshot.Unavailable(state.reason.name)
    is StorageState.Ready -> {
        val sessions = state.value.filter {
            it.id.startsWith("session-") && it.enabled && it.schedule == null
        }
        val inactive = sessions.filter { it.runtime?.evaluation?.active == false }.mapTo(mutableSetOf()) { it.id }
        HistoryRulesSnapshot.Ready(sessions.filterNot { it.id in inactive }.map {
            // The display estimate combines a fresh wall/elapsed sample and can
            // jitter by a millisecond. Persist the same committed logical endpoint
            // the owner uses for its terminal, rather than rescheduling every tick.
            HistoryRule(it.id, it.name, it.breakLevel.name.lowercase(Locale.ROOT),
                it.runtime?.wallEndEpochMs ?: it.expiresAtEpochMs)
        }, inactive)
    }
}
