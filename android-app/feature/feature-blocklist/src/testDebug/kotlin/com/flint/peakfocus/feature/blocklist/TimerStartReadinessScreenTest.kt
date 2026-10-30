package com.flint.peakfocus.feature.blocklist

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flint.peakfocus.core.common.theme.FlintTheme
import com.flint.peakfocus.core.model.AppRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-mdpi")
class TimerStartReadinessScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `PC-61 refused start recovery stays visible without scrolling at default font`() = missingPrerequisites(1f)

    @Test fun `PC-61 refused start recovery stays visible without scrolling at largest Android font`() = missingPrerequisites(2f)

    private fun missingPrerequisites(fontScale: Float) {
        val reason = "Turn on Accessibility or grant Usage Access."
        val state = mutableStateOf(TimerUiState(
            draft = TimerDraft(setOf(AppRef("example.app")), durationMinutes = 5),
            rulesLoaded = true,
            appsState = InstalledAppsUiState.Ready(emptyList()),
            startPrerequisite = reason,
        ))
        var starts = 0; var checks = 0; var setups = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                FlintTheme {
                    StartFocusSheet(state.value, {}, {}, {}, {}, {}, {}, { starts++ }, {
                        checks++
                        state.value = state.value.copy(startPrerequisite = null)
                    }, { setups++ })
                }
            }
        }
        compose.onNodeWithText("Not armed.").assertIsDisplayed()
        compose.onNodeWithText(reason).assertIsDisplayed()
        compose.onNodeWithContentDescription("Not armed. $reason")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        assertTrue(compose.onNodeWithContentDescription("Not armed. $reason")
            .fetchSemanticsNode().config.isMergingSemanticsOfDescendants)
        compose.onNodeWithText("Retry").assertIsDisplayed()
        compose.onNodeWithText("Check setup").assertIsDisplayed().performClick()
        assertEquals(1, setups)
        val begin = compose.onNodeWithText("Begin 5m")
        begin.assertIsDisplayed().assertIsNotEnabled().performClick()
        assertEquals("Refused Begin cannot invoke the write callback", 0, starts)
        val reasonBounds = compose.onNodeWithText(reason).getUnclippedBoundsInRoot()
        val beginBounds = begin.getUnclippedBoundsInRoot()
        assertTrue("The full refusal must be above Begin", reasonBounds.bottom <= beginBounds.top)
        compose.onNodeWithText("Retry").performClick()
        assertEquals(1, checks)
        assertEquals("Retry must never repeat a start", 0, starts)
        begin.assertIsEnabled()
        begin.performClick()
        assertEquals("Only the next explicit Begin can start", 1, starts)
    }

    @Test fun `PC-61 Checking is announced without an Armed claim or a nonexistent setup action`() {
        compose.setContent {
            FlintTheme {
                StartFocusSheet(TimerUiState(rulesLoaded = true), {}, {}, {}, {}, {}, {}, {}, {}, {}, setupAvailable = false)
            }
        }
        compose.onNodeWithContentDescription("Checking protection…").assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.onAllNodesWithText("Not armed.").assertCountEquals(0)
        compose.onAllNodesWithText("Check setup").assertCountEquals(0)
        compose.onNodeWithText("Retry").assertIsDisplayed()
        compose.onNodeWithText("Begin 30m").assertIsNotEnabled()
    }

    @Test fun `PC-65 recovery announcement gives an honest wait with retry and no empty setup destination`() {
        val reason = "Protection recovery is active. Try again after recovery ends."
        compose.setContent {
            FlintTheme {
                StartFocusSheet(TimerUiState(rulesLoaded = true, startPrerequisite = reason),
                    {}, {}, {}, {}, {}, {}, {}, {}, {}, setupAvailable = false)
            }
        }
        compose.onNodeWithContentDescription("Not armed. $reason").assertIsDisplayed()
        compose.onAllNodesWithText("Resume protection first.").assertCountEquals(0)
        compose.onAllNodesWithText("Check setup").assertCountEquals(0)
        compose.onNodeWithText("Retry").assertIsDisplayed()
        compose.onNodeWithText("Begin 30m").assertIsNotEnabled()
    }
}
