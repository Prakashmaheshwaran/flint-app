package com.flint.peakfocus.feature.blocklist

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.flint.peakfocus.core.datastore.BlockRulesStore
import com.flint.peakfocus.core.datastore.FlintPreferences
import com.flint.peakfocus.core.datastore.FocusStateStore
import com.flint.peakfocus.core.datastore.LimitsStore
import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.BreakLevel
import com.flint.peakfocus.core.model.OpenLimit
import com.flint.peakfocus.core.model.TimeLimit
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The rules section of the overview: loading until the first DataStore emission. */
internal sealed interface RulesUiState {
    data object Loading : RulesUiState
    data class Ready(val rules: List<BlockRule>) : RulesUiState
}

/** The limits section of the overview. */
internal sealed interface LimitsUiState {
    data object Loading : LimitsUiState
    data class Ready(val rows: List<AppLimitRow>) : LimitsUiState
}

/** The app picker's source list; loading while PackageManager enumeration runs. */
internal sealed interface InstalledAppsUiState {
    data object Loading : InstalledAppsUiState
    data class Ready(val apps: List<InstalledApp>) : InstalledAppsUiState
}

/**
 * State + actions for the whole blocklist feature. All persistence goes through the observable
 * core-datastore stores ([BlockRulesStore], [LimitsStore], [FocusStateStore]) — no persistence
 * of its own. Note the designed seam those stores document: the live AccessibilityService still
 * reads core-data's synchronous stores; A-SERVICE-INTEGRATION bridges what this UI writes into
 * the running engine.
 *
 * Dependencies are interfaces/functions so the ViewModel stays constructible with the
 * `InMemory*` store fakes; [factory] does the production wiring.
 */
internal class BlocklistViewModel(
    private val rulesStore: BlockRulesStore,
    private val limitsStore: LimitsStore,
    private val focusStateStore: FocusStateStore,
    private val loadInstalledApps: () -> List<InstalledApp>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val newRuleId: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {

    private val installedAppsState =
        MutableStateFlow<InstalledAppsUiState>(InstalledAppsUiState.Loading)

    /** Launchable apps for the pickers, loaded once off the main thread. */
    val installedApps: StateFlow<InstalledAppsUiState> = installedAppsState.asStateFlow()

    /** All configured rules, name-sorted for a stable list (upsert reorders the stored list). */
    val rules: StateFlow<RulesUiState> = rulesStore.rules
        .map<List<BlockRule>, RulesUiState> { list ->
            RulesUiState.Ready(
                list.sortedWith(
                    compareBy(String.CASE_INSENSITIVE_ORDER) { rule: BlockRule -> rule.name }
                        .thenBy { it.id },
                ),
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RulesUiState.Loading)

    /** Per-app limit rows: both limit kinds joined per package, labeled once apps have loaded. */
    val limits: StateFlow<LimitsUiState> = combine(
        limitsStore.timeLimits,
        limitsStore.openLimits,
        installedAppsState,
    ) { timeLimits, openLimits, apps ->
        val labels = (apps as? InstalledAppsUiState.Ready)
            ?.apps?.associate { it.packageName to it.label }
            ?: emptyMap()
        val ready: LimitsUiState = LimitsUiState.Ready(
            buildLimitRows(timeLimits, openLimits, labelFor = { labels[it] }),
        )
        ready
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LimitsUiState.Loading)

    /** Preselected break level for new rules/limits (settable elsewhere; consumed here). */
    val defaultBreakLevel: StateFlow<BreakLevel> = focusStateStore.defaultBreakLevel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BreakLevel.EASY)

    init {
        viewModelScope.launch {
            val apps = withContext(ioDispatcher) { loadInstalledApps() }
            installedAppsState.value = InstalledAppsUiState.Ready(apps)
        }
    }

    /**
     * Persists a validated draft (insert when its id is null, replace otherwise). Invalid
     * drafts are refused outright — the editor gates Save on [validate], this is defense.
     */
    fun saveRule(draft: RuleDraft) {
        if (validate(draft).isNotEmpty()) return
        val rule = draft.toRule(id = draft.id ?: newRuleId())
        viewModelScope.launch { rulesStore.upsert(rule) }
    }

    fun deleteRule(ruleId: String) {
        viewModelScope.launch { rulesStore.delete(ruleId) }
    }

    fun setRuleEnabled(ruleId: String, enabled: Boolean) {
        viewModelScope.launch { rulesStore.setEnabled(ruleId, enabled) }
    }

    /** Writes both limit kinds for one package; a null side clears that limit. */
    fun saveLimits(packageName: String, timeLimit: TimeLimit?, openLimit: OpenLimit?) {
        viewModelScope.launch {
            if (timeLimit != null) {
                limitsStore.setTimeLimit(timeLimit)
            } else {
                limitsStore.clearTimeLimit(packageName)
            }
            if (openLimit != null) {
                limitsStore.setOpenLimit(openLimit)
            } else {
                limitsStore.clearOpenLimit(packageName)
            }
        }
    }

    /** Removes both limits for [packageName] (each clear is a no-op if absent). */
    fun clearLimits(packageName: String) {
        viewModelScope.launch {
            limitsStore.clearTimeLimit(packageName)
            limitsStore.clearOpenLimit(packageName)
        }
    }

    companion object {
        /** Production wiring: the shared DataStore-backed stores + the PackageManager source. */
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val source = InstalledAppsSource(appContext)
                    return BlocklistViewModel(
                        rulesStore = FlintPreferences.blockRules(appContext),
                        limitsStore = FlintPreferences.limits(appContext),
                        focusStateStore = FlintPreferences.focusState(appContext),
                        loadInstalledApps = source::loadLaunchable,
                    ) as T
                }
            }
        }
    }
}
