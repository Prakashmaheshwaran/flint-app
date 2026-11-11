package com.flint.peakfocus.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.flint.peakfocus.core.model.StorageFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach

/**
 * Same backing store/transactions; production cannot use the markerless fixture seam.
 * With [retry], a markerless emission schedules and every write first attempts the
 * in-process capture retry (its own edit, before the caller's), never inside a transform.
 */
internal class TimeBootstrapRequiredDataStore(
    private val delegate: DataStore<Preferences>,
    private val retry: TimeLimitBootstrapRetry? = null,
) : DataStore<Preferences> {
    override val data: Flow<Preferences> = if (retry == null) delegate.data else delegate.data.onEach { retry.observe(it) }

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        retry?.attempt()
        return delegate.updateData(transform)
    }
}

internal fun DataStore<Preferences>.hasRequiredTimeBootstrap(prefs: Preferences): Boolean =
    this !is TimeBootstrapRequiredDataStore || readTimeLimitBootstrapCapture(prefs) != null

internal fun DataStore<Preferences>.requireTimeBootstrap(prefs: Preferences) {
    if (!hasRequiredTimeBootstrap(prefs)) throw StorageUnavailableException(StorageFailure.IO)
}
