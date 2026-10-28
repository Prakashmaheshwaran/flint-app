package com.flint.peakfocus.feature.blocklist

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flint.peakfocus.core.model.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** JVM composition checks: an error re-render must retain the editor-owned draft. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EditorWriteFeedbackTest {
    @get:Rule val compose = createComposeRule()
    private val error = "Couldn't save. Try again."

    @Test fun ruleDraftSurvivesFailureRecompositionAndRetryUsesSameName() {
        val state = mutableStateOf(EditorSaveState())
        val saved = mutableListOf<RuleDraft>()
        compose.setContent {
            RuleEditorScreen(
                initialDraft = RuleDraft(name = "Work", apps = setOf(AppRef("app"))),
                appsState = InstalledAppsUiState.Ready(emptyList()),
                onSave = { saved += it; state.value = EditorSaveState(error = error) },
                onDelete = null, onClose = {}, saveState = state.value,
            )
        }
        compose.onNode(hasSetTextAction() and hasText("Work")).performTextReplacement("My edited draft")
        compose.onNodeWithText("Save rule").performScrollTo().performClick()
        compose.onNodeWithText(error).assertExists()
        compose.onNodeWithText("Save rule").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("My edited draft", "My edited draft"), saved.map { it.name }) }
    }

    @Test fun sleepDraftSurvivesFailureRecompositionAndRetryUsesSameTimes() {
        val state = mutableStateOf(EditorSaveState())
        val saved = mutableListOf<SleepConfig>()
        compose.setContent {
            SleepEditorScreen(initial = SleepConfig.DEFAULT.copy(bedMinute = 1290), appsState = InstalledAppsUiState.Ready(emptyList()),
                onSave = { saved += it; state.value = EditorSaveState(error = error) }, onClose = {}, saveState = state.value)
        }
        compose.onNodeWithText("Mon").performScrollTo().performClick()
        compose.onNodeWithText("Save sleep mode").performScrollTo().performClick()
        compose.onNodeWithText(error).assertExists()
        compose.onNodeWithText("Save sleep mode").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(1290, 1290), saved.map { it.bedMinute })
            assertEquals(listOf(IsoDays.WEEKDAYS - 1, IsoDays.WEEKDAYS - 1), saved.map { it.nights })
        }
    }

    @Test fun limitsDraftSurvivesFailureRecompositionAndRetryUsesSameMinutes() {
        val state = mutableStateOf(EditorSaveState())
        val saved = mutableListOf<TimeLimit?>()
        val limits = LimitsUiState.Ready(buildLimitRows(listOf(TimeLimit("app", 30)), emptyList()) { null })
        compose.setContent {
            LimitEditorFlow(packageName = "app", appsState = InstalledAppsUiState.Ready(emptyList()),
                limitsState = limits, defaultBreakLevel = BreakLevel.EASY,
                onSaveLimits = { _, time, _ -> saved += time; state.value = EditorSaveState(error = error) },
                onClearLimits = {}, onClose = {}, saveState = state.value)
        }
        compose.onNode(hasSetTextAction() and hasText("30")).performScrollTo().performTextReplacement("21")
        compose.onNodeWithText("Save limits").performScrollTo().performClick()
        compose.onNodeWithText(error).assertExists()
        compose.onNodeWithText("Save limits").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(TimeLimit("app", 21), TimeLimit("app", 21)), saved) }
    }
}
