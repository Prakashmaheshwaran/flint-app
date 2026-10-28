package com.flint.peakfocus.feature.blocklist

import com.flint.peakfocus.core.history.*
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*

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
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun dismissedCompletionStaysDismissedAcrossNewViewModelInstance() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val models = ViewModelStore()
        try {
            val storage = object : CompletionDismissalStore {
                var value: String? = null
                override suspend fun read() = value
                override suspend fun write(id: String): Boolean { value = id; return true }
            }
            val history = MutableStateFlow<FocusSummary?>(summary(session()))
            val first = TimerHistoryViewModel(history, storage, { now }, { zone })
            models.put("first", first)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { first.state.collect {} }
            runCurrent(); assertNotNull(first.state.value.completion)
            first.dismissCompletion("a"); runCurrent(); assertNull(first.state.value.completion)
            val second = TimerHistoryViewModel(history, storage, { now }, { zone })
            models.put("second", second)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { second.state.collect {} }
            runCurrent(); assertNull(second.state.value.completion)
            assertEquals("a", storage.value)
            history.value = summary(session("b", end = now + 1000))
            runCurrent(); assertEquals("b", second.state.value.completion?.id)
        } finally { models.clear(); runCurrent(); Dispatchers.resetMain() }
    }
}
