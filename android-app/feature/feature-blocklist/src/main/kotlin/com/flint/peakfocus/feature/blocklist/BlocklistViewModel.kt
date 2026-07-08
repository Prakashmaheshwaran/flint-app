package com.flint.peakfocus.feature.blocklist

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.flint.peakfocus.core.datastore.BlockRulesStore
import com.flint.peakfocus.core.datastore.FlintPreferences
import com.flint.peakfocus.core.datastore.FocusStateStore
import com.flint.peakfocus.core.datastore.GroupsStore
import com.flint.peakfocus.core.datastore.LimitsStore
import com.flint.peakfocus.core.model.AppGroup
import com.flint.peakfocus.core.model.AppRef
import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.BreakLevel
import com.flint.peakfocus.core.model.DomainRef
import com.flint.peakfocus.core.model.OpenLimit
import com.flint.peakfocus.core.model.TimeLimit
import java.util.Calendar
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The rules section of the overview: loading until the first DataStore emission.
 *  [Ready.statuses] carries each rule's minute-tick live status, keyed by rule id. */
internal sealed interface RulesUiState {
    data object Loading : RulesUiState
    data class Ready(
        val rules: List<BlockRule>,
        val statuses: Map<String, RuleLiveStatus> = emptyMap(),
    ) : RulesUiState
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
    private val groupsStore: GroupsStore,
    private val loadInstalledApps: () -> List<InstalledApp>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val newRuleId: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {

    private val installedAppsState =
        MutableStateFlow<InstalledAppsUiState>(InstalledAppsUiState.Loading)

    /** Launchable apps for the pickers, loaded once off the main thread. */
    val installedApps: StateFlow<InstalledAppsUiState> = installedAppsState.asStateFlow()

    /**
     * All configured rules, name-sorted for a stable list (upsert reorders the stored list),
     * re-derived every minute so each card's "Active now" / next-edge status tracks the clock,
     * not just store writes. [RuleLiveStatus] equality keeps unchanged ticks from recomposing.
     */
    val rules: StateFlow<RulesUiState> = combine(rulesStore.rules, minuteTicks()) { list, now ->
        val sorted = list.sortedWith(
            compareBy(String.CASE_INSENSITIVE_ORDER) { rule: BlockRule -> rule.name }
                .thenBy { it.id },
        )
        val ready: RulesUiState = RulesUiState.Ready(sorted, ruleLiveStatuses(sorted, now))
        ready
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RulesUiState.Loading)

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

    /** Saved target groups for the editor's one-tap apply, name-sorted like rules. */
    val groups: StateFlow<List<AppGroup>> = groupsStore.groups
        .map { list ->
            list.sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER) { group: AppGroup -> group.name }
                    .thenBy { it.id },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    /**
     * Saves the editor's current targets as a reusable named group. Blank names and empty
     * target sets are refused outright — the editor gates the affordance, this is defense.
     * Upserts by case-insensitive name: re-saving "Social" replaces that group's targets
     * instead of accumulating duplicates the chip rail could never tell apart.
     */
    fun saveGroup(name: String, apps: Set<AppRef>, domains: List<String>) {
        if (name.isBlank() || (apps.isEmpty() && domains.isEmpty())) return
        val trimmed = name.trim()
        viewModelScope.launch {
            val existing = groupsStore.groups.first()
                .firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
            groupsStore.upsert(
                AppGroup(
                    id = existing?.id ?: newRuleId(),
                    name = existing?.name ?: trimmed,
                    apps = apps,
                    domains = domains.map { DomainRef(it) }.toSet(),
                ),
            )
        }
    }

    fun deleteGroup(groupId: String) {
        viewModelScope.launch { groupsStore.delete(groupId) }
    }

    fun setRuleEnabled(ruleId: String, enabled: Boolean) {
        viewModelScope.launch { rulesStore.setEnabled(ruleId, enabled) }
    }

    /**
     * Materializes the sleep config onto the schedules engine (see SleepRules.kt). The
     * morning rule is deleted when Morning Assist is off — [SleepConfig.toRules] only emits
     * it while on, and a stale one would keep enforcing.
     */
    fun saveSleep(config: SleepConfig) {
        if (validate(config).isNotEmpty()) return
        viewModelScope.launch {
            config.toRules().forEach { rulesStore.upsert(it) }
            if (!config.morningEnabled) rulesStore.delete(SLEEP_MORNING_RULE_ID)
        }
    }

    /** Flip sleep mode on/off from the overview card — both sleep rules move together, in
     *  ONE store transaction (separate writes could desync them across a process death). */
    fun setSleepEnabled(enabled: Boolean) {
        viewModelScope.launch {
            rulesStore.setEnabled(setOf(SLEEP_WINDOW_RULE_ID, SLEEP_MORNING_RULE_ID), enabled)
        }
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
                        groupsStore = FlintPreferences.groups(appContext),
                        loadInstalledApps = source::loadLaunchable,
                    ) as T
                }
            }
        }
    }
}

/** Emits now-millis immediately, then at every minute boundary — status edges land on time,
 *  not up to 59s late the way a fixed 60s delay would. Cancels with its collector. */
private fun minuteTicks(): Flow<Long> = flow {
    while (true) {
        val now = System.currentTimeMillis()
        emit(now)
        delay(60_000L - now % 60_000L)
    }
}

/** One rule's live schedule state for the overview: biting right now, plus the next edge
 *  ("Starts 21:00" / "Until 07:00"). Null [nextChange] = no edge worth naming. */
internal data class RuleLiveStatus(val activeNow: Boolean, val nextChange: String?)

/**
 * Statuses for every rule at [nowMillis] local time. Calendar, not java.time (minSdk 23, no
 * desugaring); Calendar's Sunday-first weekday is mapped to the model's ISO numbering.
 */
internal fun ruleLiveStatuses(
    rules: List<BlockRule>,
    nowMillis: Long,
): Map<String, RuleLiveStatus> {
    val calendar = Calendar.getInstance().apply { timeInMillis = nowMillis }
    val isoDay = ((calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1
    val minuteOfDay = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
    return rules.associate { it.id to ruleLiveStatus(it, isoDay, minuteOfDay) }
}

/**
 * Read-only mirror of `BlockDecisionEngine.scheduleActive` (half-open window; an overnight
 * window's post-midnight tail belongs to the previous day's night) so the overview can say
 * "Active now" without reaching into the engine module. The next-change label names only the
 * time — the card's schedule summary line already carries the days.
 */
internal fun ruleLiveStatus(rule: BlockRule, isoDay: Int, minuteOfDay: Int): RuleLiveStatus {
    if (!rule.enabled) return RuleLiveStatus(activeNow = false, nextChange = null)
    val schedule = rule.schedule
        ?: return RuleLiveStatus(activeNow = true, nextChange = null)
    val start = schedule.startMinuteOfDay
    val end = schedule.endMinuteOfDay
    if (start !in MINUTE_OF_DAY_RANGE || end !in MINUTE_OF_DAY_RANGE || start == end) {
        // Equal endpoints never match in the engine; the summary line already flags it.
        return RuleLiveStatus(activeNow = false, nextChange = null)
    }
    val activeNow = if (start < end) {
        dayAllowed(schedule.daysOfWeek, isoDay) && minuteOfDay in start until end
    } else {
        when {
            minuteOfDay >= start -> dayAllowed(schedule.daysOfWeek, isoDay)
            minuteOfDay < end -> dayAllowed(schedule.daysOfWeek, previousIsoDay(isoDay))
            else -> false
        }
    }
    return if (activeNow) {
        RuleLiveStatus(activeNow = true, nextChange = "Until ${formatMinuteOfDay(end)}")
    } else {
        RuleLiveStatus(activeNow = false, nextChange = "Starts ${formatMinuteOfDay(start)}")
    }
}

/** Day gate matching the engine: an empty set means every day. */
private fun dayAllowed(days: Set<Int>, isoDay: Int): Boolean = days.isEmpty() || isoDay in days

private fun previousIsoDay(isoDay: Int): Int = if (isoDay == 1) 7 else isoDay - 1
