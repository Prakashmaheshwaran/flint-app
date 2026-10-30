package com.flint.peakfocus.core.history

import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HistoryTimeConfidenceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val begin = 1_793_196_000_000L
    private val minute = 60_000L
    private val deadline = begin + 10 * minute
    private val uncertain = HistoryEventTimeConfidence.Uncertain("reboot")
    private val certain = HistoryEventTimeConfidence.Certain
    private fun event(type: String, at: Long, confidence: HistoryEventTimeConfidence? = null) =
        HistoryEvent(type = type, session = "session-a", at = at, tz = "UTC", timeConfidence = confidence)
    private fun start(confidence: HistoryEventTimeConfidence? = null) = event("started", begin, confidence)
        .copy(plannedEnd = deadline, tier = "easy", source = "manual", name = "Focus")
    private fun summary(events: List<HistoryEvent>, now: Long = begin + 180 * minute, active: Boolean = false) =
        FocusHistoryAggregator.summarize(HistorySnapshot(events, begin, true), now, ZoneOffset.UTC,
            if (active) setOf("session-a") else emptySet())
    private fun assertUnconfirmed(events: List<HistoryEvent>, credit: Long = 0, now: Long = begin + 180 * minute) {
        val result = summary(events, now)
        assertEquals(SessionOutcome.UNCONFIRMED, result.sessions.single().outcome)
        assertEquals(credit, result.sessions.single().focusedMs)
        assertEquals(0, result.days.sumOf { it.completed + it.endedEarly })
        assertEquals(1, result.days.sumOf { it.unconfirmed })
        assertEquals(0, result.streak)
    }

    @Test fun invertedBreakWindowCannotConfirmOrExtendPriorPrefix() {
        val pause = event("break_started", begin + 2 * minute).copy(grant = "g", breakEnd = begin + minute)
        assertUnconfirmed(listOf(start(), pause, event("completed", deadline)))
        assertUnconfirmed(listOf(start(), event("open_intercepted", begin + minute), pause,
            event("completed", deadline)), credit = minute)
        val snapshot = HistorySnapshot(listOf(start(), pause, event("completed", deadline)), begin, true)
        assertEquals(FocusCompletionDelivery.SUPPRESS, FocusCompletionPolicy.decision(snapshot, "session-a", deadline))
        assertTrue(FocusCompletionPolicy.alarms(snapshot, emptySet(), deadline).isEmpty())
    }

    @Test fun untrustedLaterDeadlineCannotEraseAnEarlierIndependentlyValidPrefix() {
        for (end in listOf(begin + 30_000, begin - minute)) {
            val revision = event("rescheduled", begin + 2 * minute, uncertain).copy(plannedEnd = end)
            val prefix = listOf(start(), event("open_intercepted", begin + minute))
            assertUnconfirmed(prefix + listOf(revision, event("completed", deadline)), credit = minute)
            assertUnconfirmed(prefix + listOf(revision.copy(timeConfidence = null),
                event("completed", deadline, certain)), credit = minute)
        }
    }

    private fun assertBackdatedBarrier(barrier: HistoryEvent, credit: Long = minute) {
        val witness = event("open_intercepted", begin + minute)
        val later = event("open_intercepted", begin + 3 * minute)
        val terminal = event("completed", deadline)
        val rows = listOf(start(), witness, barrier, later, terminal)
        assertUnconfirmed(rows, credit = credit, now = begin + 20 * minute)
        assertUnconfirmed(listOf(start(), barrier, later, terminal))
        assertUnconfirmed(listOf(start(), barrier, witness, later, terminal))
        val snapshot = HistorySnapshot(rows, begin, true)
        assertEquals(FocusCompletionDelivery.SUPPRESS, FocusCompletionPolicy.decision(snapshot, "session-a", deadline))
        assertTrue(FocusCompletionPolicy.alarms(snapshot, emptySet(), deadline).isEmpty())
    }

    @Test fun rejectedBackdatedPlansCannotReorderOrReplanEarlierRecordedWitness() {
        for (confidence in listOf(uncertain, null)) {
            assertBackdatedBarrier(event("rescheduled", begin + 30_000, confidence)
                .copy(plannedEnd = begin + 20_000, observedAt = begin + 2 * minute))
        }
    }

    @Test fun rejectedBackdatedRestorationsRetainRecordedPrefixWithoutLaterExtension() {
        assertBackdatedBarrier(event("protection_restored", begin + 30_000, uncertain).copy(observedAt = begin + 2 * minute))
        assertBackdatedBarrier(event("protection_restored", begin - minute).copy(observedAt = begin + 2 * minute))
    }

    @Test fun uncertainBackdatedCutsDiscountPrefixWhileInvalidLegacyCutsRemainBarriers() {
        val cuts = listOf(
            event("break_started", begin + 30_000, uncertain).copy(grant = "g", breakEnd = begin + 90_000),
            event("break_started", begin + 30_000).copy(grant = "g", breakEnd = begin + 20_000),
            event("break_started", begin - minute).copy(grant = "g", breakEnd = begin + 2 * minute),
            event("protection_degraded", begin + 30_000, uncertain),
            event("protection_degraded", begin - minute),
            event("pass_used", begin + 30_000, uncertain).copy(recoveryEnd = begin + 90_000),
            event("pass_used", begin + 30_000).copy(recoveryEnd = begin + 20_000),
            event("pass_used", begin - minute).copy(recoveryEnd = begin + 2 * minute),
        )
        for (cut in cuts) assertBackdatedBarrier(if (cut.knownType == HistoryEventType.PASS_USED)
            cut.copy(session = "operation", sessions = listOf("session-a"), observedAt = begin + 2 * minute)
            else cut.copy(observedAt = begin + 2 * minute), credit = if (cut.timeConfidence == uncertain) 0 else minute)
    }

    @Test fun acceptedLogicalCutsStillSubtractFromPrefixBeforeRejectedPlan() {
        val pass = event("pass_used", begin + 40_000).copy(session = "operation", sessions = listOf("session-a"),
            recoveryEnd = begin + 50_000)
        val rows = listOf(start(),
            event("break_ended", begin + 20_000).copy(grant = "g"),
            event("break_started", begin + 10_000).copy(grant = "g", breakEnd = begin + 25_000),
            event("protection_degraded", begin + 25_000), event("protection_restored", begin + 35_000), pass,
            event("open_intercepted", begin + minute))
        val plan = event("rescheduled", begin + 30_000).copy(plannedEnd = begin + 20_000, observedAt = begin + 2 * minute)
        assertUnconfirmed(rows + listOf(plan, event("open_intercepted", begin + 3 * minute), event("completed", deadline)),
            credit = 30_000)
        val completed = summary(rows + event("completed", deadline)).sessions.single()
        assertEquals(SessionOutcome.COMPLETED, completed.outcome)
        assertEquals(570_000L, completed.focusedMs)
    }

    @Test fun revisionAfterPriorExpiryCannotReviveOccurrenceOrCompletionDelivery() {
        for (confidence in listOf(null, certain)) {
            val revision = event("rescheduled", begin + 121 * minute, confidence)
                .copy(plannedEnd = begin + 8_000_000, observedAt = begin + 2 * minute)
            val rows = listOf(start(), event("open_intercepted", begin + minute), revision,
                event("completed", begin + 8_000_000, confidence))
            assertUnconfirmed(rows, credit = minute)
            assertUnconfirmed(rows, credit = minute, now = begin + 20 * minute)
            val snapshot = HistorySnapshot(rows, begin, true)
            assertEquals(FocusCompletionDelivery.SUPPRESS,
                FocusCompletionPolicy.decision(snapshot, "session-a", begin + 8_000_000))
            assertTrue(FocusCompletionPolicy.alarms(snapshot, emptySet(), begin + 8_000_000).isEmpty())
        }
        val boundary = event("rescheduled", deadline).copy(plannedEnd = begin + 20 * minute,
            observedAt = begin + 121 * minute)
        val valid = summary(listOf(start(), boundary, event("completed", begin + 20 * minute)), now = begin + minute)
        assertEquals(SessionOutcome.COMPLETED, valid.sessions.single().outcome)
        assertEquals(20 * minute, valid.sessions.single().focusedMs)
    }

    @Test fun onlyCertainMatchingOwnerFinalPlanCanRepairMissedExtension() {
        val at = begin + 8_000_000
        val plan = event("rescheduled", at, certain).copy(plannedEnd = at, originRoute = "editor_terminal", reason = "owner_final_plan")
        val end = event("completed", at, certain).copy(originRoute = "editor_terminal")
        val repaired = summary(listOf(start(), plan, end))
        assertEquals(SessionOutcome.COMPLETED, repaired.sessions.single().outcome)
        assertEquals(8_000_000L, repaired.sessions.single().focusedMs)
        val observer = event("rescheduled", begin + 700_000).copy(plannedEnd = at, observedAt = begin + 12_000_000)
        for (rows in listOf(listOf(start(), observer, plan, end), listOf(start(), plan, end, observer))) {
            val recovered = summary(rows)
            assertEquals(SessionOutcome.COMPLETED, recovered.sessions.single().outcome)
            assertEquals(8_000_000L, recovered.sessions.single().focusedMs)
        }
        val prefix = listOf(start(), event("open_intercepted", begin + minute))
        assertUnconfirmed(prefix + listOf(observer.copy(timeConfidence = uncertain), plan, end), credit = minute)
        assertUnconfirmed(listOf(start(uncertain), plan, end))
        assertUnconfirmed(prefix + listOf(event("protection_degraded", begin + 2 * minute, uncertain), plan, end))
        for ((revision, terminal) in listOf(
            plan.copy(reason = null) to end,
            plan.copy(originRoute = null) to end,
            plan.copy(timeConfidence = null) to end,
            plan to end.copy(timeConfidence = uncertain),
            plan to end.copy(originRoute = null),
            plan to end.copy(at = at + 1),
            plan.copy(timeConfidence = uncertain) to end.copy(timeConfidence = uncertain),
        )) assertUnconfirmed(prefix + listOf(revision, terminal), credit = minute)
    }

    @Test fun equalObservationPlanTiesUseAppendRevisionOrderAndSameNotificationDeadline() {
        val observation = begin + 121 * minute
        val first = event("rescheduled", begin + 9 * minute).copy(plannedEnd = begin + 20 * minute, observedAt = observation)
        val last = event("rescheduled", begin + 5 * minute).copy(plannedEnd = deadline, observedAt = observation)
        val completion = event("completed", begin + 20 * minute)
        assertUnconfirmed(listOf(start(), first, last, completion))
        val valid = listOf(start(), first.copy(plannedEnd = begin + 9 * minute),
            last.copy(plannedEnd = begin + 20 * minute), completion)
        assertEquals(SessionOutcome.COMPLETED, summary(valid).sessions.single().outcome)
        assertEquals(20 * minute, summary(valid).sessions.single().focusedMs)
        val snapshot = HistorySnapshot(valid, begin, true)
        assertEquals(FocusCompletionDelivery.WAIT, FocusCompletionPolicy.decision(snapshot, "session-a", begin + 15 * minute))
        assertEquals(listOf(FocusCompletionAlarm("session-a", begin + 20 * minute)),
            FocusCompletionPolicy.alarms(snapshot, emptySet(), begin + 15 * minute))
    }

    @Test fun rejectedTerminalCannotMoveUnconfirmedCountOutsideVisibleDay() {
        for (offset in listOf(1561 * minute, -10 * 24 * 60 * minute)) {
            val result = summary(listOf(start(), event("ended_early", begin + offset).copy(route = "manual_stop")),
                now = begin + 20 * minute)
            assertEquals(SessionOutcome.UNCONFIRMED, result.sessions.single().outcome)
            assertEquals(deadline, result.sessions.single().end)
            assertEquals(1, result.days.last().unconfirmed)
            assertEquals(0L, result.sessions.single().focusedMs)
        }
        val result = summary(listOf(start(uncertain).copy(plannedEnd = begin + 2 * 24 * 60 * minute)),
            now = begin + 20 * minute)
        assertEquals(begin + 20 * minute, result.sessions.single().end)
        assertEquals(1, result.days.last().unconfirmed)
    }

    @Test(timeout = 5000) fun extremeReadRecordsCannotCreateUnboundedDaySplittingOrOverflow() {
        for (extreme in listOf(Long.MIN_VALUE, Long.MAX_VALUE)) {
            val records = listOf(start().copy(plannedEnd = extreme), event("completed", extreme))
                .map { HistoryCodec.decode(HistoryCodec.encode(it)) }
            assertUnconfirmed(records, now = begin + minute)
        }
        // A supported long-lived interval is still accepted after reader rollback, but
        // only the requested seven dates are bucketed rather than every intervening day.
        val distant = 253_402_300_799_000L
        val result = summary(listOf(start().copy(plannedEnd = distant), event("completed", distant)), now = begin + minute)
        assertEquals(SessionOutcome.COMPLETED, result.sessions.single().outcome)
        assertEquals(distant - begin, result.sessions.single().focusedMs)
        assertEquals(7, result.days.size)
        assertTrue(result.days.sumOf { it.focusedMs ?: 0 } <= 7 * 24 * 60 * minute)
        assertTrue(result.days.all { (it.focusedMs ?: 0) >= 0 })
    }

    @Test fun oldAbsentAndNullConfidenceFixturesRemainLegacyAndRoundTrip() {
        val old = """{"v":1,"id":"old","type":"completed","session":"session-a","at":$deadline,"tz":"UTC","future":{"fraction":1.20}}"""
        for (line in listOf(old, old.dropLast(1) + ",\"timeConfidence\":null}")) {
            val decoded = HistoryCodec.decode(line)
            assertNull(decoded.timeConfidence)
            assertEquals(line, HistoryCodec.encode(decoded))
            assertEquals(SessionOutcome.COMPLETED, summary(listOf(start(), decoded)).sessions.single().outcome)
        }
        assertFalse(HistoryCodec.encode(start()).contains("timeConfidence"))
    }

    @Test fun certainAndUncertainFixturesAreAdditiveAndPreserveFutureFields() {
        for ((json, expected) in listOf(
            """{"kind":"certain","future":[1,2]}""" to certain,
            """{"kind":"uncertain","reason":"boot\\clock","future":{"fraction":1.20}}""" to
                HistoryEventTimeConfidence.Uncertain("boot\\clock"),
        )) {
            val line = """{"v":1,"id":"new","type":"completed","session":"session-a","at":$deadline,"tz":"UTC","timeConfidence":$json}"""
            val decoded = HistoryCodec.decode(line)
            assertEquals(expected, decoded.timeConfidence)
            assertEquals(line, HistoryCodec.encode(decoded))
            assertEquals(expected, HistoryCodec.decode(HistoryCodec.encode(event("completed", deadline, expected))).timeConfidence)
            assertEquals(1, decoded.v)
        }
        assertTrue(HistoryCodec.encode(event("completed", deadline, certain)).contains("\"timeConfidence\":{\"kind\":\"certain\"}"))
    }

    @Test fun futureAndMalformedConfidenceNeverDowngradeToLegacyCertainty() {
        for (value in listOf("""{"kind":"future","reason":"new"}""", """{"kind":"uncertain"}""",
            """{"kind":"uncertain","reason":1}""", "{}", "false", "[]", "\"certain\"")) {
            val line = """{"v":1,"id":"future","type":"completed","session":"session-a","at":$deadline,"tz":"UTC","timeConfidence":$value}"""
            val decoded = HistoryCodec.decode(line)
            assertEquals(HistoryEventTimeConfidence.Uncertain("unsupported_time_confidence"), decoded.timeConfidence)
            assertEquals(line, HistoryCodec.encode(decoded))
            assertUnconfirmed(listOf(start(), decoded))
        }
    }

    @Test fun confidenceAndOpaqueFutureFieldsSurviveCompactionAndReopen() = runTest {
        val dir = temporary.newFolder()
        val clock = Clock.fixed(Instant.ofEpochMilli(deadline), ZoneOffset.UTC)
        val store = FocusHistoryStore(dir, clock)
        assertTrue(store.append(start()))
        val line = HistoryCodec.encode(event("completed", deadline, uncertain)).dropLast(1) +
            ",\"future\":{\"fraction\":1.20}}"
        assertTrue(store.append(HistoryCodec.decode(line)))
        assertTrue(store.compact())
        val reopened = FocusHistoryStore(dir, clock).load()
        assertEquals(line, HistoryCodec.encode(reopened.events.last()))
        assertTrue(File(dir, "events.v1.jsonl").readText().contains(line))
        assertUnconfirmed(reopened.events)
    }

    @Test fun forwardJumpWitnessCannotTurnTwoRealMinutesIntoTen() {
        for (type in listOf("open_intercepted", "break_started", "break_ended", "protection_degraded", "protection_restored")) {
            for (inferred in listOf(false, true)) for (observed in listOf(null, begin + 2 * minute)) {
                val jump = event(type, begin + 121 * minute).copy(inferred = inferred, observedAt = observed,
                    breakEnd = begin + 125 * minute)
                assertUnconfirmed(listOf(start(), jump))
                val earlier = event("open_intercepted", begin + minute)
                assertUnconfirmed(listOf(start(), earlier, jump), minute)
            }
        }
    }

    @Test fun rawEarlyTerminalsAfterDeadlineCannotBeClampedIntoACompletedPlan() {
        for (confidence in listOf(null, certain, uncertain)) {
            val end = event("ended_early", begin + 121 * minute, confidence).copy(route = "manual_stop")
            assertUnconfirmed(listOf(start(), end))
            assertUnconfirmed(listOf(start(), event("open_intercepted", begin + 2 * minute), end), 2 * minute)
        }
    }

    @Test fun terminalAtOrBeforeActualStartNeverCreatesAnOutcomeOrPrefix() {
        for (type in listOf("completed", "ended_early", "superseded")) for (offset in listOf(-minute, 0L)) {
            val end = event(type, begin + offset).copy(route = "manual_stop", by = "next")
            assertUnconfirmed(listOf(start(), end))
            assertUnconfirmed(listOf(start(), event("open_intercepted", begin + minute), end), minute)
        }
    }

    @Test fun completedBeforeDeadlineIsUnconfirmedForLegacyAndCertainRecords() {
        for (confidence in listOf(null, certain, uncertain)) {
            assertUnconfirmed(listOf(start(), event("completed", begin + 2 * minute, confidence)))
        }
    }

    @Test fun backwardWitnessOrNonpositivePlanCannotCreateCreditOrCompletion() {
        assertUnconfirmed(listOf(start(), event("open_intercepted", begin - minute), event("completed", deadline)))
        for (end in listOf(begin - minute, begin)) {
            assertUnconfirmed(listOf(start().copy(plannedEnd = end), event("open_intercepted", begin + minute),
                event("completed", deadline)))
        }
        assertUnconfirmed(listOf(start(), event("rescheduled", begin - minute).copy(plannedEnd = deadline),
            event("open_intercepted", begin + minute), event("completed", deadline)))
    }

    @Test fun explicitUncertainTerminalPreservesOnlyPriorTrustedCoverage() {
        for (type in listOf("completed", "ended_early", "superseded")) {
            val at = if (type == "completed") deadline else begin + 5 * minute
            val end = event(type, at, uncertain).copy(route = "manual_stop", by = "next")
            assertUnconfirmed(listOf(start(), end))
            assertUnconfirmed(listOf(start(), event("open_intercepted", begin + 2 * minute), end), 2 * minute)
        }
    }

    @Test fun uncertainStartOrPlanCannotCreateAPrefix() {
        assertUnconfirmed(listOf(start(uncertain), event("open_intercepted", begin + 2 * minute), event("completed", deadline)))
        assertUnconfirmed(listOf(start(), event("rescheduled", begin + minute, uncertain).copy(plannedEnd = deadline),
            event("open_intercepted", begin + 2 * minute), event("completed", deadline)))
    }

    @Test fun uncertainTransitionStopsLaterWitnessesAndTerminalExtendingPriorCredit() {
        for (confidence in listOf(uncertain, null)) {
            val gap = event("protection_degraded", begin + 3 * minute, confidence)
                .copy(reason = if (confidence == null) "time_uncertain" else "permission")
            assertUnconfirmed(listOf(start(), event("open_intercepted", begin + 2 * minute), gap,
                event("protection_restored", begin + 4 * minute), event("completed", deadline)))
        }
    }

    @Test fun legacyTimeUncertainTerminalFailsClosedEvenWithInferredAndLateObservation() {
        assertUnconfirmed(listOf(start(), event("completed", deadline).copy(reason = "time_uncertain",
            inferred = true, observedAt = begin + 121 * minute)))
    }

    @Test fun validLateLogicalCompletionsKeepInferredAndCertainSemantics() {
        for (confidence in listOf(null, certain)) for (inferred in listOf(false, true)) {
            val terminal = event("completed", deadline, confidence).copy(inferred = inferred, observedAt = begin + 121 * minute)
            val result = summary(listOf(start(), terminal))
            assertEquals(SessionOutcome.COMPLETED, result.sessions.single().outcome)
            assertEquals(10 * minute, result.sessions.single().focusedMs)
            assertEquals(1, result.days.sumOf { it.completed })
            assertEquals(1, result.streak)
        }
        assertUnconfirmed(listOf(start(), event("completed", begin + 121 * minute)))
        val certainLate = summary(listOf(start(), event("completed", begin + 121 * minute, certain)))
        assertEquals(SessionOutcome.COMPLETED, certainLate.sessions.single().outcome)
        assertEquals(10 * minute, certainLate.sessions.single().focusedMs)
    }

    @Test fun readerClockRollbackCannotInvalidateCommittedLogicalTerminal() {
        val result = summary(listOf(start(), event("completed", deadline).copy(inferred = true, observedAt = deadline + minute)),
            now = begin + minute)
        assertEquals(SessionOutcome.COMPLETED, result.sessions.single().outcome)
        assertEquals(10 * minute, result.sessions.single().focusedMs)
    }

    @Test fun legacyAndCertainEarlyEndsAndExactDeadlineTiesRemainValid() {
        for (confidence in listOf(null, certain)) for (duration in listOf(2L, 10L)) {
            val end = event("ended_early", begin + duration * minute, confidence).copy(route = "manual_stop")
            val result = summary(listOf(start(), end))
            assertEquals(SessionOutcome.ENDED_EARLY, result.sessions.single().outcome)
            assertEquals(duration * minute, result.sessions.single().focusedMs)
            assertEquals(1, result.days.sumOf { it.endedEarly })
        }
    }

    @Test fun rescheduledLogicalTimeAndObservedTimeCanDifferWithoutTaintingCompletion() {
        val events = listOf(start(),
            event("rescheduled", begin + 2 * minute).copy(plannedEnd = begin + 20 * minute, observedAt = begin + 3 * minute),
            event("rescheduled", begin + 15 * minute).copy(plannedEnd = begin + 15 * minute,
                observedAt = begin + 121 * minute, inferred = true),
            event("completed", begin + 15 * minute).copy(observedAt = begin + 121 * minute, inferred = true))
        val result = summary(events).sessions.single()
        assertEquals(SessionOutcome.COMPLETED, result.outcome)
        assertEquals(15 * minute, result.focusedMs)
    }

    @Test fun durableBreakReplayUsesLogicalPairingRatherThanAppendOrder() {
        val breakStart = event("break_started", begin + 2 * minute).copy(grant = "g", breakEnd = begin + 5 * minute)
        val breakEnd = event("break_ended", begin + 4 * minute).copy(grant = "g")
        for (phases in listOf(listOf(breakStart, breakEnd), listOf(breakEnd, breakStart))) {
            val result = summary(listOf(start()) + phases + event("completed", deadline)).sessions.single()
            assertEquals(SessionOutcome.COMPLETED, result.outcome)
            assertEquals(8 * minute, result.focusedMs)
        }
        assertUnconfirmed(listOf(start(), breakEnd.copy(at = begin + minute), breakStart, event("completed", deadline)))
        assertUnconfirmed(listOf(start(), breakEnd.copy(grant = "wrong"), breakStart, event("completed", deadline)), 2 * minute)
    }

    @Test fun lateUncertainBreakCleanupDoesNotTaintPriorTerminal() {
        val result = summary(listOf(start(), event("completed", deadline),
            event("break_ended", begin + 121 * minute, uncertain))).sessions.single()
        assertEquals(SessionOutcome.COMPLETED, result.outcome)
        assertEquals(10 * minute, result.focusedMs)
    }

    @Test fun uncertainPassCannotMakeAffectedSessionLookCompleted() {
        val pass = event("pass_used", begin + 3 * minute, uncertain).copy(session = "operation",
            sessions = listOf("session-a"), recoveryEnd = begin + 4 * minute)
        assertUnconfirmed(listOf(start(), event("open_intercepted", begin + 2 * minute), pass,
            event("completed", deadline)))
    }

    @Test fun plausibleLegacyWitnessesAndRunningHistoryKeepOrdinaryCredit() {
        assertUnconfirmed(listOf(start(), event("open_intercepted", begin + 2 * minute)), 2 * minute)
        val running = summary(listOf(start()), now = begin + 2 * minute, active = true).sessions.single()
        assertEquals(SessionOutcome.RUNNING, running.outcome)
        assertEquals(2 * minute, running.focusedMs)
    }
}
