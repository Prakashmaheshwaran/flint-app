package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.blocking.engine.ProtectionContract
import com.flint.peakfocus.core.datastore.*
import com.flint.peakfocus.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

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
    private val boundarySignals = Channel<Unit>(Channel.CONFLATED)
    private val ordinarySignals = Channel<Unit>(Channel.CONFLATED)
    private val ordinaryInputs = ConcurrentHashMap<String, OrdinaryProtectionInput>()

    init {
        scope.launch(dispatcher) { store.state.collect(::publish) }
        scope.launch(dispatcher) {
            // Migrate/reconcile once before a pass is advertised. Thereafter persist elapsed
            // checkpoints at bounded intervals, and exactly at an active recovery boundary.
            while (isActive) {
                reconcile()
                val now = clock()
                val remaining = GlobalRecoveryPolicy.remaining(lastGood?.focus?.protection?.recovery, now)
                val ordinary = lastGood?.focus?.protection?.ordinaryGrants?.values.orEmpty().mapNotNull { it.lifetime.remainingMs(now)?.takeIf { ms -> ms > 0 } }
                val quota = lastGood?.openCredits?.let { com.flint.peakfocus.blocking.engine.OpenCreditPolicy().remainingToResetMs(it.reset, now) }
                val wait = (ordinary + listOfNotNull(remaining.takeIf { it > 0 }, quota?.takeIf { it > 0 }) + CHECKPOINT_MS).minOrNull() ?: CHECKPOINT_MS
                withTimeoutOrNull(wait) { boundarySignals.receive() }
                // Deadlines still stop release when storage becomes unavailable at expiry.
                runCatching(enforcementChanged)
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
        scope.launch(dispatcher) {
            for (ignored in ordinarySignals) {
                val inputs = ordinaryInputs.values.toList()
                commit { original ->
                    var snapshot = GlobalRecoveryPolicy.checkpoint(original)
                    for (input in inputs) snapshot = snapshot.copy(focus = OrdinaryProtectionPolicy.retireInvalid(snapshot, input, foreignRules()))
                    ProtectionMutation(snapshot.focus, Unit)
                }
            }
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
                    next.value.focus.protection?.ordinaryGrants != lastGood?.focus?.protection?.ordinaryGrants ||
                    lastGood == null
                if (lastGood?.rules != next.value.rules || lastGood?.times != next.value.times || lastGood?.opens != next.value.opens)
                    ordinarySignals.trySend(Unit)
                lastGood = next.value
                BlockExemptions.restoreRecovery(next.value.focus.protection?.recovery, clock, next.value.focus.revision)
                if (next.value.focus.protection?.outbox?.isNotEmpty() == true) drainSignals.trySend(Unit)
            }
            mutableState.value = next
        }
        if (changed) { boundarySignals.trySend(Unit); runCatching(enforcementChanged) }
    }

    suspend fun reconcile(): Boolean = when (val commit = commit { snapshot ->
        ProtectionMutation(GlobalRecoveryPolicy.checkpoint(snapshot).focus, Unit)
    }) {
        is ProtectionCommit.Committed -> true
        else -> false
    }

    /** Explicit preview/retry refreshes storage, occurrences and cooldown in the same edit. */
    suspend fun preview(): RecoveryPreview? {
        val operationId = "p1-${UUID.randomUUID()}"
        val committed = commit { original ->
            val snapshot = GlobalRecoveryPolicy.checkpoint(original)
            ProtectionMutation(snapshot.focus, GlobalRecoveryPolicy.preview(snapshot, foreignRules().toList(), operationId))
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

    suspend fun previewEnd(selected: Set<String>, delete: Boolean): RecoveryEndPreview? {
        val frozen = selected.toSet()
        val accepted = commit { original ->
            val snapshot = GlobalRecoveryPolicy.checkpoint(original)
            ProtectionMutation(snapshot.focus, GlobalRecoveryPolicy.previewEnd(snapshot, frozen, delete))
        }
        return (accepted as? ProtectionCommit.Committed)?.result
    }

    suspend fun endAndResume(preview: RecoveryEndPreview): Boolean {
        val frozen = preview.copy(selectedCauseKeys = preview.selectedCauseKeys.toSet(), displayNames = preview.displayNames.toList())
        return (commit { GlobalRecoveryPolicy.endAndResume(it, frozen) } as? ProtectionCommit.Committed)?.result == true
    }

    suspend fun requestOrdinary(input: OrdinaryProtectionInput, id: GrantRequestId): GrantResult {
        val stamp = id.issuedAtClock ?: return GrantResult.Unavailable()
        val snapshot = (state.value as? StorageState.Ready)?.value ?: return GrantResult.Unavailable()
        val frozen = OrdinaryProtectionPolicy.frozenScopes(snapshot, input, foreignRules().toList(), clock())?.toSet()
            ?: return GrantResult.Unavailable()
        return when (val accepted = commit { OrdinaryProtectionPolicy.request(it, input, "p1-${id.nonce}", stamp, foreignRules().toList(), frozen) }) {
            is ProtectionCommit.Committed -> accepted.result
            is ProtectionCommit.Unavailable -> GrantResult.Unavailable(accepted.reason)
            null -> GrantResult.Unconfirmed
        }
    }

    fun ordinaryCovers(input: OrdinaryProtectionInput, now: AccessClockStamp): Boolean = lastGood?.let {
        OrdinaryProtectionPolicy.covers(it, input, foreignRules(), now)
    } == true

    fun observeOrdinary(input: OrdinaryProtectionInput) {
        if (lastGood?.focus?.protection?.ordinaryGrants?.values?.any { it.target == AccessTarget.App(input.packageName) } != true) {
            ordinaryInputs.remove(input.packageName); return
        }
        if (ordinaryInputs.put(input.packageName, input) != input) ordinarySignals.trySend(Unit)
    }

    suspend fun resumeOrdinary(packageName: String? = null): Boolean =
        (commit { OrdinaryProtectionPolicy.resume(it, packageName) } as? ProtectionCommit.Committed)?.result == true

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
