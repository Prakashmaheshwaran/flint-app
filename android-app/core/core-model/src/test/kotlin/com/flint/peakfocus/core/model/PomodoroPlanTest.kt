package com.flint.peakfocus.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The exact phase vectors of `docs/architecture/pomodoro-chain.md` §2, plus plan validation. */
class PomodoroPlanTest {
    private val minute = 60_000L

    private fun phase(plan: PomodoroPlan, elapsedMinutes: Double): PomodoroPhase? =
        plan.phaseAt((elapsedMinutes * minute).toLong())

    @Test fun fourRoundPlanMatchesDesignVectors() {
        val plan = PomodoroPlan(focusMinutes = 25, restMinutes = 5, rounds = 4)
        assertEquals(115 * minute, plan.totalMs)
        assertEquals(7, plan.phases.size)
        assertEquals(3, plan.rests)

        assertEquals(PomodoroPhase(0, PomodoroPhaseKind.FOCUS, 1, 0, 25 * minute), phase(plan, 0.0))
        assertEquals(PomodoroPhase(0, PomodoroPhaseKind.FOCUS, 1, 0, 25 * minute), phase(plan, 24.99))
        assertEquals(PomodoroPhase(1, PomodoroPhaseKind.REST, 1, 25 * minute, 30 * minute), phase(plan, 25.0))
        assertEquals(PomodoroPhase(2, PomodoroPhaseKind.FOCUS, 2, 30 * minute, 55 * minute), phase(plan, 30.0))
        assertEquals(PomodoroPhase(6, PomodoroPhaseKind.FOCUS, 4, 90 * minute, 115 * minute), phase(plan, 114.99))
        assertNull(phase(plan, 115.0))
        assertNull(phase(plan, 500.0))
        // Negative input clamps to 0 rather than moving the phase.
        assertEquals(phase(plan, 0.0), plan.phaseAt(-1))
        assertEquals(phase(plan, 0.0), plan.phaseAt(-99_999))
    }

    @Test fun longRestPlanMatchesDesignVectors() {
        val plan = PomodoroPlan(focusMinutes = 25, restMinutes = 5, rounds = 4,
            longRestMinutes = 15, longRestEvery = 2)
        assertEquals(125 * minute, plan.totalMs)

        assertEquals(PomodoroPhase(3, PomodoroPhaseKind.LONG_REST, 2, 55 * minute, 70 * minute), phase(plan, 55.0))
        assertEquals(PomodoroPhase(4, PomodoroPhaseKind.FOCUS, 3, 70 * minute, 95 * minute), phase(plan, 70.0))
        assertEquals(PomodoroPhase(5, PomodoroPhaseKind.REST, 3, 95 * minute, 100 * minute), phase(plan, 95.0))
        assertNull(phase(plan, 125.0))
    }

    @Test fun intervalsAreHalfOpenAndCoverEveryMillisecond() {
        val plan = PomodoroPlan(focusMinutes = 5, restMinutes = 1, rounds = 8)
        var cursor = 0L
        plan.phases.forEach { phase ->
            assertEquals(cursor, phase.startMs)
            assertEquals(phase, plan.phaseAt(phase.startMs))
            assertEquals(phase, plan.phaseAt(phase.endMs - 1))
            if (phase.endMs < plan.totalMs) assertFalse(phase == plan.phaseAt(phase.endMs))
            cursor = phase.endMs
        }
        assertEquals(plan.totalMs, cursor)
        assertNull(plan.phaseAt(plan.totalMs))
    }

    @Test fun phasesAlternateFocusAndRestsAndEndOnFocus() {
        val plan = PomodoroPlan(focusMinutes = 10, restMinutes = 2, rounds = 3)
        assertEquals(listOf(PomodoroPhaseKind.FOCUS, PomodoroPhaseKind.REST, PomodoroPhaseKind.FOCUS,
            PomodoroPhaseKind.REST, PomodoroPhaseKind.FOCUS), plan.phases.map { it.kind })
        assertTrue(plan.phases.last().isFocus)
        assertEquals(listOf(1, 1, 2, 2, 3), plan.phases.map { it.round })
        assertEquals(plan.phases[1], plan.restAfterRound(1))
        assertEquals(plan.phases[3], plan.restAfterRound(2))
        assertNull(plan.restAfterRound(3))
    }

    @Test fun defaultIsTwentyFiveOverFiveTimesFour() {
        assertEquals(PomodoroPlan(25, 5, 4), PomodoroPlan.DEFAULT)
        assertEquals(115 * minute, PomodoroPlan.DEFAULT.totalMs)
        assertNull(PomodoroPlan.validationError(PomodoroPlan.DEFAULT))
    }

    @Test fun longestPlanStaysInsideTheTwentyFourHourCap() {
        val longest = PomodoroPlan(focusMinutes = 120, restMinutes = 60, rounds = 8)
        assertEquals((8 * 120 + 7 * 60) * minute, longest.totalMs)
        assertTrue(longest.totalMs <= 24 * 60 * minute)
        assertThrows(IllegalArgumentException::class.java) {
            PomodoroPlan(focusMinutes = 120, restMinutes = 61, rounds = 8)
        }
    }

    @Test fun rejectsOutOfRangeFields() {
        listOf(0, 3, 4, 125, 27).forEach { focus ->
            assertThrows("focus $focus", IllegalArgumentException::class.java) {
                PomodoroPlan(focusMinutes = focus, restMinutes = 5, rounds = 4)
            }
        }
        listOf(0, 61).forEach { rest ->
            assertThrows("rest $rest", IllegalArgumentException::class.java) {
                PomodoroPlan(focusMinutes = 25, restMinutes = rest, rounds = 4)
            }
        }
        listOf(1, 9).forEach { rounds ->
            assertThrows("rounds $rounds", IllegalArgumentException::class.java) {
                PomodoroPlan(focusMinutes = 25, restMinutes = 5, rounds = rounds)
            }
        }
        val valid = PomodoroPlan(focusMinutes = 5, restMinutes = 60, rounds = 2)
        assertEquals(2 * 5 * minute + 60 * minute, valid.totalMs)
    }

    @Test fun longRestPairIsBothOrNeitherAndBounded() {
        // Both or neither.
        assertThrows(IllegalArgumentException::class.java) {
            PomodoroPlan(25, 5, 4, longRestMinutes = 15)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PomodoroPlan(25, 5, 4, longRestEvery = 2)
        }
        // A long rest is 5–60 minutes and at least the ordinary rest.
        assertThrows(IllegalArgumentException::class.java) {
            PomodoroPlan(25, 5, 4, longRestMinutes = 4, longRestEvery = 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PomodoroPlan(25, 5, 4, longRestMinutes = 61, longRestEvery = 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PomodoroPlan(25, 30, 4, longRestMinutes = 20, longRestEvery = 2)
        }
        // Every 2 … rounds − 1: never every round, never the last round's absent rest.
        assertThrows(IllegalArgumentException::class.java) {
            PomodoroPlan(25, 5, 4, longRestMinutes = 15, longRestEvery = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PomodoroPlan(25, 5, 4, longRestMinutes = 15, longRestEvery = 4)
        }
        // restMinutes == longRestMinutes is allowed (at least, not strictly greater).
        val equal = PomodoroPlan(25, 5, 4, longRestMinutes = 5, longRestEvery = 3)
        assertEquals(PomodoroPhaseKind.LONG_REST, equal.restAfterRound(3)?.kind)
    }

    @Test fun choicesCoverTheDocumentedRanges() {
        assertEquals(5, PomodoroPlan.focusChoices.first())
        assertEquals(120, PomodoroPlan.focusChoices.last())
        assertTrue(PomodoroPlan.focusChoices.all { it % 5 == 0 })
        assertEquals(listOf(2, 3, 4, 5, 6, 7, 8), PomodoroPlan.roundChoices)
    }
}
