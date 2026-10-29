package com.flint.peakfocus.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.flint.peakfocus.blocking.engine.OpenCreditLedger
import com.flint.peakfocus.blocking.engine.RuleClockProjection
import com.flint.peakfocus.core.model.AccessClockStamp
import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.StorageState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Display-only access: no migration, reconciliation, checkpoint, or mutation entry point. */
interface ProtectionSnapshotReader {
    val state: Flow<StorageState<ProtectionSnapshot>>

    /** Null is validated legacy/uninitialized credit state, never an invented zero-used pool. */
    val openCredits: Flow<StorageState<OpenCreditLedger?>>
}

/**
 * Reuses the transaction store's exact atomic raw-preferences decoder. Its state flow never
 * opens an edit; unlike the migrating limits wrapper, collecting this cannot initialize data.
 * A host should share [state] once off main, then project individual targets from that stream.
 */
class DataStoreProtectionSnapshotReader internal constructor(
    override val state: Flow<StorageState<ProtectionSnapshot>>,
) : ProtectionSnapshotReader {
    constructor(dataStore: DataStore<Preferences>, clock: () -> AccessClockStamp = ProtectionClock::now) :
        this(DataStoreProtectionTransactionStore(dataStore, clock).state)

    override val openCredits: Flow<StorageState<OpenCreditLedger?>> = state.map { snapshot ->
        when (snapshot) {
            StorageState.Loading -> StorageState.Loading
            is StorageState.Unavailable -> snapshot
            is StorageState.Ready -> StorageState.Ready(snapshot.value.openCredits)
        }
    }.distinctUntilChanged()
}

/** A read-only facade over the same production preference file and strict decoder. */
fun FlintPreferences.protectionSnapshots(context: Context): ProtectionSnapshotReader =
    DataStoreProtectionSnapshotReader(protectionTransactions(context).state)

/**
 * Pure R-18 view of an already atomic rule/sidecar snapshot. Uses the same adapter as the
 * editor publication, including queued boundaries; never reconciles or initializes storage.
 * Missing occurrence authority fails explicitly rather than dropping a restrictive record.
 */
fun ProtectionSnapshot.projectedRulesForRead(now: AccessClockStamp): List<BlockRule> =
    RuleClockProjection.project(editorRulesWithRuntime(rules, editor, now), now)
