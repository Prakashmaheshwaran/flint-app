package com.flint.peakfocus

import android.app.Application
import android.content.res.Resources
import com.flint.peakfocus.core.history.FocusHistoryText
import com.flint.peakfocus.core.history.FocusSummary
import com.flint.peakfocus.core.history.HistoryAvailability
import com.flint.peakfocus.core.history.R as HistoryR
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
    @Test fun missingHistoryUsesTheSuppliedResourcesForNoteAndEmptyLabel() {
        val base = RuntimeEnvironment.getApplication().resources
        val resources = object : Resources(base.assets, base.displayMetrics, base.configuration) {
            override fun getString(id: Int): String = when (id) {
                HistoryR.string.focus_history_missing_events -> "Localized missing events"
                HistoryR.string.focus_history_no_recorded_sessions -> "Localized empty history"
                else -> super.getString(id)
            }
        }
        val summary = FocusSummary(HistoryAvailability.DEGRADED, emptyList(), emptyList(), 0,
            lifecycleEventsMissing = true)
        assertEquals("Localized missing events", FocusHistoryText.note(summary, resources = resources))
        assertEquals("Localized empty history", FocusHistoryText.today(summary, resources))
    }
}
