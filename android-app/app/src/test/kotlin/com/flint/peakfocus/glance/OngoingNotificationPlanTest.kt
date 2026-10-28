package com.flint.peakfocus.glance

import org.junit.Assert.*
import org.junit.Test

class OngoingNotificationPlanTest {
    private val display = GlanceDisplay("Protection active at last check", "Focus · Hardcore · Until 20:00",
        "No upcoming session", "0m", null, "Checked 19:55", 400_000, 160_000)

    @Test fun onlyFreshAuthoritativeActiveDeadlineGetsCountdown() {
        val plan = OngoingNotificationPlan.from(display, 100_000)
        assertEquals(400_000L, plan.countdownEndElapsedMs)
        assertTrue(plan.text.contains("Hardcore"))
    }

    @Test fun staleOrUnavailableNeverRetainsCountdown() {
        for (reading in listOf(display, display.copy(refreshAtElapsedMs = null))) {
            val plan = OngoingNotificationPlan.from(reading, 160_000)
            assertNull(plan.countdownEndElapsedMs)
            assertTrue(plan.title.contains("check needed"))
        }
    }

    @Test fun deadlineDoesNotBecomeCompletionAlert() {
        val plan = OngoingNotificationPlan.from(display.copy(refreshAtElapsedMs = 500_000), 400_000)
        assertNull(plan.countdownEndElapsedMs)
        assertTrue(plan.title.contains("Planned end reached"))
        assertFalse(plan.title.contains("complete"))
    }

    @Test fun degradedPassAndIdleRetainHonestStatusWithoutCountdown() {
        listOf("Protection degraded", "Emergency Pass recovery", "No active protection at last check").forEach { status ->
            val plan = OngoingNotificationPlan.from(display.copy(status = status, countdownEndElapsedMs = null), 100_000)
            assertTrue(plan.title.contains(status))
            assertNull(plan.countdownEndElapsedMs)
        }
    }
}
