package com.flint.peakfocus.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.flint.peakfocus.core.model.*
import kotlinx.coroutines.flow.Flow
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
    val APP_GROUPS = stringPreferencesKey("app_groups_v1")
    val TIME_LIMITS = stringPreferencesKey("time_limits_v1")
    val OPEN_LIMITS = stringPreferencesKey("open_limits_v1")
    val DEFAULT_BREAK_LEVEL = stringPreferencesKey("default_break_level_v1")
    val EMERGENCY_PASS_WEEK_START = longPreferencesKey("emergency_pass_week_start_v1")
    val EMERGENCY_PASS_USED_AT = longPreferencesKey("emergency_pass_used_at_v1")
    val BREAK_SESSION = stringPreferencesKey("break_session_v1")
    val OPEN_COUNTS_DAY = longPreferencesKey("open_counts_day_v1")
    val OPEN_COUNTS = stringPreferencesKey("open_counts_v1")
    val FOCUS_LEDGER = stringPreferencesKey("focus_exemptions_v1")
    val PROTECTION_SCHEMA = longPreferencesKey("protection_schema_v1")
    val PROTECTION_LEDGER = stringPreferencesKey("protection_pass_outbox_v1")
    val PROTECTION_ARCHIVE = stringPreferencesKey("protection_legacy_archive_v1")
}

private fun Preferences.rules(): List<BlockRule> {
    validateInitializedProtectionChildren(this)
    return StrictPrefsCodec.rules(StrictPrefsCodec.string(this, PrefsKeys.BLOCK_RULES).orEmpty())
}
private fun Preferences.groups() = StrictPrefsCodec.groups(StrictPrefsCodec.string(this, PrefsKeys.APP_GROUPS).orEmpty())
private fun Preferences.timeLimits(): List<TimeLimit> {
    validateInitializedProtectionChildren(this)
    return StrictPrefsCodec.timeLimits(StrictPrefsCodec.string(this, PrefsKeys.TIME_LIMITS).orEmpty(),
        StrictPrefsCodec.level(StrictPrefsCodec.string(this, PrefsKeys.DEFAULT_BREAK_LEVEL)))
}
private fun Preferences.openLimits(): List<OpenLimit> {
    validateInitializedProtectionChildren(this)
    return StrictPrefsCodec.openLimits(StrictPrefsCodec.string(this, PrefsKeys.OPEN_LIMITS).orEmpty())
}

/** Pass-owned children stay required; editor policy domains validate their own initialization. */
internal fun validateInitializedProtectionChildren(prefs: Preferences) {
    val schema = StrictPrefsCodec.long(prefs, PrefsKeys.PROTECTION_SCHEMA)
    if (schema == null) {
        if (StrictPrefsCodec.string(prefs, PrefsKeys.PROTECTION_LEDGER) != null ||
            StrictPrefsCodec.string(prefs, PrefsKeys.PROTECTION_ARCHIVE) != null ||
            FocusLedgerCodec.decode(StrictPrefsCodec.string(prefs, PrefsKeys.FOCUS_LEDGER)).protectionInitialized) wireCorrupt()
        return
    }
    if (schema != 1L) wireFuture()
    for (key in listOf(EDITOR_COMMITMENTS_KEY, HARDER_WAIT_KEY))
        if (StrictPrefsCodec.string(prefs, key) == null) wireCorrupt()
    if (StrictPrefsCodec.long(prefs, EDITOR_VERSION_KEY) == null || StrictPrefsCodec.long(prefs, OPEN_CREDIT_SCHEMA) == null) wireCorrupt()
    readEditorState(prefs); readHarderWait(prefs); readOpenCredits(prefs)
}
private fun MutablePreferences.putRules(rules: List<BlockRule>) {
    val encoded = PrefsCodec.encodeRules(rules)
    StrictPrefsCodec.rules(encoded)
    this[PrefsKeys.BLOCK_RULES] = encoded
}

class DataStoreBlockRulesStore(
    private val dataStore: DataStore<Preferences>,
) : BlockRulesStore {

    override val rulesState: Flow<StorageState<List<BlockRule>>> = dataStore.data.storageStates { it.rules() }

    override val rulesOrError: Flow<List<BlockRule>> = rulesState.readyValues(throwOnUnavailable = true)
    override val rules: Flow<List<BlockRule>> = rulesState.readyValues()

    override suspend fun upsert(rule: BlockRule) =
        mutate { current -> current.filterNot { it.id == rule.id } + rule }

    override suspend fun updateRules(upserts: List<BlockRule>, deleteIds: Set<String>) = mutate { current ->
        require(upserts.map { it.id }.distinct().size == upserts.size)
        current.filterNot { it.id in deleteIds || it.id in upserts.map(BlockRule::id) } + upserts
    }

    override suspend fun pruneExpired(nowEpochMs: Long) = mutate { current -> current.filterNot { it.isExpired(nowEpochMs) } }

    override suspend fun startSessionIfIdle(rule: BlockRule, nowEpochMs: Long): Boolean {
        requireFiniteSession(rule, nowEpochMs)
        var started = false
        dataStore.edit { prefs ->
            val current = prefs.rules()
            started = current.canStartSession(rule.id, nowEpochMs)
            if (started) prefs.putRules(current + rule)
        }
        return started
    }

    override suspend fun cancelEasySession(ruleId: String, nowEpochMs: Long): Boolean {
        var cancelled = false
        dataStore.edit { prefs ->
            val current = prefs.rules()
            cancelled = current.canCancelEasySession(ruleId, nowEpochMs)
            if (cancelled) prefs.putRules(current.filterNot { it.id == ruleId })
        }
        return cancelled
    }

    override suspend fun delete(ruleId: String) =
        mutate { current -> current.filterNot { it.id == ruleId } }

    override suspend fun endTimerSession(expected: BlockRule, nowEpochMs: Long): Boolean {
        var ended = false
        dataStore.edit { prefs ->
            val current = prefs.rules()
            ended = current.canEndTimerSession(expected, nowEpochMs)
            if (ended) prefs.putRules(current.filterNot { it.id == expected.id })
        }
        return ended
    }

    override suspend fun setEnabled(ruleId: String, enabled: Boolean) =
        mutate { current -> current.map { if (it.id == ruleId) it.copy(enabled = enabled) else it } }

    override suspend fun setEnabled(ruleIds: Set<String>, enabled: Boolean) =
        mutate { current -> current.map { if (it.id in ruleIds) it.copy(enabled = enabled) else it } }

    override suspend fun replaceAll(rules: List<BlockRule>) = mutate { rules }

    private suspend fun mutate(transform: (List<BlockRule>) -> List<BlockRule>) {
        dataStore.edit { prefs ->
            val current = prefs.rules()
            prefs.putRules(transform(current))
        }
    }
}

class DataStoreGroupsStore(
    private val dataStore: DataStore<Preferences>,
) : GroupsStore {

    override val groupsState: Flow<StorageState<List<AppGroup>>> = dataStore.data.storageStates { it.groups() }
    override val groups: Flow<List<AppGroup>> = groupsState.readyValues()

    override suspend fun upsert(group: AppGroup) =
        mutate { current -> current.filterNot { it.id == group.id } + group }

    override suspend fun delete(groupId: String) =
        mutate { current -> current.filterNot { it.id == groupId } }

    private suspend fun mutate(transform: (List<AppGroup>) -> List<AppGroup>) {
        dataStore.edit { prefs ->
            val current = prefs.groups()
            val encoded = PrefsCodec.encodeGroups(transform(current))
            StrictPrefsCodec.groups(encoded)
            prefs[PrefsKeys.APP_GROUPS] = encoded
        }
    }
}

class DataStoreLimitsStore(
    private val dataStore: DataStore<Preferences>,
) : LimitsStore {

    override val timeLimitsState: Flow<StorageState<List<TimeLimit>>> = dataStore.data.storageStates { it.timeLimits() }
    override val openLimitsState: Flow<StorageState<List<OpenLimit>>> = dataStore.data.storageStates { it.openLimits() }
    override val timeLimits: Flow<List<TimeLimit>> = timeLimitsState.readyValues()
    override val openLimits: Flow<List<OpenLimit>> = openLimitsState.readyValues()

    override suspend fun setLimits(packageName: String, time: TimeLimit?, open: OpenLimit?) {
        require(time == null || time.packageName == packageName)
        require(open == null || open.packageName == packageName)
        dataStore.edit { prefs ->
            val times = prefs.timeLimits().filterNot { it.packageName == packageName } + listOfNotNull(time)
            val opens = prefs.openLimits().filterNot { it.packageName == packageName } + listOfNotNull(open)
            val encodedTime = PrefsCodec.encodeTimeLimits(times)
            val encodedOpen = PrefsCodec.encodeOpenLimits(opens)
            StrictPrefsCodec.timeLimits(encodedTime); StrictPrefsCodec.openLimits(encodedOpen)
            prefs[PrefsKeys.TIME_LIMITS] = encodedTime
            prefs[PrefsKeys.OPEN_LIMITS] = encodedOpen
        }
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
            val current = prefs.timeLimits()
            val encoded = PrefsCodec.encodeTimeLimits(transform(current))
            StrictPrefsCodec.timeLimits(encoded)
            prefs[PrefsKeys.TIME_LIMITS] = encoded
        }
    }

    private suspend fun mutateOpen(transform: (List<OpenLimit>) -> List<OpenLimit>) {
        dataStore.edit { prefs ->
            val current = prefs.openLimits()
            val encoded = PrefsCodec.encodeOpenLimits(transform(current))
            StrictPrefsCodec.openLimits(encoded)
            prefs[PrefsKeys.OPEN_LIMITS] = encoded
        }
    }
}

class DataStoreFocusStateStore(
    private val dataStore: DataStore<Preferences>,
) : FocusStateStore {

    override val state: Flow<StorageState<FocusSnapshot>> = dataStore.data.storageStates(::readFocus)
    override val defaultBreakLevel: Flow<BreakLevel> = state.readyValues().map { it.defaultBreakLevel }
    override val emergencyPass: Flow<EmergencyPassState> = state.readyValues().map { it.emergencyPass }
    override val breakSession: Flow<BreakSessionState> = state.readyValues().map { it.breakSession }
    override val openCounts: Flow<OpenCountState> = state.readyValues().map { it.openCounts }

    override suspend fun <R> transact(transform: (FocusSnapshot) -> FocusMutation<R>): FocusCommit<R> {
        var committed: FocusCommit.Committed<R>? = null
        return try {
            dataStore.edit { prefs ->
                val current = readFocus(prefs)
                val mutation = transform(current)
                committed = FocusCommit.Committed(writeFocus(prefs, current, mutation.next), mutation.result)
            }
            checkNotNull(committed) // edit returned: the acknowledgement now represents durable data.
        } catch (cause: Exception) {
            FocusCommit.Unavailable(storageFailure(cause) ?: throw cause)
        }
    }

    private suspend fun update(transform: (FocusSnapshot) -> FocusSnapshot) {
        when (val result = transact { FocusMutation(transform(it), Unit) }) {
            is FocusCommit.Committed -> Unit
            is FocusCommit.Unavailable -> throw StorageUnavailableException(result.reason)
        }
    }

    override suspend fun setDefaultBreakLevel(level: BreakLevel) = update { it.copy(defaultBreakLevel = level) }
    override suspend fun setEmergencyPass(state: EmergencyPassState) = update { it.copy(emergencyPass = state) }
    override suspend fun setBreakSession(state: BreakSessionState) = update { it.copy(breakSession = state) }
    override suspend fun setOpenCounts(state: OpenCountState) = update { it.copy(openCounts = state) }
}

/** The caller owns the surrounding edit; this helper never commits or publishes early. */
internal fun writeFocus(prefs: MutablePreferences, current: FocusSnapshot, proposed: FocusSnapshot, forceRevision: Boolean = false): FocusSnapshot {
    require(proposed.revision == current.revision) { "Focus revision is store-owned" }
    require(proposed.minimumRequestEpochMs >= current.minimumRequestEpochMs) { "Request retention cannot rewind" }
    require(current.protection == null || proposed.protection != null) { "Initialized protection cannot become a legacy default" }
    val next = if (proposed == current && !forceRevision) current else {
        require(current.revision < Long.MAX_VALUE) { "Focus revision exhausted" }
        proposed.copy(revision = current.revision + 1)
    }
    // Validate ALL output before assigning preferences, and decode the result to freeze it.
    val ledger = FocusLedgerCodec.encode(FocusLedgerCodec.Ledger(next.exemptions, next.breaksByCause, next.revision, next.minimumRequestEpochMs,
        protectionInitialized = next.protection != null))
    val protection = next.protection?.let(ProtectionLedgerCodec::encode)
    val session = PrefsCodec.encodeBreakSession(next.breakSession)
    val counts = PrefsCodec.encodeOpenCounts(next.openCounts.opensByPackage)
    StrictPrefsCodec.session(session); StrictPrefsCodec.counts(counts)
    require(next.emergencyPass.usedAtEpochMs?.let { it >= 0 } != false)
    val contract = next.protection
    require(contract == null || contract.lastUsedAtEpochMs == next.emergencyPass.usedAtEpochMs)
    if (next.defaultBreakLevel != current.defaultBreakLevel) prefs[PrefsKeys.DEFAULT_BREAK_LEVEL] = next.defaultBreakLevel.name
    if (next.breakSession != current.breakSession) prefs[PrefsKeys.BREAK_SESSION] = session
    if (next.emergencyPass != current.emergencyPass) {
        prefs[PrefsKeys.EMERGENCY_PASS_WEEK_START] = next.emergencyPass.weekStartEpochDay
        val usedAt = next.emergencyPass.usedAtEpochMs
        if (usedAt == null) prefs.remove(PrefsKeys.EMERGENCY_PASS_USED_AT) else prefs[PrefsKeys.EMERGENCY_PASS_USED_AT] = usedAt
    }
    if (next.openCounts != current.openCounts) {
        prefs[PrefsKeys.OPEN_COUNTS_DAY] = next.openCounts.epochDay
        prefs[PrefsKeys.OPEN_COUNTS] = counts
    }
    if (next.revision != current.revision) prefs[PrefsKeys.FOCUS_LEDGER] = ledger
    if (protection != null && next.protection != current.protection) {
        prefs[PrefsKeys.PROTECTION_LEDGER] = protection
        prefs[PrefsKeys.PROTECTION_SCHEMA] = 1L
    }
    return readFocus(prefs)
}

internal fun readFocus(prefs: Preferences): FocusSnapshot {
    validateInitializedProtectionChildren(prefs)
    // The legacy quota is a pair written in one edit. A retained day with missing
    // counts (or vice versa) is corruption, never a fresh allowance during migration
    // or through the production FocusStateStore reader.
    val countsDay = StrictPrefsCodec.long(prefs, PrefsKeys.OPEN_COUNTS_DAY)
    val countsRaw = StrictPrefsCodec.string(prefs, PrefsKeys.OPEN_COUNTS)
    if ((countsDay == null) != (countsRaw == null)) wireCorrupt()
    val ledger = FocusLedgerCodec.decode(StrictPrefsCodec.string(prefs, PrefsKeys.FOCUS_LEDGER))
    val usedAt = StrictPrefsCodec.long(prefs, PrefsKeys.EMERGENCY_PASS_USED_AT)
    if (usedAt != null && usedAt < 0) throw StorageUnavailableException(StorageFailure.CORRUPT)
    val schema = StrictPrefsCodec.long(prefs, PrefsKeys.PROTECTION_SCHEMA)
    val passRaw = StrictPrefsCodec.string(prefs, PrefsKeys.PROTECTION_LEDGER)
    val archive = StrictPrefsCodec.string(prefs, PrefsKeys.PROTECTION_ARCHIVE)
    if (schema != null && schema != 1L) wireFuture()
    if (schema == null && (passRaw != null || archive != null)) wireCorrupt()
    if (ledger.protectionInitialized != (schema != null)) wireCorrupt()
    val protection = if (schema == null) null else {
        if (passRaw == null || archive == null || StrictPrefsCodec.string(prefs, PrefsKeys.FOCUS_LEDGER) == null) wireCorrupt()
        if (!archive.startsWith("A|1\n")) wireCorrupt()
        ProtectionLedgerCodec.decode(passRaw).also { if (it.lastUsedAtEpochMs != usedAt) wireCorrupt() }
    }
    return FocusSnapshot(
        defaultBreakLevel = StrictPrefsCodec.level(StrictPrefsCodec.string(prefs, PrefsKeys.DEFAULT_BREAK_LEVEL)),
        emergencyPass = EmergencyPassState(
            StrictPrefsCodec.long(prefs, PrefsKeys.EMERGENCY_PASS_WEEK_START) ?: EmergencyPassState.WEEK_UNINITIALIZED,
            usedAt,
        ),
        breakSession = StrictPrefsCodec.session(StrictPrefsCodec.string(prefs, PrefsKeys.BREAK_SESSION)),
        openCounts = OpenCountState(
            countsDay ?: OpenCountState.DAY_UNINITIALIZED,
            StrictPrefsCodec.counts(countsRaw.orEmpty()),
        ),
        exemptions = ledger.exemptions,
        breaksByCause = ledger.breaksByCause,
        revision = ledger.revision,
        minimumRequestEpochMs = ledger.minimumRequestEpochMs,
        protection = protection,
    )
}
