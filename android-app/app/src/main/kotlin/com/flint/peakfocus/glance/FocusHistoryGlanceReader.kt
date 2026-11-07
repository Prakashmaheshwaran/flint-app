package com.flint.peakfocus.glance

import android.content.Context
import android.content.res.Resources
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
    resources: Resources? = null,
    dayNow: () -> LocalDate = { LocalDate.now() },
) : GlanceFocusReader {
    /** F's bridge must be started by app composition before constructing this reader. */
    constructor(context: Context, scope: CoroutineScope) : this(
        FocusHistoryBridge.summaries(context.applicationContext), scope, resources = context.resources,
    )

    private val latest = summaries.map { focusGlanceReading(it, dayNow(), resources) }
        .catch { emit(FocusTodayReading()) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, FocusTodayReading())

    override val changes = latest.map { Unit }
    override suspend fun read() = latest.value
}

internal fun focusGlanceReading(summary: FocusSummary?, today: LocalDate, resources: Resources? = null): FocusTodayReading = FocusTodayReading(
    text = FocusHistoryText.today(summary),
    // Keep F's availability wording; progress is qualified separately for cached surfaces.
    note = FocusHistoryText.note(summary, days = summary?.days?.takeLast(1).orEmpty().map { it.copy(inProgress = false) }, resources = resources),
    day = summary?.days?.lastOrNull()?.date ?: today,
    inProgress = summary?.days?.lastOrNull()?.inProgress == true,
)
