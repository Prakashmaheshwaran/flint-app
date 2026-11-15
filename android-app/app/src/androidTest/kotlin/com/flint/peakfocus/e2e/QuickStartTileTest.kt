package com.flint.peakfocus.e2e

import android.content.ComponentName
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 3.2 quick start on a disposable emulator: the entry is not exported to other apps, and a real
 * Quick Settings tile click opens Start focus (with the existing refusal copy, since no detector
 * is live here) without starting anything.
 *
 * The "other apps cannot reach it" half is asserted from the manifest rather than by trying an
 * `adb shell am start`: the shell UID is allowed to start a non-exported component of a
 * debuggable app, so that probe would succeed for reasons that do not apply to any real app.
 */
@RunWith(AndroidJUnit4::class)
class QuickStartTileTest : FlintE2EFixture() {
    private val tile = "$app/com.flint.peakfocus.quickstart.FlintQuickTileService"
    private val entryClass = "com.flint.peakfocus.quickstart.QuickStartActivity"

    @Test fun tileOpensStartFocusAndOtherAppsCannotUseTheEntry() {
        // What the platform enforces for every other UID: a non-exported activity.
        val info = instrumentation.targetContext.packageManager
            .getActivityInfo(ComponentName(app, entryClass), 0)
        assertFalse("The quick-start entry must not be exported", info.exported)

        // Nothing starts a session or opens the sheet by itself. "Break level" only exists in the
        // Start focus sheet, so the check does not depend on the remembered duration label.
        SystemClock.sleep(500)
        assertNull("No start focus sheet without a user gesture", device.findObject(By.text("Break level")))

        shell("cmd statusbar add-tile $tile")
        try {
            // `add-tile` is asynchronous: SystemUI publishes the tile afterwards, and a click
            // issued before that is silently ignored (the logcat shows
            // notifyQuickSettingsTilesChanged with an empty list right after the click). Retry
            // until the sheet is actually on screen instead of assuming one click lands.
            var attempts = 0
            while (attempts < 10 && !device.hasObject(By.text("Break level"))) {
                shell("cmd statusbar click-tile $tile")
                device.wait(Until.hasObject(By.text("Break level")), 2_000)
                attempts++
            }
            visible(By.text("Break level"))
            visible(By.text("Choose what rests first."))
            visible(By.text("Turn on Accessibility or grant Usage Access."))
            screenshot("tile-start-focus")
        } finally {
            shell("cmd statusbar remove-tile $tile")
            shell("cmd statusbar collapse")
        }
    }
}
