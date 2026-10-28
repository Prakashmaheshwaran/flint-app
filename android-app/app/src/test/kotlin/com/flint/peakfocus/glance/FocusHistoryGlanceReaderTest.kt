package com.flint.peakfocus.glance

import com.flint.peakfocus.core.history.FocusDay
import com.flint.peakfocus.core.history.FocusSummary
import com.flint.peakfocus.core.history.HistoryAvailability
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FocusHistoryGlanceReaderTest {
    private val day = LocalDate.of(2027, 1, 15)
    private fun summary(availability: HistoryAvailability = HistoryAvailability.VALUE, ms: Long? = 300_000,
                        inProgress: Boolean = false, date: LocalDate = day) =
        FocusSummary(availability, emptyList(), listOf(FocusDay(date, ms, inProgress = inProgress)), 0)

    @Test fun canonicalFLabelsPreserveLoadingUnavailableEmptyZeroAndPartialProgress() {
        assertEquals("Loading focus history…", focusGlanceReading(null, day).text)
        assertEquals("Focus history unavailable", focusGlanceReading(summary(HistoryAvailability.UNAVAILABLE), day).text)
        assertEquals("No focus sessions yet", focusGlanceReading(summary(HistoryAvailability.EMPTY), day).text)
        assertEquals("0m", focusGlanceReading(summary(ms = 0), day).text)
        assertEquals("No data", focusGlanceReading(summary(ms = null), day).text)
        val partial = focusGlanceReading(summary(HistoryAvailability.DEGRADED, inProgress = true), day)
        assertEquals("5m", partial.text)
        assertEquals("In progress · Some history couldn't be read", partial.note)
    }

    @Test fun sourceDayIsPreservedSoYesterdayCannotBecomeToday() {
        val stale = focusGlanceReading(summary(date = day.minusDays(1)), day)
        assertEquals(day.minusDays(1), stale.day)
        val display = GlanceDisplayMapper.map(ProtectionReading(), stale, 100_000, 1_800_000_000_000, ZoneId.of("UTC"))
        assertEquals("Focus history unavailable", display.focusedToday)
        assertEquals("Open Flint to refresh", display.historyNote)
    }

    @Test fun summaryMetadataAndTicksWithoutVisibleChangesDoNotEmitWidgetUpdates() = runTest {
        val source = MutableStateFlow<FocusSummary?>(summary())
        val reader = FocusHistoryGlanceReader(source, backgroundScope) { day }
        var changes = 0
        reader.changes.onEach { changes++ }.launchIn(backgroundScope)
        runCurrent()
        val initial = changes
        source.value = summary().copy(streak = 42)
        runCurrent()
        assertEquals(initial, changes)
        source.value = summary(ms = 360_000)
        runCurrent()
        assertEquals(initial + 1, changes)
        assertEquals("6m", reader.read().text)
    }

    @Test fun publicReadFailureIsUnavailableNeverZeroOrAnException() = runTest {
        val source = flow<FocusSummary?> { throw IOException("fixture") }
        val reader = FocusHistoryGlanceReader(source, backgroundScope) { day }
        runCurrent()
        assertEquals("Focus history unavailable", reader.read().text)
    }
}
