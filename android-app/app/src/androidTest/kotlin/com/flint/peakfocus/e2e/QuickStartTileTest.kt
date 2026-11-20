package com.flint.peakfocus.e2e

import android.content.ComponentName
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

        // Nothing starts a session or opens the sheet by itself. The empty-draft Start focus
        // sheet shows this hint only when it has opened without an app selection (and the entry
        // must never start anything), so the check does not depend on scroll position or the
        // remembered duration/break-level label.
        val sheetOpen = By.text("Choose what rests first.")
        SystemClock.sleep(500)
        assertNull("No start focus sheet without a user gesture", device.findObject(sheetOpen))

        shell("cmd statusbar add-tile $tile")
        try {
            // `add-tile` is asynchronous: SystemUI publishes the tile afterwards, and a click
            // issued before that is silently ignored (the logcat shows
            // notifyQuickSettingsTilesChanged with an empty list right after the click). Retry
            // until the sheet is actually on screen instead of assuming one click lands. Refresh
            // the accessibility cache while polling so a stale API 36 view never hides an already
            // opened sheet and triggers another tile click after a successful activation.
            var attempts = 0
            while (attempts < 10) {
                refreshAccessibilityCache()
                if (device.hasObject(sheetOpen)) break
                shell("cmd statusbar click-tile $tile")
                attempts++
                val deadline = SystemClock.uptimeMillis() + 2_000
                while (SystemClock.uptimeMillis() < deadline) {
                    refreshAccessibilityCache()
                    if (device.hasObject(sheetOpen)) break
                    SystemClock.sleep(100)
                }
            }
            // Stable sheet identifiers present in the Start focus sheet (not Flint's main screen):
            // the empty-draft hint and the existing refusal copy shown when no detector is live.
            visible(sheetOpen)
            visible(By.text("Turn on Accessibility or grant Usage Access."))
            screenshot("tile-start-focus")
            // Dismiss the sheet, then verify real focus-session state: the Start focus sheet has no
            // "End session" control, so UI absence there cannot prove nothing started in background.
            // History writes are asynchronous, so poll the real file and fail immediately on any started event.
            shell("cmd statusbar collapse")
            visible(By.desc("Close").enabled(true)).click()
            assertNoStartedFocusSessionWithin(
                readEvents = ::focusHistoryEvents,
                nowMs = { SystemClock.uptimeMillis() },
                sleepMs = { SystemClock.sleep(it) },
            )
            visible(By.text("Today"))
        } finally {
            shell("cmd statusbar remove-tile $tile")
            shell("cmd statusbar collapse")
        }
    }

    private fun focusHistoryEvents(): List<JSONObject> =
        readFocusHistory(app) { shell(it) }
}
