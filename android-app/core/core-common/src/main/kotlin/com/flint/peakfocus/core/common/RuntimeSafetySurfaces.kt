package com.flint.peakfocus.core.common

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.TelecomManager

/**
 * The device's *runtime* safety-floor surfaces (see `core-model` SafetyFloor): the current
 * default home app and the default and system dialer. A custom default dialer or launcher can be
 * any package, so a build-time list cannot cover it. Shared by the detectors' block coordinator
 * and the app pickers, so a surface the pickers hide is exactly one enforcement refuses to shield.
 *
 * Only the *default* home app is on the floor: it is the way back to the home screen (shielding
 * it loops Home into the block screen). Other packages that merely declare a HOME activity are
 * ordinary apps — exempting every HOME handler would let any app escape rules by declaring one.
 * A launcher the user just made default is picked up by the coordinator's re-resolve before it
 * mounts a shield.
 *
 * [resolveBySource] keeps each lookup separate: a failing service yields `null` for that source
 * only, so a caller can keep its last known value instead of shrinking the floor. Package
 * visibility for the HOME query comes from this module's manifest `<queries>`.
 */
object RuntimeSafetySurfaces {

    const val SOURCE_HOME = "home"
    const val SOURCE_DEFAULT_DIALER = "default-dialer"
    const val SOURCE_SYSTEM_DIALER = "system-dialer"

    /** Source → resolved packages (empty = none configured), or null when that lookup failed. */
    fun resolveBySource(context: Context): Map<String, Set<String>?> {
        val pm = context.packageManager
        val telecom = runCatching {
            context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        }
        return buildMap {
            put(
                SOURCE_HOME,
                runCatching {
                    val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    @Suppress("DEPRECATION") // ResolveInfoFlags overloads need API 33; minSdk is 23.
                    setOfNotNull(
                        pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName,
                    )
                }.getOrNull(),
            )
            put(
                SOURCE_DEFAULT_DIALER,
                telecom.mapCatching { setOfNotNull(it?.defaultDialerPackage) }.getOrNull(),
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    SOURCE_SYSTEM_DIALER,
                    telecom.mapCatching { setOfNotNull(it?.systemDialerPackage) }.getOrNull(),
                )
            }
        }
    }

    /** Union of every lookup that succeeded (for one-shot callers such as the pickers). */
    fun resolve(context: Context): Set<String> =
        resolveBySource(context).values.flatMapTo(LinkedHashSet()) { it.orEmpty() }
}
