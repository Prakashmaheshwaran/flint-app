package com.flint.peakfocus.e2e

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 3.2 quick start on a disposable emulator: another UID cannot reach the non-exported entry,
 * and a real Quick Settings tile click opens Start focus (with the existing refusal copy,
 * since no detector is live here) without starting anything.
 */
@RunWith(AndroidJUnit4::class)
class QuickStartTileTest : FlintE2EFixture() {
    private val tile = "$app/com.flint.peakfocus.quickstart.FlintQuickTileService"
    private val entry = "$app/com.flint.peakfocus.quickstart.QuickStartActivity"

    @Test fun tileOpensStartFocusAndOtherAppsCannotUseTheEntry() {
        val denied = shell("am start -W -a com.flint.peakfocus.action.START_FOCUS -n $entry")
        assertTrue("Shell must be refused: $denied", denied.contains("not exported") || denied.contains("Permission Denial"))
        SystemClock.sleep(1_000)
        assertNull("A refused external start must not open the sheet", device.findObject(By.text("Begin 30m")))

        shell("cmd statusbar add-tile $tile")
        try {
            shell("cmd statusbar click-tile $tile")
            visible(By.text("Begin 30m"))
            visible(By.text("Choose what rests first."))
            visible(By.text("Turn on Accessibility or grant Usage Access."))
            screenshot("tile-start-focus")
        } finally {
            shell("cmd statusbar remove-tile $tile")
            shell("cmd statusbar collapse")
        }
    }
}
