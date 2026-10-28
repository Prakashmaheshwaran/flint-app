package com.flint.peakfocus.core.history

import java.time.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.transformLatest

/** Re-observe unchanged strict Ready rules when due. A newer state cancels the old wakeup. */
@OptIn(ExperimentalCoroutinesApi::class)
@JvmOverloads
internal fun Flow<HistoryRulesSnapshot>.withDeadlineObservations(
    clock: Clock = Clock.systemUTC(),
    hasTerminal: (String) -> Boolean = { false },
    retryEveryMs: Long = 30_000,
    hasStarted: (String) -> Boolean = { true },
): Flow<HistoryRulesSnapshot> =
    transformLatest { snapshot ->
        emit(snapshot)
        if (snapshot is HistoryRulesSnapshot.Ready) {
            while (true) {
                val pending = snapshot.rules.filter {
                    it.plannedEnd != null && !hasTerminal(it.id) &&
                        // A new future rule's initial start append can still be in flight.
                        (it.plannedEnd > clock.millis() || hasStarted(it.id))
                }
                val deadline = pending.minOfOrNull { it.plannedEnd!! } ?: break
                val remaining = deadline - clock.millis()
                // A due wake is not an acknowledgement: a rollback or failed write can
                // leave the session open. Retry until its terminal record actually exists.
                delay(if (remaining > 0) minOf(retryEveryMs, remaining) else retryEveryMs)
                if (pending.any { !hasTerminal(it.id) && it.plannedEnd!! <= clock.millis() }) emit(snapshot)
            }
        }
    }.buffer(0)
