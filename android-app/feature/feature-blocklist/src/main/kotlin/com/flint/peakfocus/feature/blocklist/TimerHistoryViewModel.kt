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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

internal data class TimerHistoryState(val summary: FocusSummary? = null, val completion: FocusSession? = null)

internal fun completionFromHistory(summary: FocusSummary?, dismissedId: String?, todayEpochMs: Long, zone: ZoneId): FocusSession? {
    val today = Instant.ofEpochMilli(todayEpochMs).atZone(zone).toLocalDate()
    val latest = summary?.sessions?.maxByOrNull { it.end } ?: return null
    return latest.takeIf {
        it.id != dismissedId && it.outcome == SessionOutcome.COMPLETED && it.terminalRecorded &&
            Instant.ofEpochMilli(it.end).atZone(zone).toLocalDate() == today
    }
}

internal class TimerHistoryViewModel(context: Context) : ViewModel() {
    private val dismissed = MutableStateFlow<String?>(null)
    val state = combine(FocusHistoryBridge.summaries(context.applicationContext), dismissed) { summary, id ->
        TimerHistoryState(summary, completionFromHistory(summary, id, System.currentTimeMillis(), ZoneId.systemDefault()))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TimerHistoryState())

    fun dismissCompletion(id: String) { dismissed.value = id }

    companion object {
        fun factory(context: Context) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = TimerHistoryViewModel(context) as T
        }
    }
}
