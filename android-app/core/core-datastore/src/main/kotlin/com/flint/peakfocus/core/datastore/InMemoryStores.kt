package com.flint.peakfocus.core.datastore

import com.flint.peakfocus.core.model.AppGroup
import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.BreakLevel
import com.flint.peakfocus.core.model.BreakSessionState
import com.flint.peakfocus.core.model.EmergencyPassState
import com.flint.peakfocus.core.model.OpenCountState
import com.flint.peakfocus.core.model.OpenLimit
import com.flint.peakfocus.core.model.TimeLimit
import com.flint.peakfocus.core.model.FocusSnapshot
import com.flint.peakfocus.core.model.FocusMutation
import com.flint.peakfocus.core.model.StorageState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory implementations of the store interfaces — test doubles and Compose-preview
 * fixtures for feature modules (nothing persists across process death; production code uses
 * [FlintPreferences]). Pure Kotlin, so JVM unit tests can use them without Android.
 */

class InMemoryBlockRulesStore(initial: List<BlockRule> = emptyList()) : BlockRulesStore {

    private val state = MutableStateFlow(initial)

    override val rules: Flow<List<BlockRule>> = state.asStateFlow()

    override suspend fun upsert(rule: BlockRule) {
        state.update { current -> current.filterNot { it.id == rule.id } + rule }
    }

    override suspend fun updateRules(upserts: List<BlockRule>, deleteIds: Set<String>) {
        require(upserts.map { it.id }.distinct().size == upserts.size)
        state.update { current -> current.filterNot { it.id in deleteIds || it.id in upserts.map(BlockRule::id) } + upserts }
    }

    override suspend fun pruneExpired(nowEpochMs: Long) {
        state.update { current -> current.filterNot { it.isExpired(nowEpochMs) } }
    }

    override suspend fun startSessionIfIdle(rule: BlockRule, nowEpochMs: Long): Boolean {
        requireFiniteSession(rule, nowEpochMs)
        while (true) {
            val current = state.value
            if (!current.canStartSession(rule.id, nowEpochMs)) return false
            if (state.compareAndSet(current, current + rule)) return true
        }
    }

    override suspend fun cancelEasySession(ruleId: String, nowEpochMs: Long): Boolean {
        while (true) {
            val current = state.value
            if (!current.canCancelEasySession(ruleId, nowEpochMs)) return false
            if (state.compareAndSet(current, current.filterNot { it.id == ruleId })) return true
        }
    }

    override suspend fun delete(ruleId: String) {
        state.update { current -> current.filterNot { it.id == ruleId } }
    }

    override suspend fun endTimerSession(expected: BlockRule, nowEpochMs: Long): Boolean {
        while (true) {
            val current = state.value
            if (!current.canEndTimerSession(expected, nowEpochMs)) return false
            if (state.compareAndSet(current, current.filterNot { it.id == expected.id })) return true
        }
    }

    override suspend fun setEnabled(ruleId: String, enabled: Boolean) {
        state.update { current -> current.map { if (it.id == ruleId) it.copy(enabled = enabled) else it } }
    }

    override suspend fun setEnabled(ruleIds: Set<String>, enabled: Boolean) {
        state.update { current -> current.map { if (it.id in ruleIds) it.copy(enabled = enabled) else it } }
    }

    override suspend fun replaceAll(rules: List<BlockRule>) {
        StrictPrefsCodec.rules(PrefsCodec.encodeRules(rules))
        state.value = rules
    }
}

class InMemoryGroupsStore(initial: List<AppGroup> = emptyList()) : GroupsStore {

    private val state = MutableStateFlow(initial)

    override val groups: Flow<List<AppGroup>> = state.asStateFlow()

    override suspend fun upsert(group: AppGroup) {
        state.update { current -> current.filterNot { it.id == group.id } + group }
    }

    override suspend fun delete(groupId: String) {
        state.update { current -> current.filterNot { it.id == groupId } }
    }
}

class InMemoryLimitsStore(
    initialTimeLimits: List<TimeLimit> = emptyList(),
    initialOpenLimits: List<OpenLimit> = emptyList(),
) : LimitsStore {

    private val limits = MutableStateFlow(initialTimeLimits to initialOpenLimits)

    override val timeLimits: Flow<List<TimeLimit>> = limits.map { it.first }

    override val openLimits: Flow<List<OpenLimit>> = limits.map { it.second }

    override suspend fun setLimits(packageName: String, time: TimeLimit?, open: OpenLimit?) {
        require(time == null || time.packageName == packageName)
        require(open == null || open.packageName == packageName)
        limits.update { (times, opens) ->
            (times.filterNot { it.packageName == packageName } + listOfNotNull(time)) to
                (opens.filterNot { it.packageName == packageName } + listOfNotNull(open))
        }
    }

    override suspend fun setTimeLimit(limit: TimeLimit) {
        limits.update { (time, open) -> (time.filterNot { it.packageName == limit.packageName } + limit) to open }
    }

    override suspend fun clearTimeLimit(packageName: String) {
        limits.update { (time, open) -> time.filterNot { it.packageName == packageName } to open }
    }

    override suspend fun setOpenLimit(limit: OpenLimit) {
        limits.update { (time, open) -> time to (open.filterNot { it.packageName == limit.packageName } + limit) }
    }

    override suspend fun clearOpenLimit(packageName: String) {
        limits.update { (time, open) -> time to open.filterNot { it.packageName == packageName } }
    }
}

class InMemoryFocusStateStore : FocusStateStore {

    private val snapshot = MutableStateFlow(FocusSnapshot())
    private val mutex = Mutex()

    override val state: Flow<StorageState<FocusSnapshot>> = snapshot.withReadiness()
    override val defaultBreakLevel: Flow<BreakLevel> = snapshot.map { it.defaultBreakLevel }
    override val emergencyPass: Flow<EmergencyPassState> = snapshot.map { it.emergencyPass }
    override val breakSession: Flow<BreakSessionState> = snapshot.map { it.breakSession }
    override val openCounts: Flow<OpenCountState> = snapshot.map { it.openCounts }

    override suspend fun <R> transact(transform: (FocusSnapshot) -> FocusMutation<R>): FocusCommit<R> = mutex.withLock {
        val current = snapshot.value
        val mutation = transform(current)
        require(mutation.next.revision == current.revision) { "Focus revision is store-owned" }
        val next = if (mutation.next == current) current else {
            require(current.revision < Long.MAX_VALUE) { "Focus revision exhausted" }
            mutation.next.copy(revision = current.revision + 1)
        }
        val ledger = FocusLedgerCodec.decode(FocusLedgerCodec.encode(FocusLedgerCodec.Ledger(next.exemptions, next.breaksByCause, next.revision)))
        StrictPrefsCodec.session(PrefsCodec.encodeBreakSession(next.breakSession))
        StrictPrefsCodec.counts(PrefsCodec.encodeOpenCounts(next.openCounts.opensByPackage))
        val frozen = next.copy(exemptions = ledger.exemptions, breaksByCause = ledger.breaksByCause,
            openCounts = next.openCounts.copy(opensByPackage = next.openCounts.opensByPackage.toMap()))
        snapshot.value = frozen
        FocusCommit.Committed(frozen, mutation.result)
    }

    override suspend fun setDefaultBreakLevel(level: BreakLevel) {
        transact { FocusMutation(it.copy(defaultBreakLevel = level), Unit) }
    }

    override suspend fun setEmergencyPass(state: EmergencyPassState) {
        transact { FocusMutation(it.copy(emergencyPass = state), Unit) }
    }

    override suspend fun setBreakSession(state: BreakSessionState) {
        transact { FocusMutation(it.copy(breakSession = state), Unit) }
    }

    override suspend fun setOpenCounts(state: OpenCountState) {
        transact { FocusMutation(it.copy(openCounts = state), Unit) }
    }
}
