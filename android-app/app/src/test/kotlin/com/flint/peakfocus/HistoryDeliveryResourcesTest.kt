package com.flint.peakfocus

import android.app.Application
import android.content.res.Resources
import com.flint.peakfocus.core.history.FocusHistoryAggregator
import com.flint.peakfocus.core.history.FocusHistoryText
import com.flint.peakfocus.core.history.FocusSummary
import com.flint.peakfocus.core.history.HistoryAvailability
import com.flint.peakfocus.core.history.HistoryEvent
import com.flint.peakfocus.core.history.HistorySnapshot
import com.flint.peakfocus.core.history.R as HistoryR
import com.flint.peakfocus.glance.focusGlanceReading
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class HistoryDeliveryResourcesTest {
    @Suppress("DEPRECATION")
    private fun localizedResources(): Resources {
        val base = RuntimeEnvironment.getApplication().resources
        return object : Resources(base.assets, base.displayMetrics, base.configuration) {
            override fun getString(id: Int): String = when (id) {
                HistoryR.string.focus_history_missing_events -> "Localized missing events"
                HistoryR.string.focus_history_no_recorded_sessions -> "Localized empty history"
                else -> super.getString(id)
            }
            override fun getQuantityString(id: Int, quantity: Int, vararg formatArgs: Any): String = when (id) {
                HistoryR.plurals.focus_history_unconfirmed -> "Localized $quantity unconfirmed"
                else -> super.getQuantityString(id, quantity, *formatArgs)
            }
        }
    }

    @Test fun missingHistoryUsesTheSuppliedResourcesForNoteAndEmptyLabel() {
        val resources = localizedResources()
        val summary = FocusSummary(HistoryAvailability.DEGRADED, emptyList(), emptyList(), 0,
            lifecycleEventsMissing = true)
        assertEquals("Localized missing events", FocusHistoryText.note(summary, resources = resources))
        assertEquals("Localized empty history", FocusHistoryText.today(summary, resources))
        val glance = focusGlanceReading(summary, LocalDate.of(2026, 10, 28), resources)
        assertEquals("Localized empty history", glance.text)
        assertEquals("Localized missing events", glance.note)
    }

    @Test fun unconfirmedCountsAndMissingEventsBothReachTheResourcesAwareGlanceProjection() {
        val begin = 1_793_196_000_000L
        val recorded = FocusHistoryAggregator.summarize(HistorySnapshot(events = listOf(
            HistoryEvent(type = "started", session = "session-unconfirmed", at = begin, tz = "UTC",
                plannedEnd = begin + 300_000, tier = "easy", source = "manual", name = "Focus"),
        ), historyStart = begin, readable = true), begin + 600_000, ZoneOffset.UTC, emptySet())
        val summary = recorded.copy(availability = HistoryAvailability.DEGRADED, lifecycleEventsMissing = true)
        val resources = localizedResources()
        assertEquals(1, summary.days.last().unconfirmed)
        assertEquals(0, summary.days.last().completed)
        assertEquals(0, summary.days.last().endedEarly)
        assertEquals(0L, summary.days.last().focusedMs)
        val note = "Localized 1 unconfirmed · Localized missing events"
        assertEquals(note, FocusHistoryText.note(summary, resources = resources))
        val glance = focusGlanceReading(summary, summary.days.last().date, resources)
        assertEquals("0m", glance.text)
        assertEquals(note, glance.note)
    }
}
