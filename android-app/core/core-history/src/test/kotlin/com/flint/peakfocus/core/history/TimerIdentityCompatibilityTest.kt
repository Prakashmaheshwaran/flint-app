package com.flint.peakfocus.core.history

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TimerIdentityCompatibilityTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `PC-17 legacy and v2 Timer ids preserve exact planned duration despite observation delay`() = runTest {
        val start = 1_790_000_000_000L
        val clock = Clock.fixed(Instant.ofEpochMilli(start + 765), ZoneOffset.UTC)
        for (prefix in listOf("session-timer-", "session-timer-v2-")) {
            val store = FocusHistoryStore(temporary.newFolder(), clock) { ZoneOffset.UTC }
            val id = "$prefix$start-uuid"
            HistoryReconciler(store, clock) { ZoneOffset.UTC }.observe(listOf(
                HistoryRule(id, "Focus timer", "easy", start + 300_000),
            ))
            val event = store.snapshots.value.events.single()
            assertEquals(id, event.session)
            assertEquals(start, event.at)
            assertEquals(300_000L, event.plannedEnd!! - event.at)
            assertEquals("manual", event.source)
        }
    }
}
