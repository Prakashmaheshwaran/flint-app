package com.flint.peakfocus.core.datastore

import androidx.datastore.core.CorruptionException
import com.flint.peakfocus.core.model.StorageFailure
import com.flint.peakfocus.core.model.StorageState
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.transform

class StorageUnavailableException(val reason: StorageFailure) : IOException("Storage unavailable: $reason")

internal fun storageFailure(cause: Throwable): StorageFailure? = when (cause) {
    is StorageUnavailableException -> cause.reason
    is CorruptionException -> StorageFailure.CORRUPT
    is IOException -> StorageFailure.IO
    else -> null // Cancellation and programming errors must propagate.
}

/** Compatibility for injected stores; production adapters also decode each snapshot strictly. */
fun <T> Flow<T>.withReadiness(): Flow<StorageState<T>> =
    map<T, StorageState<T>> { StorageState.Ready(it) }
        .onStart { emit(StorageState.Loading) }
        .catch { cause -> emit(StorageState.Unavailable(storageFailure(cause) ?: throw cause)) }

internal fun <P, T> Flow<P>.storageStates(decode: (P) -> T): Flow<StorageState<T>> =
    map<P, StorageState<T>> { raw ->
        try { StorageState.Ready(decode(raw)) }
        catch (cause: StorageUnavailableException) { StorageState.Unavailable(cause.reason) }
    }.retryWhen { cause, _ ->
        if (storageFailure(cause) != StorageFailure.IO) return@retryWhen false
        emit(StorageState.Unavailable(StorageFailure.IO))
        delay(1_000) // Retry reads; never write defaults or replace a corrupt file.
        true
    }.catch { cause ->
        emit(StorageState.Unavailable(storageFailure(cause) ?: throw cause))
    }.onStart { emit(StorageState.Loading) }.distinctUntilChanged()

/** For bounded work (for example goAsync receivers): never wait on a value-only flow. */
suspend fun <T> Flow<StorageState<T>>.snapshotOrNull(timeoutMs: Long = 1_000): T? = withTimeoutOrNull(timeoutMs) {
    (firstOrNull { it !is StorageState.Loading } as? StorageState.Ready)?.value
}

/** Legacy consumers keep their last value; new consumers observe the explicit state. */
fun <T> Flow<StorageState<T>>.readyValues(throwOnUnavailable: Boolean = false): Flow<T> = transform {
    when (it) {
        is StorageState.Ready -> emit(it.value)
        is StorageState.Unavailable -> if (throwOnUnavailable) throw StorageUnavailableException(it.reason)
        StorageState.Loading -> Unit
    }
}
