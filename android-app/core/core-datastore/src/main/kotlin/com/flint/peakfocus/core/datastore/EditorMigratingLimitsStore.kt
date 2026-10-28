package com.flint.peakfocus.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.flint.peakfocus.core.model.AccessClockStamp
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/** First-load migration barrier without touching C3's DataStoreStores.kt hot file. */
internal class EditorMigratingLimitsStore(
    dataStore: DataStore<Preferences>,
    clock: () -> AccessClockStamp = ProtectionClock::now,
) : LimitsStore by DataStoreLimitsStore(dataStore) {
    private val initialized = flow {
        DataStoreEditorCommitmentStore(dataStore, clockAtCommit = clock).reconcile(clock())
        emitAll(dataStore.data)
    }
    override val timeLimitsState = initialized.storageStates { it.editorTimes() }
    override val openLimitsState = initialized.storageStates { it.editorOpens() }
    override val timeLimits = timeLimitsState.readyValues()
    override val openLimits = openLimitsState.readyValues()
}
