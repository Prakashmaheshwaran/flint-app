package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.core.datastore.ProtectionClock
import com.flint.peakfocus.core.datastore.ProtectionSnapshot
import com.flint.peakfocus.core.datastore.ProtectionSnapshotReader
import com.flint.peakfocus.core.model.AccessClockStamp
import com.flint.peakfocus.core.model.StorageFailure
import com.flint.peakfocus.core.model.StorageState
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Shares the strict atomic decode once per host scope. Ticks project the cached decoded
 * snapshot; they never reopen DataStore or run a migration/reconciliation transaction.
 * Both projection and injected observations run off the collector's main/UI context.
 */
class ObservedTargetProtectionReader(
    snapshots: ProtectionSnapshotReader,
    scope: CoroutineScope,
    private val selfPackage: String,
    private val environment: suspend (TargetRef, StorageState<ProtectionSnapshot>, AccessClockStamp) -> TargetReadEnvironment,
    ticks: Flow<AccessClockStamp> = flow { while (currentCoroutineContext().isActive) { emit(ProtectionClock.now()); delay(1_000) } },
    private val legacyZone: () -> ZoneId = ZoneId::systemDefault,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
) : TargetProtectionReader {
    private val decoded = snapshots.state.catch { emit(StorageState.Unavailable(StorageFailure.IO)) }
        .flowOn(worker).stateIn(scope, SharingStarted.WhileSubscribed(0, 0), StorageState.Loading)
    private val clock = ticks.flowOn(worker).shareIn(scope, SharingStarted.WhileSubscribed(0, 0), replay = 1)

    override fun observe(target: TargetRef): Flow<TargetProtection> = combine(decoded, clock) { state, now -> state to now }
        .map { (state, now) ->
            val observed = try { environment(target, state, now) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { TargetReadEnvironment(unavailableReason = "runtime-observation-unavailable") }
            TargetProtectionProjection.read(target, state, observed, now, selfPackage, legacyZone())
        }.distinctUntilChanged().conflate().flowOn(worker)
}
