package com.flint.peakfocus.feature.settings

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flint.peakfocus.blocking.engine.WebCoverage
import com.flint.peakfocus.blocking.resilience.HealthStatus
import com.flint.peakfocus.core.common.theme.FlintTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The Help route must preserve C5's canonical matrix and update its observation. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HelpWebsiteContentTest {
    @get:Rule val compose = createComposeRule()

    @Test fun helpUsesTheSharedWebsiteProjectionAndUpdatesWhileOpen() {
        val initial = HealthStatus(false, true, true, true, websiteCoverage = WebCoverage.Paused)
        val health = mutableStateOf(initial)
        compose.setContent {
            FlintTheme {
                SettingsHelpNavigation(websiteCoverageText = BlockingHealthUi.websiteCoverage(health.value)) { open ->
                    TextButton(onClick = open) { Text("Open Help fixture") }
                }
            }
        }
        compose.onNodeWithText("Open Help fixture").performClick()
        val pausedCopy = BlockingHealthUi.websiteCoverage(initial)
        compose.onNodeWithText(pausedCopy).assertExists()
        val updated = initial.copy(accessibilityEnabled = true, websiteCoverage = WebCoverage.AwaitingBrowser)
        compose.runOnIdle { health.value = updated }
        compose.onNodeWithText(pausedCopy).assertDoesNotExist()
        compose.onNodeWithText(BlockingHealthUi.websiteCoverage(updated)).assertExists()
    }
}
