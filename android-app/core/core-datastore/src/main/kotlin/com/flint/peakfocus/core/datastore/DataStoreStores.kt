package com.flint.peakfocus.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.BreakLevel
import com.flint.peakfocus.core.model.BreakSessionState
import com.flint.peakfocus.core.model.EmergencyPassState
import com.flint.peakfocus.core.model.OpenCountState
import com.flint.peakfocus.core.model.OpenLimit
import com.flint.peakfocus.core.model.TimeLimit
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * Preferences DataStore implementations of the three store interfaces; every value goes
 * through [PrefsCodec]. Implementations take the [DataStore] in their constructor so JVM
 * tests can inject a fake; production code obtains them via [FlintPreferences] (its own
 * file on purpose — keeping the process-wide DataStore delegate out of this file's static
 * initializer so constructing a store never drags production wiring into tests).
 */

internal object PrefsKeys {
    val BLOCK_RULES = stringPreferencesKey("block_rules_v1")
    val TIME_LIMITS = stringPreferencesKey("time_limits_v1")
    val OPEN_LIMITS = stringPreferencesKey("open_limits_v1")
    val DEFAULT_BREAK_LEVEL = stringPreferencesKey("default_break_level_v1")
    val EMERGENCY_PASS_WEEK_START = longPreferencesKey("emergency_pass_week_start_v1")
    val EMERGENCY_PASS_USED_AT = longPreferencesKey("emergency_pass_used_at_v1")
    val BREAK_SESSION = stringPreferencesKey("break_session_v1")
    val OPEN_COUNTS_DAY = longPreferencesKey("open_counts_day_v1")
    val OPEN_COUNTS = stringPreferencesKey("open_counts_v1")
}

/** Standard DataStore hygiene: a corrupted/unreadable file degrades to defaults, not a crash. */
private fun DataStore<Preferences>.safeData(): Flow<Preferences> =
    data.catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }

class DataStoreBlockRulesStore(
    private val dataStore: DataStore<Preferences>,
) : BlockRulesStore {

    override val rules: Flow<List<BlockRule>> = dataStore.safeData().map { prefs ->
        PrefsCodec.decodeRules(prefs[PrefsKeys.BLOCK_RULES].orEmpty())
    }

    override suspend fun upsert(rule: BlockRule) =
        mutate { current -> current.filterNot { it.id == rule.id } + rule }

    override suspend fun delete(ruleId: String) =
        mutate { current -> current.filterNot { it.id == ruleId } }

    override suspend fun setEnabled(ruleId: String, enabled: Boolean) =
        mutate { current -> current.map { if (it.id == ruleId) it.copy(enabled = enabled) else it } }

    override suspend fun replaceAll(rules: List<BlockRule>) = mutate { rules }

    private suspend fun mutate(transform: (List<BlockRule>) -> List<BlockRule>) {
        dataStore.edit { prefs ->
            val current = PrefsCodec.decodeRules(prefs[PrefsKeys.BLOCK_RULES].orEmpty())
            prefs[PrefsKeys.BLOCK_RULES] = PrefsCodec.encodeRules(transform(current))
        }
    }
}

class DataStoreLimitsStore(
    private val dataStore: DataStore<Preferences>,
) : LimitsStore {

    override val timeLimits: Flow<List<TimeLimit>> = dataStore.safeData().map { prefs ->
        PrefsCodec.decodeTimeLimits(prefs[PrefsKeys.TIME_LIMITS].orEmpty())
    }

    override val openLimits: Flow<List<OpenLimit>> = dataStore.safeData().map { prefs ->
        PrefsCodec.decodeOpenLimits(prefs[PrefsKeys.OPEN_LIMITS].orEmpty())
    }

    override suspend fun setTimeLimit(limit: TimeLimit) =
        mutateTime { current -> current.filterNot { it.packageName == limit.packageName } + limit }

    override suspend fun clearTimeLimit(packageName: String) =
        mutateTime { current -> current.filterNot { it.packageName == packageName } }

    override suspend fun setOpenLimit(limit: OpenLimit) =
        mutateOpen { current -> current.filterNot { it.packageName == limit.packageName } + limit }

    override suspend fun clearOpenLimit(packageName: String) =
        mutateOpen { current -> current.filterNot { it.packageName == packageName } }

    private suspend fun mutateTime(transform: (List<TimeLimit>) -> List<TimeLimit>) {
        dataStore.edit { prefs ->
            val current = PrefsCodec.decodeTimeLimits(prefs[PrefsKeys.TIME_LIMITS].orEmpty())
            prefs[PrefsKeys.TIME_LIMITS] = PrefsCodec.encodeTimeLimits(transform(current))
        }
    }

    private suspend fun mutateOpen(transform: (List<OpenLimit>) -> List<OpenLimit>) {
        dataStore.edit { prefs ->
            val current = PrefsCodec.decodeOpenLimits(prefs[PrefsKeys.OPEN_LIMITS].orEmpty())
            prefs[PrefsKeys.OPEN_LIMITS] = PrefsCodec.encodeOpenLimits(transform(current))
        }
    }
}

class DataStoreFocusStateStore(
    private val dataStore: DataStore<Preferences>,
) : FocusStateStore {

    override val defaultBreakLevel: Flow<BreakLevel> = dataStore.safeData().map { prefs ->
        PrefsCodec.decodeBreakLevel(prefs[PrefsKeys.DEFAULT_BREAK_LEVEL])
    }

    override val emergencyPass: Flow<EmergencyPassState> = dataStore.safeData().map { prefs ->
        EmergencyPassState(
            weekStartEpochDay = prefs[PrefsKeys.EMERGENCY_PASS_WEEK_START]
                ?: EmergencyPassState.WEEK_UNINITIALIZED,
            usedAtEpochMs = prefs[PrefsKeys.EMERGENCY_PASS_USED_AT],
        )
    }

    override val breakSession: Flow<BreakSessionState> = dataStore.safeData().map { prefs ->
        prefs[PrefsKeys.BREAK_SESSION]?.let(PrefsCodec::decodeBreakSession) ?: BreakSessionState()
    }

    override val openCounts: Flow<OpenCountState> = dataStore.safeData().map { prefs ->
        OpenCountState(
            epochDay = prefs[PrefsKeys.OPEN_COUNTS_DAY] ?: OpenCountState.DAY_UNINITIALIZED,
            opensByPackage = PrefsCodec.decodeOpenCounts(prefs[PrefsKeys.OPEN_COUNTS].orEmpty()),
        )
    }

    override suspend fun setDefaultBreakLevel(level: BreakLevel) {
        dataStore.edit { prefs -> prefs[PrefsKeys.DEFAULT_BREAK_LEVEL] = level.name }
    }

    override suspend fun setEmergencyPass(state: EmergencyPassState) {
        dataStore.edit { prefs ->
            prefs[PrefsKeys.EMERGENCY_PASS_WEEK_START] = state.weekStartEpochDay
            val usedAt = state.usedAtEpochMs
            if (usedAt == null) {
                prefs.remove(PrefsKeys.EMERGENCY_PASS_USED_AT)
            } else {
                prefs[PrefsKeys.EMERGENCY_PASS_USED_AT] = usedAt
            }
        }
    }

    override suspend fun setBreakSession(state: BreakSessionState) {
        dataStore.edit { prefs -> prefs[PrefsKeys.BREAK_SESSION] = PrefsCodec.encodeBreakSession(state) }
    }

    override suspend fun setOpenCounts(state: OpenCountState) {
        dataStore.edit { prefs ->
            prefs[PrefsKeys.OPEN_COUNTS_DAY] = state.epochDay
            prefs[PrefsKeys.OPEN_COUNTS] = PrefsCodec.encodeOpenCounts(state.opensByPackage)
        }
    }
}
