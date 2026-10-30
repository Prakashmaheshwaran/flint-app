package com.flint.peakfocus.feature.blocklist

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flint.peakfocus.core.common.theme.FlintTheme
import com.flint.peakfocus.core.model.BreakLevel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PCSleepTierScreenTest {
    @get:Rule val compose = createComposeRule()
    private var saved: SleepConfig? = null
    private fun render(initial: SleepConfig) {
        compose.setContent { FlintTheme {
            SleepEditorScreen(initial, InstalledAppsUiState.Ready(emptyList()), { saved = it }, {})
        } }
    }

    @Test fun PC_82_reopenedHardcoreHasNoPresetAndExplicitPresetChangesTheShownTierBeforeSave() {
        render(SleepConfig(enabled = true, breakLevel = BreakLevel.HARDCORE))
        compose.onNodeWithText("Wind down").performScrollTo().assertIsNotSelected()
        compose.onNodeWithText("Full assist").performScrollTo().assertIsNotSelected()
        compose.onNodeWithText("Tier: Hardcore").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(HARDCORE_COMMITMENT_DISCLOSURE).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Full assist").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("Tier: Harder").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertNull(saved) }
        compose.onNodeWithText("Save sleep mode").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(BreakLevel.HARDER, saved!!.breakLevel)
            assertEquals(BreakLevel.HARDER, saved!!.morningBreakLevel)
        }
    }

    @Test fun PC_41_PC_42_PC_82_newDraftStartsWindDownAndHardcoreUsesTheSharedGlobalPassDisclosure() {
        render(SleepConfig.DEFAULT)
        compose.onNodeWithText("Wind down").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Tier: Easy").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Hardcore").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("Wind down").performScrollTo().assertIsNotSelected()
        compose.onNodeWithText("Full assist").performScrollTo().assertIsNotSelected()
        compose.onNodeWithText(HARDCORE_COMMITMENT_DISCLOSURE).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Sleep pass", substring = true).assertCountEquals(0)
        compose.runOnIdle { assertNull(saved) }
        compose.onNodeWithText("Save sleep mode").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(BreakLevel.HARDCORE, saved!!.breakLevel) }
    }

    @Test fun PC_82_strongerSavedMorningTierIsVisibleAndSurvivesAnUnrelatedSave() {
        render(SleepConfig(morningEnabled = true, breakLevel = BreakLevel.EASY, morningBreakLevel = BreakLevel.HARDCORE))
        compose.onNodeWithText("Tier: Easy").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Tier: Hardcore").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Save sleep mode").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(BreakLevel.EASY, saved!!.breakLevel)
            assertEquals(BreakLevel.HARDCORE, saved!!.morningBreakLevel)
        }
    }
}
