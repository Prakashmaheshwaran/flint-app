package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.blocking.engine.ProtectionContract
import com.flint.peakfocus.core.datastore.*
import com.flint.peakfocus.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One durable authority shared by the two detector adapters; no UI callback writes accounting. */
internal class GlobalRecoveryController(
    private val store: ProtectionTransactionStore,
    scope: CoroutineScope,
    private val clock: () -> AccessClockStamp,
    private val foreignRules: () -> List<BlockRule>,
    private val enforcementChanged: () -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val timeoutMs: Long = 5_000L,
) {
    private val mutableState = MutableStateFlow<StorageState<ProtectionSnapshot>>(StorageState.Loading)
    val state: StateFlow<StorageState<ProtectionSnapshot>> = mutableState
    private val publicationLock = Any()
    @Volatile var lastGood: ProtectionSnapshot? = null
        private set
    private val drainSignals = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch(dispatcher) { store.state.collect(::publish) }
        scope.launch(dispatcher) {
            // Migrate/reconcile once before a pass is advertised. Thereafter persist elapsed
            // checkpoints at bounded intervals, and exactly at an active recovery boundary.
            while (isActive) {
                reconcile()
                val now = clock()
                val remaining = GlobalRecoveryPolicy.remaining(lastGood?.focus?.protection?.recovery, now)
                delay(if (remaining > 0) minOf(remaining, CHECKPOINT_MS) else CHECKPOINT_MS)
            }
        }
        scope.launch(dispatcher) {
            val unsubscribe = BlockEventHooks.subscribeDurableConsumer { drainSignals.trySend(Unit) }
            try {
                drainSignals.trySend(Unit)
                for (ignored in drainSignals) {
                    val consumer = BlockEventHooks.currentDurableConsumer() ?: continue
                    // A stuck consumer may delay history, never enforcement or another grant.
                    withTimeoutOrNull(DELIVERY_TIMEOUT_MS) { store.deliverLifecycleEvents(consumer::deliver) }
                }
            } finally { unsubscribe() }
        }
    }

    private fun publish(next: StorageState<ProtectionSnapshot>) {
        var changed = false
        synchronized(publicationLock) {
            if (next is StorageState.Ready) {
                if (next.value.focus.revision < (lastGood?.focus?.revision ?: -1L)) return
                if (next.value.focus.protection == null) {
                    mutableState.value = StorageState.Loading
                    return
                }
                changed = next.value.focus.protection?.recovery != lastGood?.focus?.protection?.recovery ||
                    lastGood == null
                lastGood = next.value
                BlockExemptions.restoreRecovery(next.value.focus.protection?.recovery, clock, next.value.focus.revision)
                if (next.value.focus.protection?.outbox?.isNotEmpty() == true) drainSignals.trySend(Unit)
            }
            mutableState.value = next
        }
        if (changed) runCatching(enforcementChanged)
    }

    suspend fun reconcile(): Boolean = when (val commit = commit { snapshot ->
        ProtectionMutation(GlobalRecoveryPolicy.checkpoint(snapshot).focus, Unit)
    }) {
        is ProtectionCommit.Committed -> true
        else -> false
    }

    /** Explicit preview/retry refreshes storage, occurrences and cooldown in the same edit. */
    suspend fun preview(): RecoveryPreview? {
        val committed = commit { original ->
            val snapshot = GlobalRecoveryPolicy.checkpoint(original)
            ProtectionMutation(snapshot.focus, GlobalRecoveryPolicy.preview(snapshot, foreignRules().toList()))
        }
        return (committed as? ProtectionCommit.Committed)?.result
    }

    suspend fun confirm(preview: RecoveryPreview): RecoveryResult {
        val frozen = preview.copy(affected = preview.affected.toList())
        return when (val accepted = commit { GlobalRecoveryPolicy.consume(it, frozen, foreignRules().toList()) }) {
            is ProtectionCommit.Committed -> accepted.result
            is ProtectionCommit.Unavailable -> RecoveryResult.Unavailable(accepted.reason)
            null -> RecoveryResult.Unconfirmed
        }
    }

    suspend fun resumeNow(): Boolean = when (val accepted = commit(GlobalRecoveryPolicy::resume)) {
        is ProtectionCommit.Committed -> accepted.result
        else -> false
    }

    private suspend fun <R> commit(reducer: (ProtectionSnapshot) -> ProtectionMutation<R>): ProtectionCommit<R>? = withContext(dispatcher) {
        val accepted = withTimeoutOrNull(timeoutMs) { store.transact(reducer) }
        if (accepted is ProtectionCommit.Committed) publish(StorageState.Ready(accepted.snapshot))
        else if (accepted is ProtectionCommit.Unavailable) mutableState.value = StorageState.Unavailable(accepted.reason)
        accepted
    }

    companion object {
        internal const val CHECKPOINT_MS = 60_000L
        private const val DELIVERY_TIMEOUT_MS = 10_000L
    }
}
