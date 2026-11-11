package com.flint.peakfocus.feature.blocklist

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flint.peakfocus.core.common.theme.FlintTheme
import com.flint.peakfocus.core.model.AccessClockStamp
import com.flint.peakfocus.core.model.AppRef
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The immediate Allow List Timer inverts the selection: the picked apps are the ones kept
 * available. Every surface must say so — an allowed app is never described as resting or blocked.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-mdpi")
class TimerAllowListScreenTest {
    @get:Rule val compose = createComposeRule()

    private val at = 1_793_181_600_000L
    private val apps = setOf(AppRef("app", "Example"), AppRef("other.app", "Other"))
    private val draft = TimerDraft(apps = apps, durationMinutes = 5)
    private val allowListState = TimerUiState(
        draft = draft.copy(allowListMode = true),
        appsState = InstalledAppsUiState.Ready(emptyList()),
        rulesLoaded = true,
        startPrerequisite = null,
    )

    @Test fun `the sheet names the allow-list mode and reports a switch to block list`() {
        var toggled: Boolean? = null
        compose.setContent {
            FlintTheme {
                StartFocusSheet(allowListState, {}, {}, {}, {}, {}, {}, {}, {}, {},
                    onAllowListMode = { toggled = it })
            }
        }
        compose.onNodeWithText("2 apps allowed").assertExists()
        compose.onNodeWithText("Every other app is blocked while this runs. Calling, Home, Settings and Flint stay available.")
            .assertExists()
        compose.onAllNodesWithText("resting", substring = true, ignoreCase = true).assertCountEquals(0)
        compose.onNodeWithText("Block list").assertExists().performScrollTo().performClick()
        assertEquals(false, toggled)
    }

    @Test fun `the sheet cannot change the mode while its draft is read only`() {
        var toggles = 0
        compose.setContent {
            FlintTheme {
                StartFocusSheet(allowListState.copy(rulesLoaded = false), {}, {}, {}, {}, {}, {}, {}, {}, {},
                    onAllowListMode = { toggles++ })
            }
        }
        compose.onNodeWithText("Allow list").assertExists()
        compose.onNodeWithText("Block list").assertExists().performScrollTo().assertIsNotEnabled()
        assertEquals(0, toggles)
    }

    @Test fun `the running card states the allow-list mode instead of resting apps`() {
        val timer = timerRule(draft.copy(allowListMode = true), at, "screen")
        compose.setContent {
            FlintTheme {
                SessionScene(timer, 0, at + 1_000, true, true, PaddingValues(), {}, {},
                    TimerArmingState.Armed(timer.id, AccessClockStamp(at, 10_000, "boot")), {}, {}, Modifier)
            }
        }
        compose.onNodeWithText("Allow list · 2 apps allowed").assertIsDisplayed()
        compose.onNodeWithText("Every other app is blocked").assertIsDisplayed()
        compose.onAllNodesWithText("resting", substring = true, ignoreCase = true).assertCountEquals(0)
        compose.onAllNodesWithText("Locked until it ends").assertCountEquals(0)
    }

    @Test fun `the running card keeps the block-list copy for a block-list Timer`() {
        val timer = timerRule(draft, at, "screen")
        compose.setContent {
            FlintTheme {
                SessionScene(timer, 0, at + 1_000, true, true, PaddingValues(), {}, {},
                    TimerArmingState.Armed(timer.id, AccessClockStamp(at, 10_000, "boot")), {}, {}, Modifier)
            }
        }
        compose.onNodeWithText("2 apps resting").assertIsDisplayed()
        compose.onNodeWithText("Locked until it ends").assertIsDisplayed()
        compose.onAllNodesWithText("Allow list", substring = true, ignoreCase = true).assertCountEquals(0)
    }
}
