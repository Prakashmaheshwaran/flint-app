package com.flint.peakfocus.core.common

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.TelecomManager

/**
 * The device's *runtime* safety-floor surfaces (see `core-model` SafetyFloor): the default and
 * system dialer and every installed home app. A custom default dialer or launcher can be any
 * package, so a build-time list cannot cover it. Shared by the detectors' block coordinator and
 * the app pickers, so a surface the pickers hide is exactly one enforcement refuses to shield.
 *
 * Every home app is included, not only the default: a just-switched or secondary launcher is
 * still a way back to the home screen, and launchers only start other apps (which stay subject
 * to their own rules). Package visibility for the HOME query comes from the `<queries>` entry in
 * blocking-overlay's manifest. Each lookup is independent and best-effort: one failing service
 * never drops the others.
 */
object RuntimeSafetySurfaces {

    fun resolve(context: Context): Set<String> = buildSet {
        val pm = context.packageManager
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        runCatching {
            @Suppress("DEPRECATION") // ResolveInfoFlags overloads need API 33; minSdk is 23.
            pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName?.let(::add)
        }
        runCatching {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(home, 0).forEach { info ->
                info.activityInfo?.packageName?.let(::add)
            }
        }
        val telecom = runCatching {
            context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        }.getOrNull()
        runCatching { telecom?.defaultDialerPackage?.let(::add) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { telecom?.systemDialerPackage?.let(::add) }
        }
    }
}
