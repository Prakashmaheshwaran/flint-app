package com.flint.peakfocus.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.flint.peakfocus.core.model.*
import com.flint.peakfocus.core.model.AccessClockStamp
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers

/** First-load migration barrier without touching C3's DataStoreStores.kt hot file. */
internal class EditorMigratingLimitsStore(
    private val dataStore: DataStore<Preferences>,
    private val clock: () -> AccessClockStamp = ProtectionClock::now,
) : LimitsStore by DataStoreLimitsStore(dataStore) {
    private val editor = DataStoreEditorCommitmentStore(dataStore, clockAtCommit = clock)
    private val initialized = flow {
        if ((StrictPrefsCodec.long(dataStore.data.first(), EDITOR_VERSION_KEY) ?: 0L) < EDITOR_DOMAIN_VERSION) {
            editor.reconcile(clock())
        }
        emitAll(dataStore.data)
    }
    override val timeLimitsState = initialized.storageStates {
        readEditorState(it); readHarderWait(it); validateTimeLimitUsageForRead(it); it.editorTimes()
    }.flowOn(Dispatchers.IO)
    override val openLimitsState = initialized.storageStates { readEditorState(it); readHarderWait(it); it.editorOpens() }
    override val timeLimits = timeLimitsState.readyValues()
    override val openLimits = openLimitsState.readyValues()

    override suspend fun setTimeLimit(limit: TimeLimit) = mutateTime { current -> current.filterNot { it.packageName == limit.packageName } + limit }
    override suspend fun clearTimeLimit(packageName: String) = mutateTime { current -> current.filterNot { it.packageName == packageName } }
    private suspend fun mutateTime(transform: (List<TimeLimit>) -> List<TimeLimit>) {
        dataStore.edit { prefs ->
            val now = clock()
            editor.prepare(prefs, now)
            val previous = prefs.editorTimes()
            val next = normalizeTimeLimitMutation(prefs, transform(previous), previous, now, java.util.TimeZone.getDefault().id)
            val raw = PrefsCodec.encodeTimeLimits(next); StrictPrefsCodec.timeLimits(raw)
            prefs[PrefsKeys.TIME_LIMITS] = raw
        }
    }
    override suspend fun setLimits(packageName: String, time: TimeLimit?, open: OpenLimit?) {
        require(time == null || time.packageName == packageName)
        require(open == null || open.packageName == packageName)
        dataStore.edit { prefs ->
            val now = clock()
            editor.prepare(prefs, now)
            val previous = prefs.editorTimes()
            val times = normalizeTimeLimitMutation(prefs, previous.filterNot { it.packageName == packageName } + listOfNotNull(time), previous, now, java.util.TimeZone.getDefault().id)
            val timeRaw = PrefsCodec.encodeTimeLimits(times)
            val openRaw = PrefsCodec.encodeOpenLimits(prefs.editorOpens().filterNot { it.packageName == packageName } + listOfNotNull(open))
            StrictPrefsCodec.timeLimits(timeRaw); StrictPrefsCodec.openLimits(openRaw)
            prefs[PrefsKeys.TIME_LIMITS] = timeRaw
            prefs[PrefsKeys.OPEN_LIMITS] = openRaw
        }
    }
}
