package com.flint.peakfocus.glance

import android.content.Context
import com.flint.peakfocus.core.history.FocusHistoryBridge
import com.flint.peakfocus.core.history.FocusHistoryText
import com.flint.peakfocus.core.history.FocusSummary
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Public F projection only: no log files, aggregation, lifecycle writes or usage estimates. */
class FocusHistoryGlanceReader internal constructor(
    summaries: Flow<FocusSummary?>,
    scope: CoroutineScope,
    dayNow: () -> LocalDate = { LocalDate.now() },
) : GlanceFocusReader {
    /** F's bridge must be started by app composition before constructing this reader. */
    constructor(context: Context, scope: CoroutineScope) : this(
        FocusHistoryBridge.summaries(context.applicationContext), scope,
    )

    private val latest = summaries.map { focusGlanceReading(it, dayNow()) }
        .catch { emit(FocusTodayReading()) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, FocusTodayReading())

    override val changes = latest.map { Unit }
    override suspend fun read() = latest.value
}

internal fun focusGlanceReading(summary: FocusSummary?, today: LocalDate): FocusTodayReading = FocusTodayReading(
    text = FocusHistoryText.today(summary),
    note = FocusHistoryText.note(summary),
    day = summary?.days?.lastOrNull()?.date ?: today,
)
