package com.flint.peakfocus

import android.content.Context
import com.flint.peakfocus.blocking.usagestats.UsageStatsForegroundService
import com.flint.peakfocus.permissions.AccessibilityPermission
import com.flint.peakfocus.permissions.UsageAccess

/** Fully-qualified name of the Path A service, as it appears in Secure settings. */
internal const val A11Y_SERVICE_CLASS =
    "com.flint.peakfocus.blocking.accessibility.FlintAccessibilityService"

/**
 * Path B (UsageStats poll → overlay) is the documented FALLBACK: keep its foreground
 * service running exactly when it can work (usage access granted) and is needed (the
 * accessibility service is off). Running both paths at once would double-enforce — two
 * block surfaces for the same app.
 *
 * Called from the app shell on ON_RESUME and from the boot re-arm hook registered in
 * [FlintApplication]. Both are legal start contexts: resume is foregrounded, and
 * `specialUse` is not on Android 15's list of FGS types barred from BOOT_COMPLETED starts.
 */
internal object PathBServiceGate {
    fun sync(context: Context) {
        val accessibilityOn = AccessibilityPermission.isEnabled(context, A11Y_SERVICE_CLASS)
        if (UsageAccess.isGranted(context) && !accessibilityOn) {
            UsageStatsForegroundService.start(context)
        } else {
            UsageStatsForegroundService.stop(context)
        }
    }
}
