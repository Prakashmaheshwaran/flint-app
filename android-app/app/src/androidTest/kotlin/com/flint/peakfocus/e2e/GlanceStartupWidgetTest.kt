package com.flint.peakfocus.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual startup/read-only fallback pixels, including on the installed R8 release APK. */
@RunWith(AndroidJUnit4::class)
class GlanceStartupWidgetTest : FlintE2EFixture() {
    @Test fun installedWidgetWorkerRendersUnavailableAndOpensFlint() {
        assertTrue(shell("am start -W -n $target/com.flint.peakfocus.e2e.GlanceWidgetHostActivity").contains("Status: ok"))
        device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("(?i)create|allow"))), timeout)?.click()
        visible(By.text("Protection state unavailable"))
        visible(By.text("Emergency Pass unavailable"))
        screenshot("installed-widget-unavailable")
        visible(By.text("Protection state unavailable")).click()
        visible(By.text("Today"))
        screenshot("installed-widget-open-flint")
    }
}
