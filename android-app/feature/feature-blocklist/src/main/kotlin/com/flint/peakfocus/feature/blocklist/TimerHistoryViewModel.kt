package com.flint.peakfocus.feature.blocklist

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.flint.peakfocus.core.history.FocusHistoryBridge
import com.flint.peakfocus.core.history.FocusSession
import com.flint.peakfocus.core.history.FocusSummary
import com.flint.peakfocus.core.history.SessionOutcome
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class TimerHistoryState(val summary: FocusSummary? = null, val completion: FocusSession? = null)

internal fun completionFromHistory(summary: FocusSummary?, dismissedId: String?, todayEpochMs: Long, zone: ZoneId): FocusSession? {
    val today = Instant.ofEpochMilli(todayEpochMs).atZone(zone).toLocalDate()
    val latest = summary?.sessions?.maxByOrNull { it.end } ?: return null
    return latest.takeIf {
        it.id != dismissedId && it.outcome == SessionOutcome.COMPLETED && it.terminalRecorded &&
            Instant.ofEpochMilli(it.end).atZone(zone).toLocalDate() == today
    }
}

internal interface CompletionDismissalStore {
    suspend fun read(): String?
    suspend fun write(id: String): Boolean
}

private class PreferencesCompletionDismissalStore(context: Context) : CompletionDismissalStore {
    private val app = context.applicationContext
    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        runCatching { app.getSharedPreferences("focus-history-ui", Context.MODE_PRIVATE).getString("dismissed-completion", null) }.getOrNull()
    }
    override suspend fun write(id: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { app.getSharedPreferences("focus-history-ui", Context.MODE_PRIVATE).edit().putString("dismissed-completion", id).commit() }.getOrDefault(false)
    }
}

internal class TimerHistoryViewModel(
    summaries: Flow<FocusSummary?>,
    private val dismissalStore: CompletionDismissalStore,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : ViewModel() {
    constructor(context: Context) : this(FocusHistoryBridge.summaries(context.applicationContext), PreferencesCompletionDismissalStore(context))
    private data class Dismissal(val id: String?)
    private val dismissed = MutableStateFlow<Dismissal?>(null)
    init { viewModelScope.launch { dismissed.value = Dismissal(dismissalStore.read()) } }
    val state = combine(summaries, dismissed) { summary, dismissal ->
        TimerHistoryState(summary, if (dismissal == null) null else completionFromHistory(summary, dismissal.id, now(), zone()))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TimerHistoryState())

    fun dismissCompletion(id: String) { viewModelScope.launch {
        if (dismissalStore.write(id)) dismissed.value = Dismissal(id)
    } }

    companion object {
        fun factory(context: Context) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = TimerHistoryViewModel(context) as T
        }
    }
}
