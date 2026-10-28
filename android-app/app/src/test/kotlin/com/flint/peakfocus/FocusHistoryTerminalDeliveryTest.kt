package com.flint.peakfocus

import com.flint.peakfocus.core.history.FocusHistoryAggregator
import com.flint.peakfocus.core.history.FocusHistoryStore
import com.flint.peakfocus.core.history.HistoryEvent
import com.flint.peakfocus.core.history.SessionOutcome
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FocusHistoryTerminalDeliveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val start = 1_800_000_000_000L
    private val hour = 3_600_000L
    private val terminal = HistoryEvent(id = "owner-terminal", type = "completed", session = "session-late",
        at = start + hour, observedAt = start + 2 * hour, tz = "UTC", originRoute = "service", inferred = true)

    @Test fun failedHistoryAppendNeverAcknowledgesOwnerIntent() = runTest {
        var acknowledged = false
        assertFalse(projectHistoryTerminal(terminal, { false }) { acknowledged = true })
        assertFalse(acknowledged)
        assertTrue(projectHistoryTerminal(terminal, { true }) { acknowledged = true })
        assertTrue(acknowledged)
    }

    @Test fun committedHistorySurvivesFailedAckAndLateReplayWithoutDoubleCounting() = runTest {
        val clock = Clock.fixed(Instant.ofEpochMilli(start + 2 * hour), ZoneOffset.UTC)
        val store = FocusHistoryStore(temporary.newFolder(), clock)
        assertTrue(store.append(HistoryEvent(type = "started", session = terminal.session, at = start,
            plannedEnd = start + hour, tz = "UTC", name = "Focus", tier = "easy", source = "manual")))
        try {
            projectHistoryTerminal(terminal, store::append) { throw IOException("ack interrupted") }
            fail("Acknowledgement must fail without undoing the history commit")
        } catch (_: IOException) { }
        assertEquals(2, store.load().events.size)
        var acknowledged = false
        assertTrue(projectHistoryTerminal(terminal, store::append) { acknowledged = true })
        assertTrue(acknowledged)
        val events = store.load().events
        assertEquals(2, events.size)
        val session = FocusHistoryAggregator.sessions(events, clock.millis(), emptySet()).single()
        assertEquals(SessionOutcome.COMPLETED, session.outcome)
        assertEquals(hour, session.focusedMs)
    }

    @Test fun cancelledAppendLeavesOwnerIntentPending() = runTest {
        var acknowledged = false
        try {
            projectHistoryTerminal(terminal, { throw CancellationException("write cancelled") }) { acknowledged = true }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertFalse(acknowledged)
    }
}
