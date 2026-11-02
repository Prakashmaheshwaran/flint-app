package com.flint.peakfocus.core.history

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

/** A later loading/stale status cannot erase known loss. The owner persists its loss counter. */
internal class HistoryDeliveryHealth {
    private val eventsDropped = MutableStateFlow(false)

    fun noteEventsDropped() { eventsDropped.value = true }

    fun applyTo(summaries: Flow<FocusSummary?>): Flow<FocusSummary?> =
        combine(summaries, eventsDropped) { summary, dropped ->
            if (!dropped || summary == null) summary else summary.copy(
                availability = if (summary.availability == HistoryAvailability.UNAVAILABLE)
                    HistoryAvailability.UNAVAILABLE else HistoryAvailability.DEGRADED,
                lifecycleEventsMissing = true,
            )
        }
}
