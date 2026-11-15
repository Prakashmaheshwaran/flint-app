package com.flint.peakfocus.feature.blocklist

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flint.peakfocus.core.common.theme.FlintTheme
import com.flint.peakfocus.core.model.AppRef
import com.flint.peakfocus.core.model.BreakLevel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The Block list / Allow list control and its help line make the Start focus sheet taller. Every
 * break level must still be reachable through an accessibility scroll (what TalkBack and Switch
 * Access use) on a small screen at 200% text, with Begin kept in the fixed footer.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w320dp-h568dp-mdpi")
class TimerStartSheetReachabilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `Hardcore is reachable at large text on a small screen in block-list mode`() = assertHardcoreReachable(false)

    @Test fun `Hardcore is reachable at large text on a small screen in allow-list mode`() = assertHardcoreReachable(true)

    private fun assertHardcoreReachable(allowList: Boolean) {
        var picked: BreakLevel? = null
        val state = TimerUiState(
            draft = TimerDraft(apps = setOf(AppRef("app", "Example")), durationMinutes = 25, allowListMode = allowList),
            appsState = InstalledAppsUiState.Ready(emptyList()),
            rulesLoaded = true,
            startPrerequisite = null,
        )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                FlintTheme {
                    StartFocusSheet(state, {}, {}, {}, {}, { picked = it }, {}, {}, {}, {})
                }
            }
        }
        compose.onNodeWithText("Hardcore").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(BreakLevel.HARDCORE, picked)
        compose.onNodeWithText("Begin 25m").assertIsDisplayed()
    }
}
