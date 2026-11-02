package com.flint.peakfocus.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.flint.peakfocus.core.model.StorageFailure

/** Same backing store/transactions; production cannot use the markerless fixture seam. */
internal class TimeBootstrapRequiredDataStore(delegate: DataStore<Preferences>) : DataStore<Preferences> by delegate

internal fun DataStore<Preferences>.hasRequiredTimeBootstrap(prefs: Preferences): Boolean =
    this !is TimeBootstrapRequiredDataStore || readTimeLimitBootstrapCapture(prefs) != null

internal fun DataStore<Preferences>.requireTimeBootstrap(prefs: Preferences) {
    if (!hasRequiredTimeBootstrap(prefs)) throw StorageUnavailableException(StorageFailure.IO)
}
