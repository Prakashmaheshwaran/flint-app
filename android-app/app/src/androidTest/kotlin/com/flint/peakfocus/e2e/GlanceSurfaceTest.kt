package com.flint.peakfocus.e2e

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Fixture pixels, not enforcement evidence. AppWidgetHost runs the real receiver/WorkManager job. */
@RunWith(AndroidJUnit4::class)
class GlanceSurfaceTest : FlintE2EFixture() {
    @Test fun fixtureWidgetAndOptionalNotificationProtectedInactiveAndUnavailable() {
        // This class is selected only by the debug suite; release has exact separate selectors.
        // Explicit activity launch below must fail if the fixture is unexpectedly missing.
        openFixture("protected")
        // Ordinary widget binding and POST_NOTIFICATIONS consent; never adopt/grant privileges.
        val deadline = SystemClock.uptimeMillis() + timeout
        var bound = false
        var allowed = false
        while (SystemClock.uptimeMillis() < deadline) {
            refreshAccessibilityCache()
            if (!bound) device.findObject(By.text(java.util.regex.Pattern.compile("(?i)create|allow")))?.let { it.click(); bound = true }
            if (!allowed) device.findObject(By.res("com.android.permissioncontroller", "permission_allow_button"))?.let { it.click(); allowed = true }
            if (device.findObject(By.text("Protection active at last check")) != null) break
            SystemClock.sleep(100)
        }
        visible(By.text("Protection active at last check"))
        visible(By.text("Emergency Pass unavailable"))
        visible(By.textContains("Fixture focus · Harder"))
        visible(By.textContains("5m"))
        screenshot("fixture-widget-protected")
        device.openNotification()
        visible(By.text("Flint · Protection active at last check"))
        screenshot("fixture-notification-protected")
        device.pressBack()
        openFixture("inactive")
        visible(By.text("No active protection at last check"))
        screenshot("fixture-widget-not-protected")
        device.openNotification()
        assertFalse(device.wait(androidx.test.uiautomator.Until.hasObject(By.text("Flint · Protection active at last check")), 1_000))
        screenshot("fixture-notification-not-protected-absent")
        device.pressBack()
        openFixture("unavailable")
        visible(By.text("Protection state unavailable"))
        visible(By.textContains("5m"))
        screenshot("fixture-widget-unavailable-history-readable")
        device.openNotification()
        assertFalse(device.wait(androidx.test.uiautomator.Until.hasObject(By.text("Flint · Protection active at last check")), 1_000))
        screenshot("fixture-notification-unavailable-absent")
        device.pressBack()

        // Open Flint from an active fixture: production readers must replace the fake global
        // observer even while the fixture Activity remains on the back stack.
        openFixture("protected")
        visible(By.text("Protection active at last check")).click()
        visible(By.text("Today"))
        productionHost()
        screenshot("fixture-exit-restores-production-widget")

        openFixture("protected")
        visible(By.text("Protection active at last check"))
        device.pressHome()
        productionHost()
        screenshot("fixture-home-restores-production-widget")
    }

    private fun openFixture(state: String) {
        assertTrue(shell("am start -W -n $app/.glance.GlanceFixtureActivity --es state $state").contains("Status: ok"))
    }

    private fun productionHost() {
        shell("am start -W -n $target/com.flint.peakfocus.e2e.GlanceWidgetHostActivity")
        val deadline = SystemClock.uptimeMillis() + timeout
        var consented = false
        while (SystemClock.uptimeMillis() < deadline) {
            refreshAccessibilityCache()
            if (device.hasObject(By.text("Protection state unavailable"))) break
            if (!consented) device.findObject(By.text(java.util.regex.Pattern.compile("(?i)create|allow")))?.let {
                it.click(); consented = true
            }
            SystemClock.sleep(100)
        }
        visible(By.text("Protection state unavailable"))
        assertFalse(device.hasObject(By.textContains("Fixture local history")))
        assertFalse(device.hasObject(By.textContains("Fixture focus")))
    }
}
