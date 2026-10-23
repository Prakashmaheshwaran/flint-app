package com.flint.peakfocus.core.datastore

import com.flint.peakfocus.core.model.AccessLedgerV2
import com.flint.peakfocus.core.model.AccessMutation
import kotlinx.coroutines.flow.Flow

enum class AccessStoreFailure { IO, CORRUPT, UNSUPPORTED_VERSION }

sealed interface AccessStoreState {
    data object Loading : AccessStoreState
    data class Ready(val ledger: AccessLedgerV2) : AccessStoreState
    data class Unavailable(val reason: AccessStoreFailure) : AccessStoreState
}

sealed interface AccessCommit<out R> {
    data class Committed<R>(val ledger: AccessLedgerV2, val result: R) : AccessCommit<R>
    data class Unavailable(val reason: AccessStoreFailure) : AccessCommit<Nothing>
}

/**
 * Additive v2 state. A missing key in a successful read is empty; unreadable storage is not.
 * Transforms must be pure: no random/clock reads, I/O, UI, or mutable captured state.
 * Committed is returned only after the whole ledger is durably written. Cancellation may
 * occur after a commit; callers recover by sequence, never by issuing a replacement grant.
 */
interface AccessStateStore {
    val state: Flow<AccessStoreState>

    suspend fun <R> transact(transform: (AccessLedgerV2) -> AccessMutation<R>): AccessCommit<R>
}
