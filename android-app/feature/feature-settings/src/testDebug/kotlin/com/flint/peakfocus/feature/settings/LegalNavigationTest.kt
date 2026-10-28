package com.flint.peakfocus.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LegalNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun showSettings(loader: (LegalDocument) -> String = { "Bundled ${it.name} body" }) {
        compose.setContent {
            SettingsLegalNavigation(loadDocument = loader) { open ->
                Column { LegalSettingsLinks(onOpen = open) }
            }
        }
    }

    private fun awaitText(text: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun `privacy and licenses open their own bundled document and back returns to settings`() {
        val loaded = mutableListOf<LegalDocument>()
        showSettings { loaded.add(it); "Bundled ${it.name} body" }
        compose.onNodeWithText("Privacy policy").performClick()
        awaitText("Bundled PRIVACY body")
        compose.onNodeWithText("Bundled PRIVACY body").assertIsDisplayed()
        compose.onNodeWithText("Back to settings").performClick()
        compose.onNodeWithText("Open-source licenses").performClick()
        awaitText("Bundled LICENSES body")
        compose.onNodeWithText("Bundled LICENSES body").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Privacy policy").assertIsDisplayed()
        assertEquals(listOf(LegalDocument.PRIVACY, LegalDocument.LICENSES), loaded)
    }

    @Test fun `load failure has an offline retry and a working back action`() {
        var attempts = 0
        showSettings {
            attempts++
            if (attempts == 1) throw IOException("Missing fixture asset")
            "Recovered bundled document"
        }
        compose.onNodeWithText("Privacy policy").performClick()
        awaitText("This bundled document could not be opened.")
        compose.onNodeWithText("Try again").performClick()
        awaitText("Recovered bundled document")
        compose.onNodeWithText("Back to settings").performClick()
        compose.onNodeWithText("Privacy policy").assertIsDisplayed()
        assertEquals(2, attempts)
    }

    @Test fun `legal route survives saved state restoration`() {
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restoration.setContent {
            SettingsLegalNavigation(loadDocument = { "Restored ${it.name}" }) { open ->
                Column { LegalSettingsLinks(onOpen = open) }
            }
        }
        compose.onNodeWithText("Open-source licenses").performClick()
        awaitText("Restored LICENSES")
        restoration.emulateSavedInstanceStateRestore()
        awaitText("Restored LICENSES")
        compose.onNodeWithText("Back to settings").performClick()
        compose.onNodeWithText("Privacy policy").assertIsDisplayed()
    }
}
