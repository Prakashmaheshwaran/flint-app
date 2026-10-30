package com.flint.peakfocus.core.history

import java.time.LocalDate
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HistoryDeliveryHealthTest {
    private val day = FocusDay(LocalDate.of(2026, 10, 28), 600_000, completed = 1)
    private val summary = FocusSummary(HistoryAvailability.VALUE, emptyList(), listOf(day), 1)

    @Test fun publicLossSignalAddsAHonestNoteWithoutChangingEvidencedTotals() = runBlocking {
        val health = HistoryDeliveryHealth()
        assertEquals(summary, health.applyTo(flowOf(summary)).first())
        health.noteEventsDropped()
        val result = health.applyTo(flowOf(summary)).first()!!
        assertEquals(HistoryAvailability.DEGRADED, result.availability)
        assertTrue(result.lifecycleEventsMissing)
        assertEquals(summary.days, result.days)
        assertEquals(summary.sessions, result.sessions)
        assertEquals(summary.streak, result.streak)
        assertEquals("Some focus events may be missing", FocusHistoryText.note(result))
    }

    @Test fun knownLossSurvivesFreshSnapshotsAndDoesNotInventAStartTimeOrEvents() = runBlocking {
        val health = HistoryDeliveryHealth()
        health.noteEventsDropped()
        val first = health.applyTo(flowOf(summary)).first()!!
        val later = health.applyTo(flowOf(summary.copy(streak = 2))).first()!!
        assertTrue(first.lifecycleEventsMissing && later.lifecycleEventsMissing)
        assertEquals(2, later.streak)
        assertEquals("Some focus events may be missing", FocusHistoryText.note(later))
        assertFalse(FocusHistoryText.note(later)!!.contains("since", ignoreCase = true))
    }

    @Test fun emptyHistoryWithDroppedEventsIsNotClaimedToBeCompleteOrUnreadable() = runBlocking {
        val health = HistoryDeliveryHealth()
        health.noteEventsDropped()
        val empty = summary.copy(availability = HistoryAvailability.EMPTY, days = emptyList(), streak = 0)
        val result = health.applyTo(flowOf(empty)).first()!!
        assertEquals(HistoryAvailability.DEGRADED, result.availability)
        assertEquals("No recorded focus sessions", FocusHistoryText.today(result))
        assertEquals("Some focus events may be missing", FocusHistoryText.note(result))
        assertTrue(result.sessions.isEmpty())
        assertTrue(result.days.isEmpty())
    }

    @Test fun lossDoesNotTurnLoadingOrUnreadableHistoryIntoInventedData() = runBlocking {
        val health = HistoryDeliveryHealth()
        health.noteEventsDropped()
        assertNull(health.applyTo(flowOf(null)).first())
        val result = health.applyTo(flowOf(summary.copy(availability = HistoryAvailability.UNAVAILABLE))).first()!!
        assertEquals(HistoryAvailability.UNAVAILABLE, result.availability)
        assertEquals("Focus history unavailable", FocusHistoryText.today(result))
    }
}
