package com.flint.peakfocus.feature.settings.applock

import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flint.peakfocus.core.common.theme.FlintTheme
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The app-open PIN gate: content privacy, refusal, lockout, and the recovery routes that must
 * work without the PIN (ADR-012 §7, PC-50).
 *
 * TalkBack reads the merged semantics tree, so "hidden behind the gate" is asserted there.
 * Compose's *unmerged* tree deliberately keeps raw descendants below a `clearAndSetSemantics`
 * node; it is a test/debug view and not what an accessibility service receives.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AppLockGateTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun stopAmbientMotion() {
        // EmberLandscape's infinite mist/glow animations would keep the test clock busy.
        Settings.Global.putFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            0f,
        )
    }

    @Test fun `a saved pin hides the content until the correct pin is entered`() {
        val controller = controller(record("1379"))
        compose.setContent { FlintTheme { Gate(controller) { Text("Secret rule") } } }
        compose.waitForIdle()

        compose.onAllNodesWithText("Secret rule").assertCountEquals(0)
        compose.onNodeWithText("Enter PIN").assertExists()

        compose.onNode(hasSetTextAction()).performTextInput("1379")
        compose.onNodeWithText("Unlock").assertIsEnabled().performClick()
        awaitText("Secret rule")
        compose.onNodeWithText("Secret rule").assertExists()
    }

    @Test fun `a wrong pin is refused and five wrong pins start a lockout`() {
        val controller = controller(record("1379"))
        compose.setContent { FlintTheme { Gate(controller) { Text("Secret rule") } } }

        repeat(4) {
            compose.onNode(hasSetTextAction()).performTextInput("0000")
            compose.onNodeWithText("Unlock").performClick()
            awaitEmptyPinField()
            awaitText("Incorrect PIN")
            assertEquals(0L, controller.state.value.lockoutRemainingMs)
        }

        compose.onNode(hasSetTextAction()).performTextInput("0000")
        compose.onNodeWithText("Unlock").performClick()
        awaitEmptyPinField()
        awaitText("Too many attempts. Try again in 0:30.")
        compose.onNodeWithText("Unlock").assertIsNotEnabled()
        compose.onAllNodesWithText("Secret rule").assertCountEquals(0)
    }

    @Test fun `recovery opens without the pin and its OS route is the app info page`() {
        var recoveries = 0
        val controller = controller(record("1379"))
        compose.setContent {
            FlintTheme { Gate(controller, onOpenRecovery = { recoveries++ }) { Text("Secret rule") } }
        }

        compose.onNodeWithText("Emergency Pass & recovery")
            .assertHasClickAction()
            .performScrollTo()
            .performClick()
        awaitText("While Flint is locked")
        // The PIN surface is not reachable while the recovery panel is up.
        compose.onAllNodesWithText("Enter PIN").assertCountEquals(0)

        compose.onNodeWithText("Open Emergency Pass & recovery").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, recoveries) }

        compose.onNodeWithText("Open Flint's App info").performScrollTo().performClick()
        val started = shadowOf(context as Application).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, started.action)
        assertEquals("package", started.data?.scheme)
        assertEquals(context.packageName, started.data?.schemeSpecificPart)

        compose.onNodeWithText("Back to PIN").performScrollTo().performClick()
        awaitText("Enter PIN")
    }

    @Test fun `forgot pin shows the clear storage route`() {
        val controller = controller(record("1379"))
        compose.setContent { FlintTheme { Gate(controller) { Text("Secret rule") } } }

        compose.onNodeWithText("Forgot PIN?").performScrollTo().performClick()
        awaitText("While Flint is locked")
        compose.onNodeWithText("Flint can't show or reset your PIN", substring = true).assertExists()
        compose.onNodeWithText("Storage › Clear storage", substring = true).assertExists()

        compose.onNodeWithText("Back to PIN").performScrollTo().performClick()
        awaitText("Enter PIN")
    }

    @Test fun `recovery stays reachable when the saved pin cannot be read`() {
        var recoveries = 0
        val controller = controller("not a pin record")
        compose.setContent {
            FlintTheme { Gate(controller, onOpenRecovery = { recoveries++ }) { Text("Secret rule") } }
        }

        awaitText("Flint can't read its saved PIN. Your blocks keep running.")
        compose.onAllNodesWithText("Secret rule").assertCountEquals(0)

        compose.onNodeWithText("Emergency Pass & recovery").assertIsEnabled().performScrollTo().performClick()
        awaitText("While Flint is locked")
        compose.onNodeWithText("Open Emergency Pass & recovery").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, recoveries) }
    }

    @Test fun `recovery stays reachable during a lockout`() {
        var recoveries = 0
        val clock = FakeClock(elapsed = 10_000, boot = "boot-a")
        val storage = InMemoryAppLockStorage(
            recordRaw = record("1379"),
            throttle = ThrottleState(
                failures = 5,
                lockoutStartElapsedMs = 10_000,
                lockoutMillis = 30_000,
                lockoutBootId = "boot-a",
            ),
        )
        val controller = AppLockController(storage, clock, hashDispatcher = Dispatchers.Unconfined)
        compose.setContent {
            FlintTheme { Gate(controller, onOpenRecovery = { recoveries++ }) { Text("Secret rule") } }
        }

        awaitText("Too many attempts. Try again in 0:30.")
        compose.onNodeWithText("Unlock").assertIsNotEnabled()
        compose.onNodeWithText("Emergency Pass & recovery").assertIsEnabled().performScrollTo().performClick()
        awaitText("While Flint is locked")
        compose.onNodeWithText("Open Emergency Pass & recovery").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, recoveries) }
    }

    @Test fun `unlock and recovery stay reachable at font scale two`() {
        val controller = controller(record("1379"))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                FlintTheme { Gate(controller) { Text("Secret rule") } }
            }
        }

        compose.onNodeWithText("Emergency Pass & recovery").performScrollTo().performClick()
        awaitText("While Flint is locked")
        compose.onNodeWithText("Back to PIN").performScrollTo().performClick()
        awaitText("Enter PIN")

        compose.onNode(hasSetTextAction()).performScrollTo().performTextInput("1379")
        compose.onNodeWithText("Unlock").performScrollTo().assertIsEnabled().performClick()
        awaitText("Secret rule")
    }

    @Test fun `back closes the open recovery panel`() {
        val controller = controller(record("1379"))
        compose.setContent { FlintTheme { Gate(controller) { Text("Secret rule") } } }

        compose.onNodeWithText("Emergency Pass & recovery").performClick()
        awaitText("While Flint is locked")

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        awaitGone("While Flint is locked")
        awaitText("Enter PIN")
    }

    @Test fun `without a pin the content shows directly`() {
        val controller = controller()
        compose.setContent { FlintTheme { Gate(controller) { Text("Today content") } } }

        awaitText("Today content")
        compose.onAllNodesWithText("Enter PIN").assertCountEquals(0)
    }

    /** The UI clears the field only after an attempt resolves, so an empty field is its barrier. */
    private fun awaitEmptyPinField() {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().any { node ->
                node.config.getOrNull(SemanticsProperties.EditableText)?.text.isNullOrEmpty()
            }
        }
    }

    private fun awaitText(text: String, substring: Boolean = false) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitGone(text: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
        }
    }

    @Composable
    private fun Gate(
        controller: AppLockController,
        onOpenRecovery: () -> Unit = {},
        content: @Composable () -> Unit,
    ) {
        AppLockGate(
            controller = controller,
            onOpenRecovery = onOpenRecovery,
            modifier = Modifier.fillMaxSize(),
            content = content,
        )
    }

    private fun controller(recordRaw: String? = null, clock: FakeClock = FakeClock()) =
        AppLockController(
            storage = InMemoryAppLockStorage(recordRaw = recordRaw),
            clock = clock,
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

    private class FakeClock(var elapsed: Long = 10_000, var boot: String = "boot-a") : AppLockClock {
        override fun elapsedRealtimeMs(): Long = elapsed
        override fun bootId(): String = boot
    }
}
