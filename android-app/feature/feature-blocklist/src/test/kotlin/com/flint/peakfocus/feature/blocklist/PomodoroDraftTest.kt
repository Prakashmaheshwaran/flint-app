package com.flint.peakfocus.feature.blocklist

import com.flint.peakfocus.core.datastore.CONTRACT_TIMER_ID_PREFIX
import com.flint.peakfocus.core.datastore.isActiveEditorSession
import com.flint.peakfocus.core.model.AccessClockStamp
import com.flint.peakfocus.core.model.AppRef
import com.flint.peakfocus.core.model.BreakLevel
import com.flint.peakfocus.core.model.PomodoroPhaseKind
import com.flint.peakfocus.core.model.PomodoroPlan
import com.flint.peakfocus.core.datastore.EditorCause
import com.flint.peakfocus.blocking.engine.AnchoredInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F01 draft validation and the running-card text, without a device or Compose runtime. */
class PomodoroDraftTest {
    private val minute = 60_000L
    private val now = 1_793_196_000_000L
    private val apps = setOf(AppRef("com.target", "Target"))

    private fun pomodoroDraft(
        focus: Int = 25,
        rest: Int = 5,
        rounds: Int = 4,
        longRest: Int? = null,
        every: Int? = null,
    ) = TimerDraft(apps = apps, pomodoro = true, focusMinutes = focus, restMinutes = rest,
        rounds = rounds, longRestMinutes = longRest, longRestEvery = every)

    @Test fun `a plain Timer draft stays unchanged and plan-less`() {
        val draft = TimerDraft(apps = apps, durationMinutes = 45)
        assertTrue(validTimerDraft(draft))
        assertNull(draftPomodoroPlan(draft))
        val rule = timerRule(draft, now, "id")
        assertNull(rule.pomodoro)
        assertEquals(now + 45 * minute, rule.expiresAtEpochMs)
        assertEquals(45, draftChainMinutes(draft))
    }

    @Test fun `a Pomodoro draft commits the whole chain as one Timer`() {
        val draft = pomodoroDraft()
        assertTrue(validTimerDraft(draft))
        val plan = draftPomodoroPlan(draft)!!
        assertEquals(PomodoroPlan.DEFAULT, plan)
        assertEquals(115, draftChainMinutes(draft))

        val rule = timerRule(draft, now, "id")
        assertEquals(plan, rule.pomodoro)
        assertEquals(now + plan.totalMs, rule.expiresAtEpochMs)
        assertEquals(now + 115 * minute, rule.expiresAtEpochMs)
        assertTrue(rule.id.startsWith(CONTRACT_TIMER_ID_PREFIX))
        assertEquals("Pomodoro · 4 rounds", rule.name)
        // The tier applies to the whole parent, not to a single round.
        assertEquals(BreakLevel.EASY, rule.breakLevel)
        assertEquals("Pomodoro · 2 rounds", pomodoroTimerName(plan.copy(rounds = 2)))
    }

    @Test fun `the longest allowed chain stays inside the 24 hour cap`() {
        val draft = pomodoroDraft(focus = 120, rest = 60, rounds = 8)
        assertTrue(validTimerDraft(draft))
        assertEquals(1_380, draftChainMinutes(draft))
        assertEquals(now + 1_380 * minute, timerRule(draft, now, "id").expiresAtEpochMs)
    }

    @Test fun `invalid plans and missing apps are refused before any write`() {
        assertFalse("no apps", validTimerDraft(TimerDraft(apps = emptySet(), pomodoro = true)))
        assertFalse("one round is a plain Timer", validTimerDraft(pomodoroDraft(rounds = 1)))
        assertFalse("nine rounds", validTimerDraft(pomodoroDraft(rounds = 9)))
        assertFalse("off-step focus", validTimerDraft(pomodoroDraft(focus = 27)))
        assertFalse("rest over an hour", validTimerDraft(pomodoroDraft(rest = 61)))
        assertFalse("long rest without an interval", validTimerDraft(pomodoroDraft(longRest = 15)))
        assertFalse("long rest shorter than the rest",
            validTimerDraft(pomodoroDraft(rest = 20, longRest = 10, every = 2)))
        assertFalse("long rest every round", validTimerDraft(pomodoroDraft(longRest = 15, every = 1)))
    }

    @Test fun `an optional long rest is part of the same frozen plan`() {
        val draft = pomodoroDraft(longRest = 15, every = 2)
        val plan = draftPomodoroPlan(draft)!!
        assertEquals(125, draftChainMinutes(draft))
        assertEquals(PomodoroPhaseKind.LONG_REST, plan.restAfterRound(2)!!.kind)
        assertEquals(PomodoroPhaseKind.REST, plan.restAfterRound(1)!!.kind)
        assertEquals(plan, timerRule(draft, now, "id").pomodoro)
    }

    @Test fun `the running card text follows the phase and never the chain end`() {
        val plan = PomodoroPlan.DEFAULT
        assertEquals("Focus · round 1 of 4 · 25:00 left", pomodoroCardLine(plan, 0))
        assertEquals("Focus · round 1 of 4 · 00:01 left", pomodoroCardLine(plan, 25 * minute - 1_000))
        assertEquals("Rest · focus resumes in 05:00", pomodoroCardLine(plan, 25 * minute))
        assertEquals("Focus · round 2 of 4 · 25:00 left", pomodoroCardLine(plan, 30 * minute))
        assertNull("a finished chain claims no phase", pomodoroCardLine(plan, plan.totalMs))

        val withLong = PomodoroPlan(25, 5, 4, longRestMinutes = 15, longRestEvery = 2)
        assertEquals("Long rest · focus resumes in 15:00", pomodoroCardLine(withLong, 55 * minute))
    }

    @Test fun `the start disclosure states the plan and that rests are committed`() {
        val text = pomodoroDisclosure(PomodoroPlan.DEFAULT)
        assertTrue(text.contains("4 focus rounds of 25 minutes"))
        assertTrue(text.contains("5-minute rests"))
        assertTrue(text.contains("Rests are planned and part of the commitment"))
        assertTrue(text.contains("whole chain"))
        assertTrue(text.contains("cannot skip"))
        assertTrue(pomodoroDisclosure(PomodoroPlan(25, 5, 4, 15, 2)).contains("15-minute long rest every 2 rounds"))
    }

    @Test fun `a live chain projects focus and rest without ending the commitment`() {
        val rule = timerRule(pomodoroDraft(), now, "id")
        val plan = rule.pomodoro!!
        val stamp = AccessClockStamp(now, 10_000, "boot")
        val cause = EditorCause(rule.id,
            AnchoredInterval(stamp, plan.totalMs, now + plan.totalMs), "UTC")
        assertTrue("focus is an active commitment", isActiveEditorSession(rule, cause, stamp))
        val restStamp = AccessClockStamp(now + 26 * minute, 10_000 + 26 * minute, "boot")
        assertTrue("a rest is still an active commitment",
            isActiveEditorSession(rule, cause, restStamp))
        assertNotNull(plan.phaseAt(plan.totalMs - cause.interval!!.remainingMs(restStamp)))
        assertEquals(PomodoroPhaseKind.REST,
            plan.phaseAt(plan.totalMs - cause.interval!!.remainingMs(restStamp))!!.kind)
    }
}
