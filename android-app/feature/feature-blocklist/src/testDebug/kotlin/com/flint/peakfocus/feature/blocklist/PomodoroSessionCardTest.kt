package com.flint.peakfocus.feature.blocklist

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flint.peakfocus.core.common.theme.FlintTheme
import com.flint.peakfocus.core.model.AppRef
import com.flint.peakfocus.core.model.PomodoroPlan
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** F01: the running Pomodoro card names the phase and round, not only the chain end. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-mdpi")
class PomodoroSessionCardTest {
    @get:Rule val compose = createComposeRule()

    private val minute = 60_000L
    private val start = 1_793_196_000_000L
    private val plan = PomodoroPlan(focusMinutes = 25, restMinutes = 5, rounds = 2)

    private fun rule() = TimerDraft(apps = setOf(AppRef("com.target", "Target")), pomodoro = true,
        focusMinutes = plan.focusMinutes, restMinutes = plan.restMinutes, rounds = plan.rounds)
        .let { timerRule(it, start, "pomodoro") }

    private fun scene(now: Long) {
        val session = rule()
        compose.setContent {
            FlintTheme {
                SessionScene(
                    session = session,
                    others = 0,
                    nowEpochMs = now,
                    enabled = true,
                    canEnd = true,
                    contentPadding = PaddingValues(),
                    onViewApps = {},
                    onEnd = {},
                    arming = TimerArmingState.Armed(session.id, com.flint.peakfocus.core.model.AccessClockStamp(start, 10_000, "boot")),
                    onRetryProtection = {},
                    onSetup = {},
                    modifier = androidx.compose.ui.Modifier,
                )
            }
        }
    }

    @Test fun `a focus round shows the round and the time left in the phase`() {
        scene(now = start + 10 * minute)
        compose.onNodeWithText("Focus · round 1 of 2 · 15:00 left").assertIsDisplayed()
        compose.onNodeWithText("chain ends at", substring = true).assertIsDisplayed()
    }

    @Test fun `a planned rest says when focus resumes`() {
        scene(now = start + 26 * minute)
        compose.onNodeWithText("Rest · focus resumes in 04:00").assertIsDisplayed()
    }
}
