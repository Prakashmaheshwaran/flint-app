package com.flint.peakfocus.core.datastore

import com.flint.peakfocus.core.model.ProtectionLifecycleEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Both detector coordinators drain the same transaction domain in this process.
private val lifecycleDeliveryLock = Mutex()

/**
 * At-least-once delivery. The consumer must durably deduplicate event.id before returning true.
 * Nothing is claimed before delivery: a crash after the consumer commit replays the same ID.
 * False/failure retains the event; cancellation propagates. One bounded batch never holds up
 * a grant or the enforcement loop. This matches EditorCommitmentStore terminal delivery.
 */
suspend fun ProtectionTransactionStore.deliverLifecycleEvents(deliver: suspend (ProtectionLifecycleEvent) -> Boolean) {
    lifecycleDeliveryLock.withLock {
        val snapshot = state.snapshotOrNull() ?: return
        val pending = snapshot.focus.protection?.outbox?.values?.sortedWith(
            compareBy<ProtectionLifecycleEvent> { it.at.wallEpochMs }.thenBy { it.id }).orEmpty()
        for (event in pending) {
            val accepted = try { deliver(event) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { false }
            if (!accepted) return
            val acknowledged = transact { current ->
                val ledger = current.focus.protection
                // Never erase a different revision/value that happens to reuse the ID.
                val next = if (ledger?.outbox?.get(event.id) == event)
                    current.focus.copy(protection = ledger.copy(outbox = ledger.outbox - event.id)) else current.focus
                ProtectionMutation(next, Unit)
            }
            if (acknowledged !is ProtectionCommit.Committed) return
        }
    }
}
