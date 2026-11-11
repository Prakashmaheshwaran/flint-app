package com.flint.peakfocus.e2e

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.os.SystemClock
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F03 app-open PIN on a disposable emulator: a saved PIN gates Flint's content on a cold start,
 * refuses a wrong PIN, leaves Emergency Pass and protection recovery reachable, unlocks with the
 * right PIN, and turns off again when the PIN is removed.
 *
 * The fixture clears Flint before every test, so the PIN starts Off.
 */
@RunWith(AndroidJUnit4::class)
class AppPinTest : FlintE2EFixture() {

    @Test fun appPinGatesContentAndRecoveryStaysReachable() {
        val pin = "2468"

        // Set the PIN from You → App lock.
        clickTab("You")
        scrollTo("Set PIN").click()
        visible(By.text("Choose 4–6 digits (0–9), then enter them again to confirm."))
        fillPinFields(listOf(pin, pin))
        screenshot("pin-dialog-filled")
        click("Save")
        visible(By.text("Remove PIN"))
        screenshot("pin-on")

        // A cold start gates Flint's content; no tab is reachable behind the gate.
        shell("am force-stop $app")
        assertTrue("Flint process stopped", shell("pidof $app").trim().isEmpty())
        launchFlint()
        visible(By.text("Enter PIN"))
        assertFalse("Tab bar must be unreachable", device.hasObject(By.text("Today")))
        assertFalse("Tab bar must be unreachable", device.hasObject(By.text("Rules")))
        screenshot("gate")

        // A wrong PIN is refused and the gate stays up.
        fillPinFields(listOf("1111"))
        click("Unlock")
        visible(By.text("Incorrect PIN"))
        visible(By.text("Enter PIN"))
        screenshot("gate-wrong")

        // Recovery opens without the PIN and comes back to the gate.
        click("Emergency Pass & recovery")
        visible(By.text("While Flint is locked"))
        screenshot("gate-recovery")
        click("Open Emergency Pass & recovery")
        visible(By.text("Protection recovery"))
        screenshot("recovery")
        device.pressBack()
        visible(By.text("Enter PIN"))

        // The right PIN opens the app.
        fillPinFields(listOf(pin))
        click("Unlock")
        visible(By.text("Today"))
        screenshot("unlocked")

        // Removing the PIN needs the current PIN and turns the gate off.
        clickTab("You")
        scrollTo("Remove PIN").click()
        visible(By.text("Enter your current PIN"))
        fillPinFields(listOf(pin))
        click("Continue")
        scrollTo("Set PIN")
        screenshot("pin-off")

        shell("am force-stop $app")
        launchFlint()
        visible(By.text("Today"))
        assertFalse("Gate must be gone", device.hasObject(By.text("Enter PIN")))
        screenshot("no-gate")
    }

    /**
     * Types into the dialog's PIN fields in order. The fields are masked, so the value can only be
     * proven by the outcome (Save enabling, the gate unlocking, the PIN being removed).
     */
    private fun fillPinFields(values: List<String>) {
        values.forEachIndexed { index, value ->
            val fields = device.wait(Until.findObjects(By.clazz("android.widget.EditText")), timeout)
            check(!fields.isNullOrEmpty() && fields.size > index) {
                "Missing PIN field $index (found ${fields?.size ?: 0})"
            }
            fields[index].click()
            visible(By.clazz("android.widget.EditText").focused(true)).text = value
        }
        hideKeyboard()
    }

    /** Dismisses the number pad only when it is actually up, so Back never closes a dialog. */
    private fun hideKeyboard() {
        val deadline = SystemClock.uptimeMillis() + timeout
        var dismissed = false
        while (SystemClock.uptimeMillis() < deadline) {
            if (!imeVisible()) return
            if (!dismissed) {
                device.pressBack()
                dismissed = true
            }
            SystemClock.sleep(100)
        }
        fail("The keyboard did not hide; refusing to press Back again")
    }

    private fun imeVisible(): Boolean {
        val automation = instrumentation.getUiAutomation(
            UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
        )
        val info = automation.serviceInfo ?: return false
        val retrieveWindows = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        if (info.flags and retrieveWindows == 0) {
            info.flags = info.flags or retrieveWindows
            automation.serviceInfo = info
        }
        refreshAccessibilityCache()
        return automation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    }
}
