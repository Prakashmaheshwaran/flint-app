package com.flint.peakfocus.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.core.CorruptionException
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.flint.peakfocus.core.model.AccessLedgerV2
import com.flint.peakfocus.core.model.AccessMutation
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal val TEMPORARY_ACCESS_V2 = stringPreferencesKey("temporary_access_v2")

class DataStoreAccessStateStore(private val dataStore: DataStore<Preferences>) : AccessStateStore {
    override val state: Flow<AccessStoreState> = dataStore.data
        .map { readAccessState(accessPayload(it)) }
        .onStart { emit(AccessStoreState.Loading) }
        .catch { cause ->
            val failure = accessFailure(cause) ?: throw cause
            emit(AccessStoreState.Unavailable(failure))
        }

    override suspend fun <R> transact(transform: (AccessLedgerV2) -> AccessMutation<R>): AccessCommit<R> {
        var committed: AccessCommit.Committed<R>? = null
        return try {
            dataStore.edit { prefs ->
                val current = AccessLedgerCodec.decode(accessPayload(prefs))
                val mutation = transform(current)
                checkAccessTransition(current, mutation.next)
                val encoded = AccessLedgerCodec.encode(mutation.next)
                // A no-op must not create a key or rewrite a successfully read payload.
                if (mutation.next != current) prefs[TEMPORARY_ACCESS_V2] = encoded
                committed = AccessCommit.Committed(AccessLedgerCodec.decode(encoded), mutation.result)
            }
            checkNotNull(committed)
        } catch (e: AccessCodecException) {
            AccessCommit.Unavailable(e.failure)
        } catch (_: CorruptionException) {
            AccessCommit.Unavailable(AccessStoreFailure.CORRUPT)
        } catch (_: IOException) {
            AccessCommit.Unavailable(AccessStoreFailure.IO)
        }
    }
}

private fun accessPayload(prefs: Preferences): String? {
    val value = prefs.asMap()[TEMPORARY_ACCESS_V2] ?: return null
    return value as? String ?: throw AccessCodecException(AccessStoreFailure.CORRUPT)
}

private fun accessFailure(cause: Throwable): AccessStoreFailure? = when (cause) {
    is AccessCodecException -> cause.failure
    is CorruptionException -> AccessStoreFailure.CORRUPT
    is IOException -> AccessStoreFailure.IO
    else -> null
}

/** Stores encoded data internally, so caller-owned mutable maps cannot change durable state. */
class InMemoryAccessStateStore(initial: AccessLedgerV2 = AccessLedgerV2()) : AccessStateStore {
    private val mutex = Mutex()
    private val encoded = MutableStateFlow(AccessLedgerCodec.encode(initial))

    override val state: Flow<AccessStoreState> = encoded
        .map(::readAccessState)
        .onStart { emit(AccessStoreState.Loading) }

    override suspend fun <R> transact(transform: (AccessLedgerV2) -> AccessMutation<R>): AccessCommit<R> =
        mutex.withLock {
            val current = AccessLedgerCodec.decode(encoded.value)
            val mutation = transform(current)
            checkAccessTransition(current, mutation.next)
            val next = AccessLedgerCodec.encode(mutation.next)
            encoded.value = next
            AccessCommit.Committed(AccessLedgerCodec.decode(next), mutation.result)
        }
}

private fun readAccessState(encoded: String?): AccessStoreState = try {
    AccessStoreState.Ready(AccessLedgerCodec.decode(encoded))
} catch (e: AccessCodecException) {
    AccessStoreState.Unavailable(e.failure)
}

private fun checkAccessTransition(current: AccessLedgerV2, next: AccessLedgerV2) {
    require(next.nextSequence >= current.nextSequence) { "Access sequences cannot be reused" }
    if (next != current) {
        require(current.revision < Long.MAX_VALUE && next.revision == current.revision + 1) {
            "Changed access state must advance its revision exactly once"
        }
    }
}
