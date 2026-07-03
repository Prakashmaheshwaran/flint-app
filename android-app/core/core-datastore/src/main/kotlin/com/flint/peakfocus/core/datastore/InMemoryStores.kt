package com.flint.peakfocus.core.datastore

import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.BreakLevel
import com.flint.peakfocus.core.model.BreakSessionState
import com.flint.peakfocus.core.model.EmergencyPassState
import com.flint.peakfocus.core.model.OpenCountState
import com.flint.peakfocus.core.model.OpenLimit
import com.flint.peakfocus.core.model.TimeLimit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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

    override suspend fun delete(ruleId: String) {
        state.update { current -> current.filterNot { it.id == ruleId } }
    }

    override suspend fun setEnabled(ruleId: String, enabled: Boolean) {
        state.update { current -> current.map { if (it.id == ruleId) it.copy(enabled = enabled) else it } }
    }

    override suspend fun replaceAll(rules: List<BlockRule>) {
        state.value = rules
    }
}

class InMemoryLimitsStore(
    initialTimeLimits: List<TimeLimit> = emptyList(),
    initialOpenLimits: List<OpenLimit> = emptyList(),
) : LimitsStore {

    private val time = MutableStateFlow(initialTimeLimits)
    private val open = MutableStateFlow(initialOpenLimits)

    override val timeLimits: Flow<List<TimeLimit>> = time.asStateFlow()

    override val openLimits: Flow<List<OpenLimit>> = open.asStateFlow()

    override suspend fun setTimeLimit(limit: TimeLimit) {
        time.update { current -> current.filterNot { it.packageName == limit.packageName } + limit }
    }

    override suspend fun clearTimeLimit(packageName: String) {
        time.update { current -> current.filterNot { it.packageName == packageName } }
    }

    override suspend fun setOpenLimit(limit: OpenLimit) {
        open.update { current -> current.filterNot { it.packageName == limit.packageName } + limit }
    }

    override suspend fun clearOpenLimit(packageName: String) {
        open.update { current -> current.filterNot { it.packageName == packageName } }
    }
}

class InMemoryFocusStateStore : FocusStateStore {

    private val level = MutableStateFlow(BreakLevel.EASY)
    private val pass = MutableStateFlow(EmergencyPassState())
    private val session = MutableStateFlow(BreakSessionState())
    private val counts = MutableStateFlow(OpenCountState())

    override val defaultBreakLevel: Flow<BreakLevel> = level.asStateFlow()

    override val emergencyPass: Flow<EmergencyPassState> = pass.asStateFlow()

    override val breakSession: Flow<BreakSessionState> = session.asStateFlow()

    override val openCounts: Flow<OpenCountState> = counts.asStateFlow()

    override suspend fun setDefaultBreakLevel(level: BreakLevel) {
        this.level.value = level
    }

    override suspend fun setEmergencyPass(state: EmergencyPassState) {
        pass.value = state
    }

    override suspend fun setBreakSession(state: BreakSessionState) {
        session.value = state
    }

    override suspend fun setOpenCounts(state: OpenCountState) {
        counts.value = state
    }
}
