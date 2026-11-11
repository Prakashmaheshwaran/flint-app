package com.flint.peakfocus.feature.settings.applock

import android.content.Context
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flint.peakfocus.blocking.resilience.HealthStatus
import com.flint.peakfocus.core.common.Oem
import com.flint.peakfocus.core.common.theme.FlintTheme
import com.flint.peakfocus.feature.settings.OemGuidanceCatalog
import com.flint.peakfocus.feature.settings.SettingsContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** You → App lock: set, change and remove flows, including the current-PIN challenges. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AppLockSettingsTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun stopAmbientMotion() {
        Settings.Global.putFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            0f,
        )
    }

    @Test fun `setting a pin validates twice and turns the row on`() {
        val controller = controller()
        compose.setContent { Section(controller) }

        compose.onNodeWithText("Off").assertExists()
        compose.onNodeWithText("Set PIN").performClick()
        compose.onNodeWithText("Choose 4–6 digits (0–9), then enter them again to confirm.").assertExists()

        compose.onAllNodes(hasSetTextAction())[0].performTextInput("12")
        compose.onNodeWithText("Use 4–6 digits (0–9), with no spaces.").assertExists()
        compose.onNodeWithText("Save").assertIsNotEnabled()

        compose.onAllNodes(hasSetTextAction())[0].performTextClearance()
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("2468")
        compose.onAllNodes(hasSetTextAction())[1].performTextInput("2469")
        compose.onNodeWithText("PINs don’t match. Enter the same PIN twice.").assertExists()
        compose.onNodeWithText("Save").assertIsNotEnabled()

        compose.onAllNodes(hasSetTextAction())[1].performTextClearance()
        compose.onAllNodes(hasSetTextAction())[1].performTextInput("2468")
        compose.onNodeWithText("Save").assertIsEnabled().performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("On").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Change PIN").assertExists()
        compose.onNodeWithText("Remove PIN").assertExists()
        assertEquals(PinStatus.On, controller.state.value.pinStatus)
    }

    @Test fun `changing the pin requires the current pin before any new pin`() {
        val controller = controller(record("1379"))
        compose.setContent { Section(controller) }
        compose.runOnIdle {
            assertEquals(PinAttempt.Success, runBlocking { controller.unlock("1379") })
        }

        compose.onNodeWithText("Change PIN").performClick()
        compose.onNodeWithText("Enter your current PIN").assertExists()
        compose.onAllNodesWithText("New PIN").assertCountEquals(0)

        compose.onAllNodes(hasSetTextAction())[0].performTextInput("0000")
        compose.onNodeWithText("Continue").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Incorrect PIN").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText("New PIN").assertCountEquals(0)

        compose.onAllNodes(hasSetTextAction())[0].performTextInput("1379")
        compose.onNodeWithText("Continue").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("New PIN").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("1357")
        compose.onAllNodes(hasSetTextAction())[1].performTextInput("1357")
        compose.onNodeWithText("Save").assertIsEnabled().performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("New PIN").fetchSemanticsNodes().isEmpty()
        }
        compose.runOnIdle {
            assertEquals(PinAttempt.Success, runBlocking { controller.authorizeChange("1357") })
        }
    }

    @Test fun `removing the pin needs the current pin and turns the row off`() {
        val controller = controller(record("1379"))
        compose.setContent { Section(controller) }
        compose.runOnIdle {
            assertEquals(PinAttempt.Success, runBlocking { controller.unlock("1379") })
        }

        compose.onNodeWithText("Remove PIN").performClick()
        compose.onNodeWithText("Enter your current PIN").assertExists()

        compose.onAllNodes(hasSetTextAction())[0].performTextInput("0000")
        compose.onNodeWithText("Continue").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Incorrect PIN").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("On").assertExists()

        compose.onAllNodes(hasSetTextAction())[0].performTextInput("1379")
        compose.onNodeWithText("Continue").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Off").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Set PIN").assertExists()
        assertEquals(PinStatus.Off, controller.state.value.pinStatus)
    }

    @Test fun `backgrounding closes an open pin dialog`() {
        val controller = controller(record("1379"))
        compose.setContent { Section(controller) }
        compose.runOnIdle {
            assertEquals(PinAttempt.Success, runBlocking { controller.unlock("1379") })
        }

        compose.onNodeWithText("Remove PIN").performClick()
        compose.onNodeWithText("Enter your current PIN").assertExists()

        compose.runOnIdle { controller.onBackground() }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Enter your current PIN").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("App PIN").assertExists()
    }

    @Test fun `the settings screen hides the section when no controller is wired`() {
        compose.setContent {
            FlintTheme {
                SettingsContent(
                    health = HealthStatus(true, true, true, true),
                    exits = emptyList(),
                    oemGuidance = OemGuidanceCatalog.forOem(Oem.OTHER),
                    exitsSupported = false,
                    nowMillis = 0,
                    onFix = {},
                )
            }
        }
        compose.onAllNodesWithText("App PIN").assertCountEquals(0)
    }

    @Test fun `the settings screen shows the section when a controller is wired`() {
        val controller = controller(record("1379"))
        compose.setContent {
            val state by controller.state.collectAsState()
            FlintTheme {
                SettingsContent(
                    health = HealthStatus(true, true, true, true),
                    exits = emptyList(),
                    oemGuidance = OemGuidanceCatalog.forOem(Oem.OTHER),
                    exitsSupported = false,
                    nowMillis = 0,
                    onFix = {},
                    appLock = AppLockSettingsUi.from(controller, state),
                )
            }
        }
        compose.onNodeWithText("App PIN").performScrollTo().assertExists()
        compose.onNodeWithText("On").assertExists()
    }

    @Composable
    private fun Section(controller: AppLockController) {
        val state by controller.state.collectAsState()
        FlintTheme { AppLockSettingsSection(AppLockSettingsUi.from(controller, state)) }
    }

    private fun controller(recordRaw: String? = null) = AppLockController(
        storage = InMemoryAppLockStorage(recordRaw = recordRaw),
        clock = TestClock(),
        hashDispatcher = Dispatchers.Unconfined,
    )

    /** Fast record for tests: the cheapest accepted PBKDF2 cost, real salt/hash layout. */
    private fun record(pin: String, saltOffset: Int = 1): String {
        val salt = ByteArray(AppPinHasher.SALT_BYTES) { (it + saltOffset).toByte() }
        val record = AppPinRecord(
            iterations = AppPinHasher.MIN_ITERATIONS,
            salt = salt,
            hash = AppPinHasher.hash(pin, salt, AppPinHasher.MIN_ITERATIONS),
        )
        return AppPinRecordCodec.encode(record)
    }

    private class TestClock : AppLockClock {
        override fun elapsedRealtimeMs(): Long = 10_000
        override fun bootId(): String = "boot-a"
    }
}
