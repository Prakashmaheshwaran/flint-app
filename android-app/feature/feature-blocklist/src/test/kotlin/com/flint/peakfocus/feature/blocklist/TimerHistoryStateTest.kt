package com.flint.peakfocus.feature.blocklist

import com.flint.peakfocus.core.history.*
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class TimerHistoryStateTest {
    private val now = Instant.parse("2026-10-28T14:00:00Z").toEpochMilli()
    private val zone = ZoneId.of("America/New_York")
    private fun session(id: String = "a", end: Long = now, recorded: Boolean = true, outcome: SessionOutcome = SessionOutcome.COMPLETED) =
        FocusSession(id, "Deep study", end - 300_000, end, end, outcome, false,
            listOf(FocusInterval(end - 300_000, end)), null, null, recorded)
    private fun summary(vararg sessions: FocusSession) = FocusSummary(HistoryAvailability.VALUE, sessions.toList(), emptyList(), 0)

    @Test fun completionRequiresDurableTerminalAndRespectsDismissal() {
        assertNull(completionFromHistory(summary(session(recorded = false)), null, now, zone))
        assertNotNull(completionFromHistory(summary(session()), null, now, zone))
        assertNull(completionFromHistory(summary(session()), "a", now, zone))
        assertNull(completionFromHistory(summary(session(outcome = SessionOutcome.ENDED_EARLY)), null, now, zone))
    }
    @Test fun oldOrReplacedCompletionDoesNotReappearAfterEarlyEnd() {
        assertNull(completionFromHistory(summary(session(end = now - 86_400_000)), null, now, zone))
        assertNull(completionFromHistory(summary(session(end = now - 1000), session("b", outcome = SessionOutcome.ENDED_EARLY)), null, now, zone))
        assertNotNull(completionFromHistory(summary(session(), session("b", end = now + 1000)), "a", now + 1000, zone))
    }
}
