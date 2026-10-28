package com.flint.peakfocus.glance

import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class GlanceReadProjectionTest {
    @Test fun readFailuresAreUnavailableAndDoNotInventIdleOrHistoryZero() = runTest {
        val protection = object : GlanceProtectionReader {
            override val changes = flowOf(Unit)
            override suspend fun read(): ProtectionReading = throw IOException("fixture")
        }
        val history = object : GlanceFocusReader {
            override val changes = flowOf(Unit)
            override suspend fun read(): FocusTodayReading = throw IOException("fixture")
        }
        val result = readGlanceDisplay(protection, history, { 100_000 }, { 1_800_000_000_000 })
        assertEquals("Protection state unavailable", result.status)
        assertEquals("Focus history unavailable", result.focusedToday)
        assertNull(result.countdownEndElapsedMs)
    }

    @Test fun failedProtectionDoesNotEraseReadableHistory() = runTest {
        val epoch = 1_800_000_000_000L
        val day = Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()).toLocalDate()
        val history = object : GlanceFocusReader {
            override val changes = flowOf(Unit)
            override suspend fun read() = FocusTodayReading("5m", "In progress", day)
        }
        val result = readGlanceDisplay(UnavailableProtectionReader, history, { 100_000 }, { epoch })
        assertEquals("Protection state unavailable", result.status)
        assertEquals("5m", result.focusedToday)
        assertEquals("In progress", result.historyNote)
    }

    @Test fun stalledReadIsBoundedAndCallerCancellationStillPropagates() = runTest {
        assertEquals("unavailable", guardedGlanceRead({ "unavailable" }) { delay(10_000); "bad" })
        try {
            guardedGlanceRead({ "unavailable" }) { throw CancellationException("cancel") }
            fail("Caller cancellation must propagate")
        } catch (_: CancellationException) { }
    }
}
