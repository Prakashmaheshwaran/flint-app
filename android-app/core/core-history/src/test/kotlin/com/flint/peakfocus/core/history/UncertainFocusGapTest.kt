package com.flint.peakfocus.core.history

import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class UncertainFocusGapTest {
    private val minute = 60_000L
    private val begin = Instant.parse("2026-10-28T23:50:00Z").toEpochMilli()
    private val uncertain = HistoryEventTimeConfidence.Uncertain("same_boot_drift")
    private fun event(type: String, minutes: Long, session: String = "session-a") =
        HistoryEvent(type = type, session = session, at = begin + minutes * minute, tz = "UTC",
            timeConfidence = HistoryEventTimeConfidence.Certain)
    private fun start(session: String = "session-a") = event("started", 0, session)
        .copy(plannedEnd = begin + 20 * minute, tier = "easy", source = "manual", name = "Focus")
    private fun pause(kind: String? = "ordinary", at: Long = 10, until: Long? = 15) =
        event("break_started", at).copy(timeConfidence = uncertain, kind = kind, grant = "g",
            breakEnd = until?.let { begin + it * minute }, observedAt = begin + 180 * minute)
    private fun rows(gaps: List<HistoryEvent>, anchors: List<Long> = listOf(20)) =
        listOf(start()) + anchors.map { event("open_intercepted", it) } + gaps + event("completed", 20)
    private fun summary(rows: List<HistoryEvent>) = FocusHistoryAggregator.summarize(
        HistorySnapshot(rows, begin, true), begin + 30 * minute, ZoneOffset.UTC, emptySet())
    private fun assertUnconfirmed(rows: List<HistoryEvent>, minutes: Long) {
        val result = summary(rows)
        assertEquals(SessionOutcome.UNCONFIRMED, result.sessions.single().outcome)
        assertEquals(minutes * minute, result.sessions.single().focusedMs)
        assertEquals(1, result.days.sumOf { it.unconfirmed })
        assertEquals(0, result.days.sumOf { it.completed + it.endedEarly })
        assertEquals(0, result.streak)
    }

    @Test fun lateUncertainOrdinaryBreakDiscountsTwentyMinuteWitnessWithoutInventingPlacement() {
        val gap = pause()
        assertUnconfirmed(rows(listOf(gap)), 15)
        assertEquals(uncertain, gap.timeConfidence)
        assertEquals(begin + 180 * minute, gap.observedAt)
    }

    @Test fun plannedRestWithoutTrustedLengthUsesWholeCertainAnchorBracket() {
        assertUnconfirmed(rows(listOf(pause("planned")), anchors = listOf(8, 17, 20)), 11)
    }

    @Test fun unboundedCoverageGapAndUnplaceableBreakCannotInventFocus() {
        assertUnconfirmed(rows(listOf(event("protection_degraded", 10).copy(timeConfidence = uncertain))), 0)
        assertUnconfirmed(rows(listOf(pause("planned", at = 120, until = null))), 0)
    }

    @Test fun uncertainGlobalPassDiscountsEachReferencedSessionAndNeverCountsCompletion() {
        val pass = event("pass_used", 10, "operation").copy(timeConfidence = uncertain,
            sessions = listOf("session-a"), recoveryEnd = begin + 70 * minute)
        assertUnconfirmed(rows(listOf(pass)), 0)
    }

    @Test fun mixedCertainAndUncertainCutsSubtractBeforeUnion() {
        val fixed = event("break_started", 2).copy(grant = "fixed", breakEnd = begin + 4 * minute)
        val close = event("break_ended", 4).copy(grant = "fixed")
        assertUnconfirmed(rows(listOf(fixed, close, pause("planned")), anchors = listOf(8, 17, 20)), 9)
    }

    @Test fun overlappingUnboundedGapsUnionTheirLossWindow() {
        val first = pause("planned")
        assertUnconfirmed(rows(listOf(first, first.copy(id = "other", grant = "other"))), 0)
    }

    @Test fun independentCertainSessionFillsUncertainOverlap() {
        val result = summary(rows(listOf(pause())) + listOf(start("session-b"), event("completed", 20, "session-b")))
        assertEquals(20 * minute, result.days.sumOf { it.focusedMs ?: 0 })
        assertEquals(1, result.days.sumOf { it.unconfirmed })
        assertEquals(1, result.days.sumOf { it.completed })
    }

    @Test fun midnightDaysEachDiscountWorstPossiblePlacementRatherThanInventingAGapLocation() {
        val result = summary(rows(listOf(pause())))
        val days = result.days.filter { (it.focusedMs ?: 0) > 0 }
        assertEquals(listOf(5 * minute, 5 * minute), days.map { it.focusedMs })
        assertEquals(15 * minute, result.sessions.single().focusedMs)
        assertEquals(1, days.last().unconfirmed)
    }

    @Test fun uncertainBreakEndCannotUseAnEarlierPlannedReturnAsCertainCoverage() {
        val fixed = event("break_started", 10).copy(kind = "planned", grant = "g", breakEnd = begin + 15 * minute)
        val close = event("break_ended", 15).copy(grant = "g", timeConfidence = uncertain)
        assertUnconfirmed(rows(listOf(fixed, close)), 10)
    }

    @Test fun uncertainRawStampCannotCollapsePlacementAtATrustedAnchor() {
        for (at in listOf(-120L, 0L, 20L, 120L)) assertUnconfirmed(rows(listOf(pause(at = at, until = at))), 15)
    }

    @Test fun grantLinkedUncertainEndPairsAcrossBackwardOrPostterminalRawStamps() {
        val fixed = event("break_started", 10).copy(kind = "planned", grant = "g", breakEnd = begin + 15 * minute)
        for (at in listOf(-120L, 120L)) {
            val close = event("break_ended", at).copy(grant = "g", timeConfidence = uncertain)
            assertUnconfirmed(rows(listOf(fixed, close)), 10)
        }
    }
}
