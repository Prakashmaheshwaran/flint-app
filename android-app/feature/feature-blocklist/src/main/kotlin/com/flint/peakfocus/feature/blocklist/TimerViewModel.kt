package com.flint.peakfocus.feature.blocklist

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.flint.peakfocus.core.datastore.BlockRulesStore
import com.flint.peakfocus.core.datastore.FlintPreferences
import com.flint.peakfocus.core.datastore.EditorCommitmentStore
import com.flint.peakfocus.core.datastore.EditorChange
import com.flint.peakfocus.core.datastore.EditorCommitResult
import com.flint.peakfocus.core.datastore.EditorSafety
import com.flint.peakfocus.core.datastore.HarderWaitStore
import com.flint.peakfocus.core.datastore.ProtectionClock
import com.flint.peakfocus.core.model.AccessClockStamp
import com.flint.peakfocus.core.model.StorageState
import com.flint.peakfocus.blocking.engine.HarderWaitPolicy
import com.flint.peakfocus.core.model.AppRef
import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.BreakLevel
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Timer UI owns a draft; the rule store owns every active session and its deadline. */
internal class TimerViewModel(
    private val rulesStore: BlockRulesStore,
    private val loadInstalledApps: () -> List<InstalledApp>,
    private val commitments: EditorCommitmentStore,
    private val harderWaits: HarderWaitStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val protectionClock: () -> AccessClockStamp = ProtectionClock::now,
    private val safety: () -> EditorSafety = { EditorSafety(false) },
) : ViewModel() {
    private val state = MutableStateFlow(TimerUiState(nowEpochMs = now()))
    val uiState: StateFlow<TimerUiState> = state.asStateFlow()
    private var latestRules: List<BlockRule> = emptyList()
    private var rulesJob: Job? = null
    private var appsJob: Job? = null
    private var ticker: Job? = null
    private var exitJob: Job? = null
    private var waitLedger: HarderWaitPolicy.Ledger? = null

    init {
        viewModelScope.launch {
            harderWaits.state.collect { value ->
                waitLedger = (value as? StorageState.Ready)?.value
                refreshClock()
            }
        }
        retryRules()
        retryApps()
    }

    fun retryRules() {
        if (rulesJob?.isActive == true) return
        state.update { it.copy(rulesLoaded = false, rulesError = null) }
        rulesJob = viewModelScope.launch {
            try {
                rulesStore.rulesOrError.collect { rules ->
                    latestRules = rules
                    refreshClock()
                    state.update { it.copy(rulesLoaded = true, rulesError = null) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                state.update { it.copy(rulesLoaded = false, rulesError = "Couldn't load timers. Refresh to try again.") }
            }
        }
    }

    fun retryApps() {
        if (appsJob?.isActive == true) return
        state.update { it.copy(appsState = InstalledAppsUiState.Loading, appsError = null) }
        appsJob = viewModelScope.launch {
            try {
                val apps = withContext(ioDispatcher) { loadInstalledApps() }
                state.update { it.copy(appsState = InstalledAppsUiState.Ready(apps)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                state.update { it.copy(appsError = "Couldn't load installed apps. Try again.",
                    appsState = InstalledAppsUiState.Error("Couldn't load installed apps. Try again.", ::retryApps)) }
            }
        }
    }

    /** The ticker stops with the screen; a fresh wall-clock read restores it on every return. */
    fun setVisible(visible: Boolean) {
        if (!visible) {
            ticker?.cancel()
            ticker = null
            releaseExitHold()
            if (state.value.exit?.phase != TimerExitPhase.WAITING) dismissExit()
            return
        }
        refreshClock()
        if (ticker?.isActive == true) return
        ticker = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                refreshClock()
            }
        }
    }

    fun refreshClock() {
        val timestamp = now()
        state.update { current ->
            val sessions = activeTimerSessions(latestRules, timestamp)
            val expired = current.sessions.any { it.isExpired(timestamp) }
            val remaining = waitLedger?.pending?.let { HarderWaitPolicy().remainingMs(it, protectionClock()) }
            val exit = current.exit?.let { exit ->
                if (exit.phase == TimerExitPhase.WAITING && remaining == 0L) exit.copy(phase = TimerExitPhase.IDLE, waitRemainingMs = 0)
                else if (exit.phase == TimerExitPhase.WAITING) exit.copy(waitRemainingMs = remaining)
                else exit
            }
            current.copy(
                nowEpochMs = timestamp,
                sessions = sessions,
                exit = exit,
                message = if (expired && sessions.isEmpty()) {
                    "Timer finished. Other rules may still apply."
                } else {
                    current.message
                },
            )
        }
    }

    fun selectApps(picked: List<InstalledApp>) {
        if (!state.value.canEdit) return
        val visible = (state.value.appsState as? InstalledAppsUiState.Ready)
            ?.apps?.map { it.packageName }?.toSet() ?: return
        state.update { current ->
            val preserved = current.draft.apps.filter { it.packageName !in visible }
            val chosen = picked.filter { it.packageName in visible }
                .map { AppRef(it.packageName, it.label) }
            current.copy(draft = current.draft.copy(apps = (preserved + chosen).toSet()), error = null)
        }
    }

    fun clearApps() {
        if (state.value.canEdit) state.update { it.copy(draft = it.draft.copy(apps = emptySet())) }
    }

    fun selectDuration(minutes: Int) {
        if (!state.value.canEdit || minutes !in timerDurationChoices) return
        state.update { it.copy(draft = it.draft.copy(durationMinutes = minutes, presetId = null)) }
    }

    fun selectPreset(id: String) {
        if (!state.value.canEdit) return
        val preset = timerPresets.firstOrNull { it.id == id } ?: return
        state.update {
            it.copy(draft = it.draft.copy(durationMinutes = preset.minutes, presetId = preset.id))
        }
    }

    fun selectBreakLevel(level: BreakLevel) {
        if (state.value.canEdit) state.update { it.copy(draft = it.draft.copy(breakLevel = level)) }
    }

    fun start() {
        if (!state.value.canStart) return
        val draft = state.value.draft.copy(apps = state.value.draft.apps.toSet())
        state.update { it.copy(busy = true, error = null, message = null) }
        viewModelScope.launch {
            try {
                val timestamp = now()
                val rule = timerRule(draft, timestamp, newId())
                if (commitments.startTimer(rule, protectionClock(), withContext(ioDispatcher) { safety() })) {
                    // Persistence succeeded. Reflect it immediately even if the collector is
                    // still queued, preventing a second Start between write and flow emission.
                    if (latestRules.none { it.id == rule.id }) latestRules = latestRules + rule
                    refreshClock()
                } else {
                    latestRules = rulesStore.rulesOrError.first()
                    refreshClock()
                    state.update { it.copy(error = "A timer is already running. Finish it before starting another.") }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                state.update { it.copy(error = "Couldn't start the timer. Your selection is saved here; try again.") }
            } finally {
                state.update { it.copy(busy = false) }
            }
        }
    }

    fun cancelSession(ruleId: String) {
        val current = state.value
        if (current.busy || current.rulesError != null || !current.rulesLoaded) return
        val displayed = current.sessions.firstOrNull { it.id == ruleId } ?: return
        if (!canCancelTimer(displayed)) return
        state.update { it.copy(busy = true, error = null, message = null) }
        viewModelScope.launch {
            try {
                val timestamp = now()
                // Strength and activity are checked in the same transaction as deletion.
                // Another writer cannot upgrade the session between our check and removal.
                if (rulesStore.cancelEasySession(ruleId, timestamp)) {
                    com.flint.peakfocus.core.history.FocusHistoryBridge.noteEndRoute(ruleId, "cancel", timestamp)
                    latestRules = latestRules.filterNot { it.id == ruleId }
                    state.update { it.copy(message = "Timer cancelled. Other rules may still apply.") }
                } else {
                    latestRules = rulesStore.rulesOrError.first()
                    val stillActive = activeTimerSessions(latestRules, now()).any { it.id == ruleId }
                    state.update {
                        if (stillActive) it.copy(error = "This timer cannot be ended early.")
                        else it.copy(message = "This timer is no longer active.")
                    }
                }
                refreshClock()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                state.update { it.copy(error = "Couldn't cancel the timer. It may still be active; try again.") }
            } finally {
                state.update { it.copy(busy = false) }
            }
        }
    }

    fun dismissFeedback() {
        state.update { it.copy(error = null, message = null) }
    }

    fun requestExit(ruleId: String) {
        val current = state.value
        if (current.busy || current.exit != null || !current.rulesLoaded || current.rulesError != null) return
        val expected = current.sessions.firstOrNull { it.id == ruleId && canEndNativeTimer(it) } ?: return
        state.update { it.copy(busy = true, error = null, message = null) }
        viewModelScope.launch {
            try {
                when (val result = commitments.request(EditorChange("End ${expected.name}", deleteIds = setOf(expected.id), timerExit = true), protectionClock(), EditorSafety(true))) {
                    is EditorCommitResult.Preview -> state.update { it.copy(exit = TimerExitConfirmation(result.pending.expected.rules.single(), operationId = result.pending.id)) }
                    is EditorCommitResult.Waiting -> state.update { it.copy(exit = TimerExitConfirmation(result.pending.expected.rules.single(), TimerExitPhase.WAITING, operationId = result.pending.id, waitRemainingMs = result.remainingMs)) }
                    is EditorCommitResult.Rejected -> state.update { it.copy(error = result.reason) }
                    is EditorCommitResult.Applied -> Unit
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { state.update { it.copy(error = "Couldn't prepare the exit. Your Timer is still active.") } }
            finally { state.update { it.copy(busy = false) } }
        }
    }

    /** Returns ownership of this hold; a second pointer/action must not reset an existing hold. */
    fun beginExitHold(): Boolean {
        val current = state.value
        val confirmation = current.exit ?: return false
        if (confirmation.phase != TimerExitPhase.IDLE || current.busy || current.rulesError != null) return false
        state.update { it.copy(exit = confirmation.copy(phase = TimerExitPhase.HOLDING)) }
        exitJob = viewModelScope.launch {
            // Coroutine delays use monotonic time, independent of changes to the wall clock.
            for (step in 1..40) {
                delay(TIMER_EXIT_HOLD_MS / 40)
                if (state.value.exit?.expected != confirmation.expected) return@launch
                state.update { it.copy(exit = confirmation.copy(phase = TimerExitPhase.HOLDING, progress = step / 40f)) }
            }
            state.update { it.copy(exit = confirmation.copy(phase = TimerExitPhase.FINISHING, progress = 1f)) }
            delay(TIMER_EXIT_FINISH_MS)
            state.update { it.copy(exit = confirmation.copy(phase = TimerExitPhase.SETTLING, progress = 1f)) }
            delay(TIMER_EXIT_CALLBACK_MS)
            commitTimerExit(confirmation)
        }
        return true
    }

    /** Release or gesture cancellation before the threshold resets progress without a write. */
    fun releaseExitHold() {
        val confirmation = state.value.exit ?: return
        if (confirmation.phase != TimerExitPhase.HOLDING) return
        exitJob?.cancel()
        exitJob = null
        state.update { it.copy(exit = confirmation.copy(phase = TimerExitPhase.IDLE, progress = 0f)) }
    }

    fun dismissExit() {
        if (state.value.exit?.canDismiss != true) return
        val operationId = state.value.exit?.operationId
        exitJob?.cancel()
        exitJob = null
        state.update { it.copy(exit = null) }
        if (operationId != null) viewModelScope.launch { runCatching { commitments.cancel(operationId) } }
    }

    private suspend fun commitTimerExit(confirmation: TimerExitConfirmation) {
        val expected = confirmation.expected
        state.update {
            it.copy(busy = true, exit = TimerExitConfirmation(expected, TimerExitPhase.SAVING, 1f), error = null)
        }
        try {
            val timestamp = now()
            val result = confirmation.operationId?.let { commitments.confirm(it, protectionClock(), EditorSafety(true)) }
            if (result is EditorCommitResult.Applied) {
                com.flint.peakfocus.core.history.FocusHistoryBridge.noteEndRoute(expected.id, "hold_to_end", timestamp)
                latestRules = latestRules.filterNot { it.id == expected.id }
                state.update { it.copy(exit = null, message = "Timer ended. Other rules may still apply.") }
            } else {
                latestRules = rulesStore.rulesOrError.first()
                state.update {
                    it.copy(exit = null, error = (result as? EditorCommitResult.Rejected)?.reason ?: "This timer changed or is still waiting. Review its current state before ending it.")
                }
            }
            refreshClock()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            state.update {
                it.copy(exit = null, error = "Couldn't end the timer. It may still be active; check it before trying again.")
            }
        } finally {
            state.update { it.copy(busy = false) }
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = TimerViewModel(
                    rulesStore = FlintPreferences.blockRules(appContext),
                    loadInstalledApps = InstalledAppsSource(appContext)::loadLaunchable,
                    commitments = FlintPreferences.editorCommitments(appContext),
                    harderWaits = FlintPreferences.harderWait(appContext),
                    safety = { loadEditorSafety(appContext) },
                    // The start instant rides the id so Today's sun arc can place the ember;
                    // see timerStartEpochMs.
                    newId = { "${System.currentTimeMillis()}-${UUID.randomUUID()}" },
                ) as T
            }
        }
    }
}
