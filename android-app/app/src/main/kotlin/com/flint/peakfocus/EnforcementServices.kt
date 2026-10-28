package com.flint.peakfocus

import android.content.Context
import com.flint.peakfocus.blocking.usagestats.UsageStatsForegroundService
import com.flint.peakfocus.permissions.UsageAccess

/** Fully-qualified name of the Path A service, as it appears in Secure settings. */
internal const val A11Y_SERVICE_CLASS =
    "com.flint.peakfocus.blocking.accessibility.FlintAccessibilityService"

/**
 * Keep fallback running whenever Usage Access permits it. The service observes the actual
 * Path A binding and waits in cheap standby while it is live. A settings grant is not proof
 * of liveness, and trying to start a fresh FGS from onUnbind is restricted on Android 12+.
 * Called from the visible app and allowed boot/clock broadcast contexts.
 */
internal object PathBServiceGate {
    fun sync(context: Context) {
        if (UsageAccess.isGranted(context)) UsageStatsForegroundService.start(context)
        else UsageStatsForegroundService.stop(context)
    }
}
