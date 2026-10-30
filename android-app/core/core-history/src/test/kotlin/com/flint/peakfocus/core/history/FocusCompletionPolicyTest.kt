package com.flint.peakfocus.core.history

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FocusCompletionPolicyTest {
    @get:Rule val temporary = TemporaryFolder()
    private val start = 1_793_188_800_000L
    private val end = start + 300_000
    private fun started() = HistoryEvent(type = "started", session = "session-a", at = start, tz = "UTC",
        plannedEnd = end, tier = "easy", source = "manual", name = "Focus")
    private fun terminal(type: HistoryEventType, at: Long = end) = HistoryEvent(type = type.wireName,
        session = "session-a", at = at, tz = "UTC", route = "unknown", by = "session-b")
    private fun snapshot(vararg events: HistoryEvent) = HistorySnapshot(events.toList(), start, true)

    @Test fun futureStartsScheduleAndEarlyOrSupersededEndsCancel() {
        val log = snapshot(started())
        assertEquals(listOf(FocusCompletionAlarm("session-a", end)), FocusCompletionPolicy.alarms(log, emptySet(), start))
        for (type in listOf(HistoryEventType.ENDED_EARLY, HistoryEventType.SUPERSEDED)) {
            assertTrue(FocusCompletionPolicy.alarms(snapshot(started(), terminal(type, end - 1)), emptySet(), start).isEmpty())
        }
    }
    @Test fun completedBeforeReceiverStillPostsAndFirstTerminalWins() {
        val natural = terminal(HistoryEventType.COMPLETED)
        assertEquals(FocusCompletionDelivery.POST, FocusCompletionPolicy.decision(snapshot(started(), natural), "session-a", end))
        assertEquals(FocusCompletionDelivery.POST, FocusCompletionPolicy.decision(snapshot(started(), natural, terminal(HistoryEventType.ENDED_EARLY, end + 1)), "session-a", end + 1))
        assertEquals(FocusCompletionDelivery.SUPPRESS, FocusCompletionPolicy.decision(snapshot(started(), terminal(HistoryEventType.ENDED_EARLY, end - 1), natural), "session-a", end))
    }
    @Test fun dueWindowHonorsThirtyMinuteBoundaryAndSuppressesOldRearm() {
        val log = snapshot(started())
        assertEquals(FocusCompletionDelivery.WAIT, FocusCompletionPolicy.decision(log, "session-a", end - 1))
        assertEquals(FocusCompletionDelivery.POST, FocusCompletionPolicy.decision(log, "session-a", end + FocusCompletionPolicy.MAX_LATENESS_MS))
        assertEquals(FocusCompletionDelivery.STALE, FocusCompletionPolicy.decision(log, "session-a", end + FocusCompletionPolicy.MAX_LATENESS_MS + 1))
        assertTrue(FocusCompletionPolicy.alarms(log, emptySet(), end + FocusCompletionPolicy.MAX_LATENESS_MS + 1).isEmpty())
        assertTrue(FocusCompletionPolicy.alarms(log, setOf("session-a"), start).isEmpty())
    }
    @Test fun corruptMissingOpenEndedAndUnknownRecordsCannotNotify() {
        val log = snapshot(started())
        assertEquals(FocusCompletionDelivery.SUPPRESS, FocusCompletionPolicy.decision(log.copy(degraded = true), "session-a", end))
        assertEquals(FocusCompletionDelivery.SUPPRESS, FocusCompletionPolicy.decision(snapshot(started().copy(plannedEnd = null)), "session-a", end))
        assertEquals(FocusCompletionDelivery.SUPPRESS, FocusCompletionPolicy.decision(snapshot(started().copy(v = 2)), "session-a", end))
        assertEquals(FocusCompletionDelivery.SUPPRESS, FocusCompletionPolicy.decision(log, "missing", end))
    }
    @Test fun FH20_reschedulesReplaceDeadlineAndOpenEndedEditsCancel() {
        fun edit(deadline: Long?) = HistoryEvent(type = "rescheduled", session = "session-a", at = start + 60_000,
            tz = "UTC", plannedEnd = deadline)
        val extended = snapshot(started(), edit(end + 300_000))
        assertEquals(listOf(FocusCompletionAlarm("session-a", end + 300_000)), FocusCompletionPolicy.alarms(extended, emptySet(), end))
        assertEquals(FocusCompletionDelivery.WAIT, FocusCompletionPolicy.decision(extended, "session-a", end))
        val shortened = snapshot(started(), edit(start + 120_000))
        assertEquals(listOf(FocusCompletionAlarm("session-a", start + 120_000)), FocusCompletionPolicy.alarms(shortened, emptySet(), start + 120_000))
        assertTrue(FocusCompletionPolicy.alarms(snapshot(started(), edit(null)), emptySet(), end).isEmpty())
    }
    @Test fun FH20_recoveredSameTimestampPlanPrecedesCompletionAndLaterEditsCannotReviveTerminal() {
        val deadline = end + 300_000
        val plan = HistoryEvent(type = "rescheduled", session = "session-a", at = deadline, tz = "UTC", plannedEnd = deadline, inferred = true)
        val natural = terminal(HistoryEventType.COMPLETED, deadline)
        assertEquals(FocusCompletionDelivery.POST, FocusCompletionPolicy.decision(snapshot(started(), plan, natural), "session-a", deadline))
        val stopped = terminal(HistoryEventType.ENDED_EARLY, start + 60_000)
        assertTrue(FocusCompletionPolicy.alarms(snapshot(started(), stopped, plan), emptySet(), deadline).isEmpty())
    }
    @Test fun FH20_receiverRechecksExtendedAndOpenEndedPlansBeforePosting() = runTest {
        var posts = 0
        val edited = HistoryEvent(type = "rescheduled", session = "session-a", at = start + 1, tz = "UTC", plannedEnd = end + 300_000)
        var history = snapshot(started(), edited)
        val state = FakeState()
        val service = FocusCompletionDeliveryService(state, { history }, { true }) { posts++ }
        assertEquals(FocusCompletionDelivery.WAIT, service.deliver("session-a", end))
        assertFalse(state.wasDelivered("session-a")); assertEquals(0, posts)
        history = snapshot(started(), edited.copy(plannedEnd = null))
        assertEquals(FocusCompletionDelivery.SUPPRESS, service.deliver("session-a", end + 300_000))
        assertFalse(state.wasDelivered("session-a")); assertEquals(0, posts)
        history = snapshot(started(), edited, terminal(HistoryEventType.COMPLETED, end + 300_000))
        service.deliver("session-a", end + 300_000)
        assertEquals(1, posts)
    }
    @Test fun receiverWorkPostsOnceAndDeniedPermissionNeverPromptsOrPostsLater() = runTest {
        var count = 0
        val state = FakeState()
        val service = FocusCompletionDeliveryService(state, { snapshot(started(), terminal(HistoryEventType.COMPLETED)) }, { true }) { count++ }
        service.deliver("session-a", end); service.deliver("session-a", end)
        assertEquals(1, count)
        val denied = FakeState()
        FocusCompletionDeliveryService(denied, { snapshot(started()) }, { false }) { count++ }.deliver("session-a", end)
        FocusCompletionDeliveryService(denied, { snapshot(started()) }, { true }) { count++ }.deliver("session-a", end + 1)
        assertEquals(1, count)
    }
    @Test fun staleIsMarkedDeliveredAndFailedPersistenceSuppressesPost() = runTest {
        var count = 0; val stale = FakeState()
        FocusCompletionDeliveryService(stale, { snapshot(started()) }, { true }) { count++ }
            .deliver("session-a", end + FocusCompletionPolicy.MAX_LATENESS_MS + 1)
        assertTrue(stale.wasDelivered("session-a")); assertEquals(0, count)
        val failed = FakeState(fail = true)
        FocusCompletionDeliveryService(failed, { snapshot(started()) }, { true }) { count++ }.deliver("session-a", end)
        assertEquals(0, count)
    }
    @Test fun deliveryMarkersAndAlarmRegistrySurviveRestartAndRemainSeparateFromClear() = runTest {
        val file = File(temporary.newFolder(), "notification-state.v1.json")
        val state = FocusNotificationState(file)
        state.setScheduled(mapOf("session-a" to end))
        assertEquals(mapOf("session-a" to end), FocusNotificationState(file).scheduled())
        assertTrue(state.markDelivered("session-a", end))
        assertTrue(FocusNotificationState(file).wasDelivered("session-a"))
        assertFalse(FocusNotificationState(file).markDelivered("session-a", end))
        assertTrue(FocusNotificationState(file).scheduled().isEmpty())
        file.writeText("corrupt")
        assertThrows(Exception::class.java) { kotlinx.coroutines.runBlocking { FocusNotificationState(file).wasDelivered("session-a") } }
    }
    @Test fun uncertainOrImplausibleCompletedRowsCannotScheduleOrDeliver() = runTest {
        var posts = 0
        for (completion in listOf(
            terminal(HistoryEventType.COMPLETED).copy(timeConfidence = HistoryEventTimeConfidence.Uncertain("reboot")),
            terminal(HistoryEventType.COMPLETED).copy(reason = "time_uncertain", inferred = true),
            terminal(HistoryEventType.COMPLETED, start),
            terminal(HistoryEventType.COMPLETED, start - 1),
            terminal(HistoryEventType.COMPLETED, end - 1),
            terminal(HistoryEventType.COMPLETED, end + 1),
        )) {
            val log = snapshot(started(), completion)
            val state = FakeState()
            assertEquals(FocusCompletionDelivery.SUPPRESS, FocusCompletionPolicy.decision(log, "session-a", end))
            assertTrue(FocusCompletionPolicy.alarms(log, emptySet(), end).isEmpty())
            val service = FocusCompletionDeliveryService(state, { log }, { true }) { posts++ }
            assertEquals(FocusCompletionDelivery.SUPPRESS, service.deliver("session-a", end))
            assertFalse(state.wasDelivered("session-a"))
        }
        assertEquals(0, posts)
    }
    @Test fun uncertainStartPhaseAndReferencedPassSuppressCompletedDelivery() {
        val uncertainty = HistoryEventTimeConfidence.Uncertain("clock")
        val gap = HistoryEvent(type = "protection_degraded", session = "session-a", at = start + 1,
            tz = "UTC", timeConfidence = uncertainty)
        val pass = gap.copy(type = "pass_used", session = "operation", sessions = listOf("session-a"), recoveryEnd = end)
        for (log in listOf(snapshot(started().copy(timeConfidence = uncertainty), terminal(HistoryEventType.COMPLETED)),
            snapshot(started(), gap, terminal(HistoryEventType.COMPLETED)),
            snapshot(started(), pass, terminal(HistoryEventType.COMPLETED)))) {
            assertEquals(FocusCompletionDelivery.SUPPRESS, FocusCompletionPolicy.decision(log, "session-a", end))
            assertTrue(FocusCompletionPolicy.alarms(log, emptySet(), end).isEmpty())
        }
    }
    @Test fun inferredOnlyAndCertainLogicalCompletionsRemainDeliverable() {
        for (confidence in listOf(null, HistoryEventTimeConfidence.Certain)) {
            val completed = terminal(HistoryEventType.COMPLETED).copy(inferred = true, observedAt = end + 60_000,
                timeConfidence = confidence)
            val log = snapshot(started(), completed)
            assertEquals(FocusCompletionDelivery.POST, FocusCompletionPolicy.decision(log, "session-a", end + 60_000))
            assertEquals(FocusCompletionDelivery.WAIT, FocusCompletionPolicy.decision(log, "session-a", start + 1))
        }
        val certainLate = terminal(HistoryEventType.COMPLETED, end + 1).copy(timeConfidence = HistoryEventTimeConfidence.Certain)
        assertEquals(FocusCompletionDelivery.POST, FocusCompletionPolicy.decision(snapshot(started(), certainLate), "session-a", end + 1))
    }
    private class FakeState(private val fail: Boolean = false) : FocusCompletionDeliveryState {
        val delivered = mutableSetOf<String>()
        override suspend fun wasDelivered(sessionId: String) = sessionId in delivered
        override suspend fun markDelivered(sessionId: String, at: Long) = !fail && delivered.add(sessionId)
    }
}
