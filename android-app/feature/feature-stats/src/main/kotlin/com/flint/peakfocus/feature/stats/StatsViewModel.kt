package com.flint.peakfocus.feature.stats

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.flint.peakfocus.permissions.UsageAccess
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Queries usage stats off the main thread and exposes [StatsUiState]. Dependencies are plain
 * functions so the ViewModel itself stays trivially constructible in tests; [factory] does the
 * real wiring (AppOps grant check + [UsageStatsSource]).
 */
class StatsViewModel(
    private val isUsageAccessGranted: () -> Boolean,
    private val loadReport: (nowMillis: Long) -> UsageReport,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _uiState = MutableStateFlow<StatsUiState>(StatsUiState.Loading)
    val uiState: StateFlow<StatsUiState> = _uiState.asStateFlow()

    /**
     * Re-checks the grant and reloads the report. Called on entry and again on every ON_RESUME,
     * so returning from Settings → Usage access flips [StatsUiState.PermissionRequired] to a
     * report without any manual refresh. A grant can also be *revoked* while we're backgrounded
     * (some OEMs reset special access after an OTA — see permissions-special), hence the
     * re-check rather than a one-shot.
     */
    fun refresh(nowMillis: Long = System.currentTimeMillis()) {
        viewModelScope.launch {
            if (!isUsageAccessGranted()) {
                _uiState.value = StatsUiState.PermissionRequired
                return@launch
            }
            if (_uiState.value !is StatsUiState.Ready) {
                _uiState.value = StatsUiState.Loading
            }
            val report = withContext(ioDispatcher) { loadReport(nowMillis) }
            _uiState.value = StatsUiState.Ready(report)
        }
    }

    companion object {
        /** Production wiring. [context] is captured as its application context. */
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val source = UsageStatsSource(appContext)
                    return StatsViewModel(
                        isUsageAccessGranted = { UsageAccess.isGranted(appContext) },
                        loadReport = source::loadReport,
                    ) as T
                }
            }
        }
    }
}
