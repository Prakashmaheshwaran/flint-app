package com.flint.peakfocus

import com.flint.peakfocus.feature.settings.applock.PinStatus

/**
 * Which window mechanism hides Flint's content from the recents thumbnail and screen capture
 * while a PIN exists.
 *
 * The PIN protects private app content, so the system's own preview of that content must not
 * leak it. Android 13 (API 33) added `Activity.setRecentsScreenshotEnabled` for exactly the
 * recents thumbnail; earlier versions only have `FLAG_SECURE`, which also blocks screenshots and
 * screen recording — the honest trade-off there is a secure window.
 */
data class AppLockWindowDecision(
    /** API 33+: show or hide Flint's recents thumbnail. Ignored below 33. */
    val recentsScreenshotEnabled: Boolean,
    /** API 23-32: add `FLAG_SECURE` to the window. Always false on 33+. */
    val secureFlag: Boolean,
)

object AppLockWindowPolicy {

    /** Pure decision so the API-level split is unit-testable without a device. */
    fun decide(sdkInt: Int, pinStatus: PinStatus): AppLockWindowDecision {
        val protects = pinStatus == PinStatus.On || pinStatus == PinStatus.Unreadable
        return if (sdkInt >= 33) {
            AppLockWindowDecision(recentsScreenshotEnabled = !protects, secureFlag = false)
        } else {
            AppLockWindowDecision(recentsScreenshotEnabled = true, secureFlag = protects)
        }
    }
}
