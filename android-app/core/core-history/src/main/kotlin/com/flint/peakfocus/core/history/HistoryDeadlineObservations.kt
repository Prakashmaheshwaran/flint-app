package com.flint.peakfocus.core.history

import java.time.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transformLatest

/** Re-observe unchanged strict Ready rules when due. A newer state cancels the old wakeup. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<HistoryRulesSnapshot>.withDeadlineObservations(clock: Clock = Clock.systemUTC()): Flow<HistoryRulesSnapshot> =
    transformLatest { snapshot ->
        val deadlines = if (snapshot is HistoryRulesSnapshot.Ready) snapshot.rules.mapNotNull { it.plannedEnd }
            .filter { it > clock.millis() }.distinct().sorted() else emptyList()
        emit(snapshot)
        if (snapshot is HistoryRulesSnapshot.Ready) {
            for (deadline in deadlines) {
                // Bounded monotonic waits recheck wall-clock changes without doing history I/O.
                while (clock.millis() < deadline) delay(minOf(30_000, deadline - clock.millis()).coerceAtLeast(1))
                emit(snapshot)
            }
        }
    }
