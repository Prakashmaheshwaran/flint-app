package com.flint.peakfocus.feature.blocklist

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flint.peakfocus.core.model.AccessClockStamp
import com.flint.peakfocus.core.model.AppRef
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TimerArmingScreenTest {
    @get:Rule val compose = createComposeRule()
    private val at = 1_793_181_600_000L
    private val timer = timerRule(TimerDraft(setOf(AppRef("app")), durationMinutes = 5), at, "screen")
    private fun show(arming: TimerArmingState, retry: () -> Unit = {}, setup: () -> Unit = {}) {
        compose.setContent {
            SessionScene(timer, 1, at, true, true, PaddingValues(), {}, {}, arming, retry, setup, Modifier)
        }
    }

    @Test fun `PC-61 Applying shows selected apps without a running or Armed assurance`() {
        show(TimerArmingState.Applying(timer.id))
        compose.onNodeWithText("Applying…").assertExists()
        compose.onNodeWithText("1 app selected", ignoreCase = true).assertExists()
        compose.onAllNodesWithText("resting", substring = true, ignoreCase = true).assertCountEquals(0)
        compose.onAllNodesWithText("Armed").assertCountEquals(0)
        compose.onAllNodesWithText("running", substring = true, ignoreCase = true).assertCountEquals(0)
    }

    @Test fun `PC-65 degraded saved Timer exposes reason retry and setup without resting copy`() {
        var retries = 0; var setups = 0
        show(TimerArmingState.Degraded(timer.id, "Service unavailable", true), { retries++ }, { setups++ })
        compose.onNodeWithText("Protection degraded").assertExists()
        compose.onNodeWithText("Service unavailable").assertExists()
        compose.onNodeWithText("The Timer keeps its saved end time.").assertExists()
        compose.onAllNodesWithText("resting", substring = true, ignoreCase = true).assertCountEquals(0)
        compose.onNodeWithText("Retry protection").performClick()
        compose.onNodeWithText("Check setup").performClick()
        assertEquals(1, retries); assertEquals(1, setups)
    }

    @Test fun `PC-73 Armed displays its actual backend check instead of checked now`() {
        show(TimerArmingState.Armed(timer.id, AccessClockStamp(at, 10_000, "boot")))
        compose.onNodeWithText("Armed").assertExists()
        compose.onNodeWithText("1 app resting", ignoreCase = true).assertExists()
        compose.onNodeWithText("Service checked at", substring = true).assertExists()
        compose.onAllNodesWithText("checked now", substring = true, ignoreCase = true).assertCountEquals(0)
    }

    @Test fun `PC-73 stale grace shows Checking without Armed degraded or resting text`() {
        show(TimerArmingState.Checking(timer.id))
        compose.onNodeWithText("Checking protection…").assertExists()
        compose.onAllNodesWithText("Armed").assertCountEquals(0)
        compose.onAllNodesWithText("Protection degraded").assertCountEquals(0)
        compose.onAllNodesWithText("resting", substring = true, ignoreCase = true).assertCountEquals(0)
    }
}
