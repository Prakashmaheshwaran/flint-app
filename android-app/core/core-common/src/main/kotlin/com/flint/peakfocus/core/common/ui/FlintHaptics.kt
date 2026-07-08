package com.flint.peakfocus.core.common.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * Flint's haptic vocabulary — the app-wide policy for when the device answers the hand:
 *
 * - [confirm] — commitment moments: a focus session starts, a rule saves, consent accepted.
 * - [reject]  — the app refuses: blocked back-press, denied break, failed save.
 * - [toggleOn]/[toggleOff] — every Switch and stateful pill.
 * - [tick]    — light selection: tab changes, day pills, chip picks.
 *
 * Built on [View.performHapticFeedback] with [HapticFeedbackConstants], NOT Compose's
 * `HapticFeedbackType`: at this Compose BOM (ui 1.7.x) the enum only carries
 * LongPress/TextHandleMove, far too blunt for a vocabulary. The view path also respects the
 * user's system haptics setting for free. Constants are API-gated with older fallbacks
 * (minSdk 23); `performHapticFeedback` safely no-ops when unsupported.
 */
class FlintHaptics(private val view: View) {

    /** A commitment moment: session start, rule saved, consent accepted. */
    fun confirm() {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM
            else HapticFeedbackConstants.LONG_PRESS,
        )
    }

    /** The app refuses: blocked back-press, denied break, failed save. */
    fun reject() {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT
            else HapticFeedbackConstants.LONG_PRESS,
        )
    }

    /** A switch/stateful pill turned on. */
    fun toggleOn() {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.TOGGLE_ON
            else HapticFeedbackConstants.KEYBOARD_TAP,
        )
    }

    /** A switch/stateful pill turned off. */
    fun toggleOff() {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.TOGGLE_OFF
            else HapticFeedbackConstants.VIRTUAL_KEY,
        )
    }

    /** Light selection: tab change, day pill, chip pick. */
    fun tick() {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK
            else HapticFeedbackConstants.CLOCK_TICK,
        )
    }
}

/** The [FlintHaptics] for the current composition's host view. */
@Composable
fun rememberFlintHaptics(): FlintHaptics {
    val view = LocalView.current
    return remember(view) { FlintHaptics(view) }
}
