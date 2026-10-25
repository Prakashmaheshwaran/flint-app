package com.flint.peakfocus.feature.blocklist

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Composition tests for the timer's picker wiring. The ViewModel tests only see the draft, so
 * they cannot catch the F7 regression where the caller supplied a stale count as the eyebrow.
 * Here the initial selection (the confirmed draft) differs from the pending edits, and the
 * header, footer and Add label must all follow the pending selection.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TimerAppPickerTest {
    @get:Rule val compose = createComposeRule()

    private val chrome = InstalledApp("com.android.chrome", "Chrome", null)
    private val camera = InstalledApp("com.android.camera", "Camera", null)
    private val apps = listOf(camera, chrome)
    private val draft = setOf(chrome.packageName, camera.packageName)

    private fun showPicker(initiallySelected: Set<String> = draft) {
        compose.setContent {
            TimerAppPicker(
                appsState = InstalledAppsUiState.Ready(apps),
                initiallySelected = initiallySelected,
                onConfirm = {},
                onCancel = {},
            )
        }
    }

    @Test fun `header footer and add label follow the pending selection`() {
        showPicker()
        compose.onNodeWithText("2 SELECTED").assertExists()
        compose.onNodeWithText("2").assertExists() // Footer count.
        compose.onNodeWithText("Add · 2").assertIsNotEnabled()

        compose.onNodeWithText("Camera").performClick()

        compose.onNodeWithText("1 SELECTED").assertExists()
        compose.onNodeWithText("1").assertExists() // Footer count.
        compose.onNodeWithText("Add · 1").assertIsEnabled()
    }

    @Test fun `searching keeps the pending count while the draft is unchanged`() {
        showPicker()
        compose.onNodeWithText("Camera").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Chrome")

        compose.onNodeWithText("1 SELECTED").assertExists()
        compose.onNodeWithText("1").assertExists() // Footer count.
        compose.onNodeWithText("Add · 1").assertIsEnabled()
    }
}
